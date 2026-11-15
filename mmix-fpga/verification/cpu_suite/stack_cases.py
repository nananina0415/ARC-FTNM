"""PUSH/POP, marginal register and rG/rL programs with explicit register stack fixtures."""
import json

from .catalog import ISA, SPECIAL, MASK
from .stack_model import StackModel

BASE = 0x800  # Mapped test RAM. BASE/8 mod R is 0, so the ring starts at slot 0.


def operand_text(value):
    return f'${value[1]}' if isinstance(value, tuple) else str(value)


def text(instruction):
    op = instruction[0]
    if op == 'TRAP':
        return 'TRAP 0,Halt,0' if len(instruction) == 1 else f'TRAP {instruction[1]},{instruction[2]},{instruction[3]}'
    if op == 'SWYM': return f'SWYM {instruction[1]},{instruction[2]},{instruction[3]}'
    if op == 'RAW': return f'TETRA #{instruction[1]:08x}'
    if op == 'SAVE': return f'SAVE ${instruction[1]},0'
    if op == 'UNSAVE': return f'UNSAVE ${instruction[1]}'
    if op == 'PUSHJ': return f'PUSHJ ${instruction[1]},{instruction[2]}'
    if op == 'PUSHGO': return f'PUSHGO ${instruction[1]},${instruction[2]},{operand_text(instruction[3])}'
    if op == 'POP': return f'POP {instruction[1]},{instruction[2]}'
    if op == 'JMP': return f'JMP {instruction[1]}'
    if op == 'BZ': return f'BZ ${instruction[1]},{instruction[2]}'
    if op == 'PUT': return f'PUT {instruction[1]},{operand_text(instruction[2])}'
    if op == 'GET': return f'GET ${instruction[1]},{instruction[2]}'
    if op == 'GETA': return f'GETA ${instruction[1]},{instruction[2]}'
    if op == 'SETL': return f'SETL ${instruction[1]},#{instruction[2]:04x}'
    if op in ('ADDU','SUBU','CSN'):
        return f'{op} ${instruction[1]},${instruction[2]},{operand_text(instruction[3])}'
    raise ValueError(op)


def assemble(lines):
    """lines: instruction tuples, optionally preceded by a label string."""
    program, labels, source = [], {}, '        LOC 0\n'
    pending = 'Main'
    for line in lines:
        if isinstance(line, str):
            pending = line
            continue
        labels[pending] = len(program)*4 if pending else None
        source += f'{pending or "":<8}{text(line)}\n'
        program.append(line)
        pending = None
    labels.pop(None, None)
    return program, labels, source


def word_of(instruction, pc, labels):
    op = instruction[0]
    if op == 'TRAP': return (instruction[1] << 16) | (instruction[2] << 8) | instruction[3]
    if op == 'SWYM': return (0xfd << 24) | (instruction[1] << 16) | (instruction[2] << 8) | instruction[3]
    if op == 'RAW': return instruction[1]
    if op == 'SAVE': return (0xfa << 24) | (instruction[1] << 16)
    if op == 'UNSAVE': return (0xfb << 24) | instruction[1]
    if op == 'PUSHJ':
        delta = (labels[instruction[2]]-pc)//4
        return ((0xf3 if delta < 0 else 0xf2) << 24) | (instruction[1] << 16) | (delta & 0xffff)
    if op == 'PUSHGO':
        immediate = not isinstance(instruction[3], tuple)
        z = instruction[3] if immediate else instruction[3][1]
        return ((0xbf if immediate else 0xbe) << 24) | (instruction[1] << 16) | (instruction[2] << 8) | z
    if op == 'POP': return (0xf8 << 24) | (instruction[1] << 16) | instruction[2]
    if op == 'PUT':
        immediate = not isinstance(instruction[2], tuple)
        z = instruction[2] if immediate else instruction[2][1]
        return ((0xf7 if immediate else 0xf6) << 24) | (SPECIAL.index(instruction[1]) << 16) | z
    if op == 'GET': return (0xfe << 24) | (instruction[1] << 16) | SPECIAL.index(instruction[2])
    if op == 'ADDU':
        immediate = not isinstance(instruction[3], tuple)
        z = instruction[3] if immediate else instruction[3][1]
        return ((0x23 if immediate else 0x22) << 24) | (instruction[1] << 16) | (instruction[2] << 8) | z
    if op == 'SETL': return (0xe3 << 24) | (instruction[1] << 16) | instruction[2]
    if op == 'CSN': return (0x60 << 24) | (instruction[1] << 16) | (instruction[2] << 8) | instruction[3][1]
    raise ValueError(op)


