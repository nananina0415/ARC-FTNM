"""Register stack oracle written from mmix-doc 'Register stack' and mmix-sim PUSH/POP.

Slots are physical: k>=G uses slot k, k<L uses (alpha+k) mod R, where R is the largest
power of two not above G. It never reads the RTL or the crosscompiler output.
"""
from .catalog import MASK, SPECIAL


SAVED_SPECIAL = [0, 1, 2, 3, 4, 5, 6, 23, 24, 25, 26, 27]  # rB..rR, rP..rZ


class StackFault(Exception):
    def __init__(self, reason, executed=True):
        super().__init__(reason)
        self.reason = reason
        self.executed = executed


class StackModel:
    def __init__(self, program, labels, slots, special, memory):
        self.program, self.labels = program, labels
        self.slots, self.spec, self.memory = list(slots), list(special), dict(memory)
        self.pc = 0
        self.visits = {}
        self.register_writes = 0
        self.stores = 0

    def get(self, name): return self.spec[SPECIAL.index(name)]
    def put(self, name, value): self.spec[SPECIAL.index(name)] = value & MASK

    @property
    def R(self):
        G = self.get('rG')
        return 128 if G >= 128 else 64 if G >= 64 else 32

    @property
    def alpha(self): return (self.get('rO') >> 3) & (self.R-1)

    @property
    def gamma(self): return (self.get('rS') >> 3) & (self.R-1)

    def ring(self, offset): return (self.alpha+offset) & (self.R-1)

    def pending(self): return (self.get('rO')-self.get('rS')) >> 3

    def read(self, k):
        if k >= self.get('rG'): return self.slots[k]
        if k < self.get('rL'): return self.slots[self.ring(k)]
        return 0

    def write(self, k, value):
        slot = k if k >= self.get('rG') else self.ring(k)
        self.slots[slot] = value & MASK
        self.register_writes += 1

    def spill(self):
        value = self.slots[self.gamma]
        address = self.get('rS')
        for i, byte in enumerate(value.to_bytes(8, 'big')): self.memory[address+i] = byte
        self.put('rS', address+8)
        self.stores += 1

    def fill(self):
        self.put('rS', self.get('rS')-8)
        address = self.get('rS')
        self.slots[self.gamma] = int.from_bytes(bytes(self.memory.get(address+i, 0xa5) for i in range(8)), 'big')

    def increase_l(self):
        self.slots[self.ring(self.get('rL'))] = 0
        self.put('rL', self.get('rL')+1)
        if (self.pending()+self.get('rL')) & (self.R-1) == 0: self.spill()

    def grow_to(self, x):
        """Each increase returns to EXECUTE, so it counts as another visit."""
        while self.get('rG') > x >= self.get('rL'):
            if x >= self.R-1: raise StackFault(2)
            self.increase_l()
            self.visits[self.pc] = self.visits.get(self.pc, 0)+1

    def push(self, x, target):
        if x < self.get('rG'):
            self.grow_to(x)
            n = x
        else:
            self.increase_l()
            n = self.get('rL')-1
        self.slots[self.ring(n)] = n
        self.put('rJ', self.pc+4)
        self.put('rL', self.get('rL')-n-1)
        self.put('rO', self.get('rO')+8*(n+1))
        self.pc = target

    def pop(self, x, yz):
        L = self.get('rL')
        y = self.slots[self.ring(x-1)] if x != 0 and x <= L else 0
        if self.pending() == 0: self.fill()
        k = self.slots[self.ring(-1)] & 0xff
        if k >= self.R: raise StackFault(2)
        while self.pending() <= k: self.fill()
        new_l = min(k+(x if x <= L else L+1), self.get('rG'))
        if new_l >= self.R: raise StackFault(2)
        if new_l > k: self.slots[self.ring(-1)] = y
        self.pc = (self.get('rJ')+4*yz) & MASK
        self.put('rO', self.get('rO')-8*(k+1))
        self.put('rL', new_l)

    def store_octa(self, value):
        address = self.get('rS')
        for i, byte in enumerate(value.to_bytes(8, 'big')): self.memory[address+i] = byte
        self.put('rS', address+8)
        self.stores += 1

    def load_octa(self):
        self.put('rS', self.get('rS')-8)
        address = self.get('rS')
        return int.from_bytes(bytes(self.memory.get(address+i, 0xa5) for i in range(8)), 'big')

    def save(self, x):
        self.increase_l()
        n = self.get('rL')-1
        self.slots[self.ring(n)] = n
        self.put('rO', self.get('rO')+8*(n+1))
        self.put('rL', 0)
        while self.pending() != 0: self.spill()
        for k in range(self.get('rG'), 256): self.store_octa(self.slots[k])
        for k in SAVED_SPECIAL: self.store_octa(self.spec[k])
        self.store_octa((self.get('rG') << 56) | self.get('rA'))
        self.put('rO', self.get('rS'))
        self.write(x, self.get('rS')-8)

    def unsave(self, z):
        self.put('rS', (z & ~7 & MASK)+8)
        word = self.load_octa()
        self.put('rG', max(word >> 56, 32))
        self.put('rA', word & 0xc0c0)
        for k in reversed(SAVED_SPECIAL): self.spec[k] = self.load_octa()
        for k in range(255, self.get('rG')-1, -1): self.slots[k] = self.load_octa()
        self.fill()
        count = self.slots[self.gamma] & 0xff
        if count >= self.R: raise StackFault(2)
        for _ in range(count): self.fill()
        self.put('rO', self.get('rS'))
        self.put('rL', count)

    def put_special(self, name, value):
        if name == 'rL':
            if value < self.get('rL'): self.put('rL', value)
        elif name == 'rG':
            old_r, L = self.R, self.get('rL')
            new_r = 128 if value >= 128 else 64 if value >= 64 else 32
            if value > 255 or value < 32 or value < L or (new_r != old_r and L != 0):
                raise StackFault(2, executed=False)
            if new_r != old_r:
                while self.pending() != 0: self.spill()
            for slot in range(value, self.get('rG')): self.slots[slot] = 0
            self.put('rG', value)
        else:
            self.put(name, value)

    def step(self, instruction):
        op = instruction[0]
        if op == 'RAW' or (op == 'SAVE' and instruction[1] < self.get('rG')):
            raise StackFault(2, executed=False)
        if op == 'SAVE':
            self.visits[self.pc] = self.visits.get(self.pc, 0)+1
            self.save(instruction[1])
            self.pc += 4
            return True
        if op == 'UNSAVE':
            self.visits[self.pc] = self.visits.get(self.pc, 0)+1
            self.unsave(self.read(instruction[1]))
            self.pc += 4
            return True
        if op == 'PUT' and instruction[1] == 'rG':
            value = self.operand(instruction[2])
            self.put_special('rG', value)
            self.visits[self.pc] = self.visits.get(self.pc, 0)+1
            self.pc += 4
            return True
        self.visits[self.pc] = self.visits.get(self.pc, 0)+1
        if op == 'TRAP' and len(instruction) == 1: return False
        if op == 'SWYM':
            self.pc += 4
            return True
        if op == 'TRAP':
            x, y, z = instruction[1:]
            self.put('rWW', self.pc+4)
            self.put('rXX', (1 << 63) | (x << 16) | (y << 8) | z)
            self.put('rYY', self.read(y))
            self.put('rZZ', self.read(z))
            self.put('rBB', self.slots[255])
            self.slots[255] = self.get('rJ')
            self.put('rK', 0)
            self.pc = self.get('rT')
            return True
        if op == 'PUSHJ':
            self.push(instruction[1], self.labels[instruction[2]])
        elif op == 'PUSHGO':
            self.grow_if_local(instruction[1])
            target = (self.read(instruction[2])+self.operand(instruction[3])) & MASK
            self.push(instruction[1], target)
        elif op == 'POP':
            self.pop(instruction[1], instruction[2])
        elif op == 'JMP':
            self.pc = self.labels[instruction[1]]
        elif op == 'BZ':
            self.pc = self.labels[instruction[2]] if self.read(instruction[1]) == 0 else self.pc+4
        elif op == 'PUT':
            self.put_special(instruction[1], self.operand(instruction[2]))
            self.pc += 4
        else:
            x = instruction[1]
            self.grow_to(x)
            if op == 'SETL': self.write(x, instruction[2])
            elif op == 'ADDU': self.write(x, self.read(instruction[2])+self.operand(instruction[3]))
            elif op == 'SUBU': self.write(x, self.read(instruction[2])-self.operand(instruction[3]))
            elif op == 'GET': self.write(x, self.get(instruction[2]))
            elif op == 'GETA': self.write(x, self.labels[instruction[2]])
            elif op == 'CSN':
                if self.read(instruction[2]) >> 63: self.write(x, self.read(instruction[3]))
            else: raise ValueError(f'No stack oracle for {op}')
            self.pc += 4
        return True

    def grow_if_local(self, x):
        if x < self.get('rG'): self.grow_to(x)

    def operand(self, value):
        return self.read(value[1]) if isinstance(value, tuple) else value

    def run(self, limit=20000):
        """Returns (stop, reason). The final PC stays on a faulting or halting instruction."""
        for _ in range(limit):
            instruction = self.program[self.pc//4]
            try:
                if not self.step(instruction): return 'halt', 0
            except StackFault as fault:
                return 'fault', fault.reason
        raise ValueError('Stack program did not stop')
