"""Instruction semantics oracle, independent of compiler/RTL decoder logic."""
from .catalog import MASK, SPECIAL


def signed(value):
    return value-(1<<64) if value & (1<<63) else value


def execute(opcode, x, y, z, registers, special, memory):
    regs, spec, mem = list(registers), list(special), dict(memory)
    write, overflow, divzero, memory_write = True, False, False, 0
    mem_error = False
    a, b = regs[y], z if opcode & 1 else regs[z]
    sa, sb = signed(a), signed(b)
    base, result = opcode & ~1, 0
    changed_special = {}
    if opcode == 0:
        write = False
    elif base in (0x20, 0x22, 0x24, 0x26, 0x34, 0x36):
        if base in (0x34,0x36): a,sa = y,y
        subtract = base in (0x24,0x26,0x34,0x36)
        result = a-b if subtract else a+b
        exact = sa-sb if subtract else sa+sb
        overflow = base in (0x20,0x24,0x34) and not -(1<<63) <= exact < (1<<63)
    elif 0x28 <= base <= 0x2e:
        result = (a << (1+(base-0x28)//2)) + b
    elif base in (0x18,0x1a):
        full = sa*sb if base == 0x18 else a*b
        result = full & MASK
        overflow = base == 0x18 and not -(1<<63) <= full < (1<<63)
        if base == 0x1a: changed_special["rH"] = full >> 64
    elif base == 0x1c:
        result, remainder = (sa//sb, sa%sb) if b else (0,a)
        divzero = b == 0
        overflow = a == 1<<63 and b == MASK
        changed_special["rR"] = remainder & MASK
    elif base == 0x1e:
        high = spec[SPECIAL.index("rD")]
        result, remainder = (high,a) if high >= b else divmod((high<<64)|a,b)
        changed_special["rR"] = remainder
    elif base in (0x30,0x32):
        left,right = (sa,sb) if base == 0x30 else (a,b)
        result = (left>right)-(left<right)
    elif 0x38 <= base <= 0x3e:
        if base in (0x38,0x3a):
            result = a << b if b < 64 else 0
            overflow = base == 0x38 and ((a!=0) if b>=64 else not -(1<<63)<=sa*(1<<b)<(1<<63))
        else:
            result = sa >> min(b,64) if base == 0x3c else a >> min(b,64)
    elif 0x60 <= base <= 0x7e:
        condition = [sa<0,a==0,sa>0,bool(a&1),sa>=0,a!=0,sa<=0,not(a&1)][((base-0x60)//2)&7]
        result = b if condition else 0
        write = bool(condition or base>=0x70)
    elif 0xc0 <= base <= 0xce:
        result = {0xc0:a|b,0xc2:a|~b,0xc4:~(a|b),0xc6:a^b,
                  0xc8:a&b,0xca:a&~b,0xcc:~(a&b),0xce:~(a^b)}[base]
    elif base == 0xd8:
        mask = spec[SPECIAL.index("rM")]
        result = (a&mask)|(b&~mask)
    elif base in (0x8c,0x8e,0xac,0xae):
        address = ((a+b)&MASK)&~7
        mem_error = address >= 1<<30
        if not mem_error:
            if base in (0x8c,0x8e):
                result = int.from_bytes(bytes(mem.get(address+i,0xa5) for i in range(8)),"big")
            else:
                for i,byte in enumerate(regs[x].to_bytes(8,"big")): mem[address+i]=byte
                memory_write = 1
                write = False
        else: write=False
    else:
        raise ValueError(f"No oracle for opcode {opcode:#x}")
    ra = spec[SPECIAL.index("rA")]
    trip = bool((overflow and ra&(1<<14)) or (divzero and ra&(1<<15)))
    if mem_error or trip:
        return {"registers": regs, "special": spec, "memory": memory.copy(),
                "stop":"fault", "reason":1 if mem_error else 4,
                "register_writes":0,"memory_writes":0}
    if overflow and not(ra&(1<<14)): ra |= 1<<6
    if divzero and not(ra&(1<<15)): ra |= 1<<7
    spec[SPECIAL.index("rA")] = ra
    for name,value in changed_special.items(): spec[SPECIAL.index(name)] = value&MASK
    if write: regs[x] = result&MASK
    return {"registers":regs,"special":spec,"memory":mem,"stop":"halt","reason":0,
            "register_writes":int(write),"memory_writes":memory_write}