def save(root, op, label, lines, target, *, g=200, l=5, offset=0, pending=0, registers=None, memory=None, preset=None):
    """target: index of the instruction under test. registers use logical numbers below L or G."""
    program, labels, source = assemble(lines)
    pc = target*4
    word = word_of(program[target], pc, labels)
    if word >> 24 != op: raise ValueError(f'{ISA[op]}.{label}: encoded {word >> 24:#x}')
    special = [0]*32
    for name, value in (('rG', g), ('rL', l), ('rO', BASE+8*offset), ('rS', BASE+8*(offset-pending))):
        special[SPECIAL.index(name)] = value
    for name, value in (preset or {}).items(): special[SPECIAL.index(name)] = value
    slots = [0]*256
    model = StackModel(program, labels, slots, special, memory or {})
    for number, value in (registers or {}).items():
        slot = number if number >= g else model.ring(number) if number < l else number
        slots[slot] = value & MASK
    model = StackModel(program, labels, slots, special, memory or {})
    stop, reason = model.run()
    name = ISA[op].lower()+'.'+label
    directory = root/'instructions'/ISA[op].lower()
    directory.mkdir(parents=True, exist_ok=True)
    (directory/(label+'.mms')).write_text('% '+name+'\n'+source, encoding='utf-8', newline='\n')
    case = {'schema':1,'id':name,'target':{'mnemonic':ISA[op],'opcode':f'0x{op:02x}','pc':f'0x{pc:x}',
            'word':f'0x{word:08x}','executions':model.visits.get(pc,0)},'tags':['stack',label],
            'initial':{'entry':'0x0','explicit_stack':True,
                       'registers':{f'${i}':f'0x{v:016x}' for i,v in enumerate(slots) if v},
                       'special':{SPECIAL[i]:f'0x{v:016x}' for i,v in enumerate(special) if v},
                       'memory':{f'0x{a:x}':f'{v:02x}' for a,v in (memory or {}).items()}},
            'expect':{'stop':stop,'reason':reason,'pc':f'0x{model.pc:016x}',
                      'registers':{f'${i}':f'0x{v:016x}' for i,v in enumerate(model.slots) if v!=slots[i]},
                      'special':{SPECIAL[i]:f'0x{v:016x}' for i,v in enumerate(model.spec) if v!=special[i]},
                      'memory':{f'0x{a:x}':f'{v:02x}' for a,v in model.memory.items() if v!=(memory or {}).get(a,0xa5)},
                      'unchanged_other_state':True,
                      'subject_effects':{'register_writes':model.register_writes,'memory_writes':model.stores}},
            'coverage':[label],'profiles':['no_stall','backpressure'],'limits':{'cycles':60000,'wall_seconds':1200}}
    (directory/(label+'.case.json')).write_text(json.dumps(case,indent=2)+'\n', encoding='utf-8')
    return name


def call(op, x):
    """The call instruction. PUSHGO reads its target from global $254 set up by prepare()."""
    if op in (0xf2, 0xf3): return ('PUSHJ', x, 'Sub')
    return ('PUSHGO', x, 254, ('r', 253)) if op == 0xbe else ('PUSHGO', x, 254, 0)


def call_program(op, x, body):
    """Returns (lines, target index). PUSHJB needs a subroutine placed before the call."""
    prepare = [] if op in (0xf2, 0xf3) else [('GETA', 254, 'Sub')]
    subroutine = ['Sub'] + body
    if op == 0xf3:
        lines = [('JMP', 'Start')] + subroutine + ['Start', call(op, x), ('TRAP',)]
        return lines, 1+len(body)
    lines = prepare + [call(op, x), ('TRAP',)] + subroutine
    return lines, len(prepare)


def recursion(op, depth):
    """Rec saves rJ in $2 and calls itself through $3 until the global $255 reaches zero."""
    prepare = [('SETL', 255, depth)] + ([] if op in (0xf2, 0xf3) else [('GETA', 254, 'Rec')])
    inner = ('PUSHJ', 3, 'Rec') if op in (0xf2, 0xf3) else call(op, 3)
    outer = ('PUSHJ', 3, 'Rec') if op in (0xf2, 0xf3) else call(op, 3)
    head = prepare + [outer, ('TRAP',)]
    body = ['Rec', ('GET', 2, 'rJ'), ('SUBU', 255, 255, 1), ('BZ', 255, 'Base'), inner,
            'Base', ('PUT', 'rJ', ('r', 2)), ('POP', 0, 0)]
    # The outer call is forward and the inner call backward, so each opcode has a target.
    target = len(prepare) if op != 0xf3 else len(head)+3
    return head+body, target


def push_cases(root, op):
    names = []
    def emit(label, x, body=None, **kwargs):
        lines, target = call_program(op, x, body or [('POP', 0, 0)])
        registers = {0:0x10, 1:0x11, 2:0x12, 3:0x13, 4:0x14, 253:0, **kwargs.pop('registers', {})}
        names.append(save(root, op, label, lines, target, registers=registers, **kwargs))
    emit('basic', 2)
    emit('x_zero', 0)
    emit('x_marginal', 7, l=2)
    emit('x_global', 250)
    emit('x_global_l_zero', 250, l=0)
    emit('return_value', 2, [('SETL', 0, 0x5a), ('POP', 1, 0)])
    emit('ring_wrap', 2, l=5, offset=126)
    for g, depth in ((40, 24), (80, 40), (130, 70)):
        lines, target = recursion(op, depth)
        r = 128 if g >= 128 else 64 if g >= 64 else 32
        names.append(save(root, op, f'spill_fill_r{r}', lines, target, g=g, l=2, registers={0:0x10, 1:0x11, 253:0}))
    return names


def pop_cases(root):
    names = []
    def emit(label, body, x=2, **kwargs):
        lines = [('PUSHJ', x, 'Sub'), ('TRAP',), 'Sub'] + body
        names.append(save(root, 0xf8, label, lines, len(body)+1, **kwargs))
    registers = {0:0x10, 1:0x11, 2:0x12, 3:0x13, 4:0x14}
    emit('pop_zero', [('POP', 0, 0)], registers=registers)
    emit('pop_one', [('SETL', 0, 0x5a), ('POP', 1, 0)], registers=registers)
    emit('pop_two', [('SETL', 0, 0x5a), ('SETL', 1, 0x6b), ('POP', 2, 0)], registers=registers)
    emit('pop_above_l', [('POP', 5, 0)], x=4, registers=registers)
    lines = [('PUSHJ', 2, 'Sub'), ('TRAP',), ('TRAP',), 'Sub', ('POP', 0, 1)]
    names.append(save(root, 0xf8, 'pop_return_offset', lines, 3, registers=registers))
    # With R<=G, a sum clamped to G always reaches R, so it stops as a range fault.
    emit('pop_g_clamp_fault', [('SETL', 4, 1), ('POP', 5, 0)], x=30, g=32, l=31)
    emit('pop_range_fault', [('SETL', 1, 1), ('POP', 2, 0)], x=126, g=128, l=127)
    # Growing L spills most of the caller, so POP has to fill several slots.
    emit('pop_multi_fill', [('SETL', 25, 1), ('POP', 0, 0)], x=29, g=32, l=30,
         registers={i:0x100+i for i in range(30)})
    return names


def marginal_cases(root):
    names = []
    names.append(save(root, 0x23, 'marginal_read', [('ADDU', 1, 50, 1), ('TRAP',)], 0, l=2, registers={50:0x77}))
    names.append(save(root, 0x23, 'marginal_write_grows', [('ADDU', 5, 1, 1), ('TRAP',)], 0, l=2,
                      registers={1:0x41, 2:0x22, 3:0x33, 4:0x44}))
    names.append(save(root, 0xe3, 'marginal_limit_fault', [('SETL', 127, 1), ('TRAP',)], 0, g=128, l=1))
    names.append(save(root, 0x60, 'false_condition_grows', [('CSN', 10, 0, ('r', 1)), ('TRAP',)], 0, l=2,
                      registers={0:1, 1:0x55, 5:0x66}))
    return names


def special_cases(root):
    names = []
    for label, name, kwargs in (('rl','rL',{'l':9}), ('rg','rG',{'g':150}), ('ro','rO',{'offset':3}), ('rs','rS',{'offset':3})):
        names.append(save(root, 0xfe, label, [('GET', 3, name), ('TRAP',)], 0, **kwargs))
    def put(label, value, **kwargs):
        registers = kwargs.pop('registers', {})
        for op, operand in ((0xf6, ('r', 250)), (0xf7, value)):
            if op == 0xf7 and value > 255: continue
            names_registers = {**registers, **({250:value} if op == 0xf6 else {})}
            name = 'rL' if label.startswith('rl') else 'rG'
            names.append(save(root, op, label, [('PUT', name, operand), ('TRAP',)], 0,
                              registers=names_registers, **kwargs))
    put('rl_shrink', 3, l=9)
    put('rl_no_grow', 30, l=9)
    put('rg_raise', 240, g=200, l=5)
    put('rg_lower_clear', 150, g=200, l=5, registers={150:0x99, 199:0x88, 149:0x77})
    put('rg_ring_change_empty', 100, g=200, l=0, offset=4, pending=4, registers={0:0x10, 1:0x11, 2:0x12, 3:0x13})
    put('rg_ring_change_with_locals', 100, g=200, l=5)
    put('rg_below_32', 31, g=200, l=5)
    put('rg_below_l', 40, g=64, l=50)
    return names


SAVED_VALUES = {name: 0x1100000000000000+i for i, name in enumerate(
    ('rB','rD','rE','rH','rJ','rM','rR','rP','rW','rX','rY','rZ'))}


def save_cases(root):
    names = []
    locals_ = {i: 0x100+i for i in range(5)}
    def emit(label, x=255, **kwargs):
        names.append(save(root, 0xfa, label, [('SAVE', x), ('TRAP',)], 0, **kwargs))
    emit('save_l_zero', l=0, registers={200:0x77, 255:0x88})
    emit('save_locals', registers={**locals_, 200:0x77})
    # The context stays inside mapped test RAM: 128 ring slots, 56 globals, 13 special slots.
    emit('save_limit', l=127, registers={i: 0x100+i for i in range(127)})
    emit('save_pending', l=3, offset=8, pending=8, registers={i: 0x200+i for i in range(3)})
    emit('save_x_boundary', x=200, registers=locals_)
    emit('save_special_values', registers=locals_, preset={**SAVED_VALUES, 'rA':0xc0c0, 'rF':0x99})
    emit('save_x_not_global', x=100, registers=locals_)
    names.append(save(root, 0xfa, 'save_invalid_z', [('RAW', 0xfaff0001), ('TRAP',)], 0, registers=locals_))
    return names


def context(base, g, count, a=0xc0c0, stored_g=None):
    """A saved context: hole at base, then globals from g, 12 special registers and (rG,rA)."""
    pack = g if stored_g is None else stored_g
    words = [count] + [0x300+k for k in range(g, 256)] + [0x400+i for i in range(12)] + [(pack << 56) | a]
    memory = {base+8*i+j: byte for i, word in enumerate(words) for j, byte in enumerate(word.to_bytes(8, 'big'))}
    return memory, base+8*(len(words)-1)


def unsave_cases(root):
    names = []
    locals_ = {i: 0x100+i for i in range(5)}
    round_trip = [('SAVE', 255), ('SETL', 1, 0x1234), ('SETL', 201, 0x77), ('UNSAVE', 255), ('TRAP',)]
    names.append(save(root, 0xfb, 'unsave_round_trip', round_trip, 3, registers={**locals_, 201:0x55}))
    with_special = [('SAVE', 255), ('SETL', 1, 0xff), ('PUT', 'rM', ('r', 1)), ('UNSAVE', 255), ('TRAP',)]
    names.append(save(root, 0xfb, 'unsave_special_values', with_special, 3, registers=locals_,
                      preset={**SAVED_VALUES, 'rA':0xc0}))
    ring_change = [('SAVE', 255), ('PUT', 'rG', 100), ('UNSAVE', 255), ('TRAP',)]
    names.append(save(root, 0xfb, 'unsave_ring_change', ring_change, 2, registers={**locals_, 201:0x55}))
    base = BASE
    # A stored G below 32 restores as 32, so the context holds the globals from $32.
    memory, top = context(base, 32, 0, stored_g=10)
    names.append(save(root, 0xfb, 'unsave_g_below_32', [('UNSAVE', 255), ('TRAP',)], 0, l=0, registers={255:top}, memory=memory))
    memory, top = context(base, 200, 0)
    names.append(save(root, 0xfb, 'unsave_z_unaligned', [('UNSAVE', 255), ('TRAP',)], 0, l=0, registers={255:top+5}, memory=memory))
    memory, top = context(base, 200, 0, a=0xffff)
    names.append(save(root, 0xfb, 'unsave_ra_masked', [('UNSAVE', 255), ('TRAP',)], 0, l=0, registers={255:top}, memory=memory))
    memory, top = context(base, 128, 200)
    names.append(save(root, 0xfb, 'unsave_count_fault', [('UNSAVE', 255), ('TRAP',)], 0, l=0, registers={255:top}, memory=memory))
    names.append(save(root, 0xfb, 'unsave_invalid_x', [('RAW', 0xfb0100ff), ('TRAP',)], 0, l=0))
    return names


def trap_cases(root):
    """rT points past an invalid word, so falling through to PC+4 stops with a fault."""
    names = []
    def emit(label, x, y, z, *, preset=None, **kwargs):
        lines = [('TRAP', x, y, z), ('RAW', 0x01000000), ('TRAP',)]
        names.append(save(root, 0x00, label, lines, 0, preset={'rT':8, 'rJ':0x1234, 'rK':0x5555, **(preset or {})}, **kwargs))
    locals_ = {0:0x10, 1:0x11, 2:0x22, 3:0x33, 4:0x44, 200:0x200, 255:0xaa}
    emit('trap_basic', 1, 1, 2, registers=locals_)
    emit('trap_x_field', 5, 0, 0, registers=locals_)
    emit('trap_marginal_operands', 1, 50, 60, l=2, registers={0:1, 1:2, 50:0x50, 60:0x60})
    emit('trap_global_operands', 1, 200, 255, registers=locals_)
    emit('trap_y_255', 1, 255, 0, registers=locals_)
    emit('trap_locals_preserved', 1, 14, 0, l=20, registers={i: 0x100+i for i in range(20)})
    emit('trap_rk_cleared', 1, 1, 0, preset={'rK':MASK}, registers=locals_)
    return names


def swym_cases(root):
    names = []
    locals_ = {0:0x10, 1:0x11, 2:0x22}
    for label, x, y, z, kwargs in (('swym_zero', 0, 0, 0, {}), ('swym_fields', 1, 2, 3, {'registers':locals_}),
                                   ('swym_no_grow', 100, 150, 180, {'l':2, 'registers':{100:0x77}})):
        names.append(save(root, 0xfd, label, [('SWYM', x, y, z), ('TRAP',)], 0, **kwargs))
    return names


def generate(root):
    names = []
    names.extend(trap_cases(root))
    names.extend(swym_cases(root))
    for op in (0xbe, 0xbf, 0xf2, 0xf3): names.extend(push_cases(root, op))
    names.extend(pop_cases(root))
    names.extend(marginal_cases(root))
    names.extend(special_cases(root))
    names.extend(save_cases(root))
    names.extend(unsave_cases(root))
    return names
