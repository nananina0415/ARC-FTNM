"""Independent ISA catalog; never extracted from the DUT decoder."""
import re

PAIRS = {
    0x18: "MUL", 0x1a: "MULU", 0x1c: "DIV", 0x1e: "DIVU",
    0x20: "ADD", 0x22: "ADDU", 0x24: "SUB", 0x26: "SUBU",
    0x28: "2ADDU", 0x2a: "4ADDU", 0x2c: "8ADDU", 0x2e: "16ADDU",
    0x30: "CMP", 0x32: "CMPU", 0x34: "NEG", 0x36: "NEGU",
    0x38: "SL", 0x3a: "SLU", 0x3c: "SR", 0x3e: "SRU",
    **{0x60+2*i: n for i,n in enumerate(["CSN","CSZ","CSP","CSOD","CSNN","CSNZ","CSNP","CSEV",
                                         "ZSN","ZSZ","ZSP","ZSOD","ZSNN","ZSNZ","ZSNP","ZSEV"])},
    0x80: 'LDB',0x82:'LDBU',0x84:'LDW',0x86:'LDWU',0x88:'LDT',0x8a:'LDTU',
    0x8c: "LDO", 0x8e: "LDOU",0xa2:'STBU',0xa6:'STWU',0xaa:'STTU',
    0xac: "STO", 0xae: "STOU",0xb4:'STCO',0xbe:'PUSHGO',
    **{0xc0+2*i: n for i,n in enumerate(["OR","ORN","NOR","XOR","AND","ANDN","NAND","NXOR"])},
    0xd8: "MUX",
}
ISA = {op+imm: name+("I" if imm else "") for op,name in PAIRS.items() for imm in (0,1)}
CONSTANTS = {0xe0+i:name for i,name in enumerate([
    'SETH','SETMH','SETML','SETL','INCH','INCMH','INCML','INCL',
    'ORH','ORMH','ORML','ORL','ANDNH','ANDNMH','ANDNML','ANDNL'])}
ISA.update(CONSTANTS)
CONDITIONS = ['N','Z','P','OD','NN','NZ','NP','EV']
BRANCHES = {0x40+16*hint+2*i+back:('PB' if hint else 'B')+name+('B' if back else '')
            for hint in (0,1) for i,name in enumerate(CONDITIONS) for back in (0,1)}
BRANCHES.update({0xf0:'JMP',0xf1:'JMPB'})
ISA.update(BRANCHES)
ADDRESSES={0xf4:'GETA',0xf5:'GETAB'}
GO_OPS={0x9e:'GO',0x9f:'GOI'}
SPECIAL_OPS={0xf6:'PUT',0xf7:'PUTI',0xfe:'GET'}
ISA.update(ADDRESSES)
ISA.update(GO_OPS)
ISA.update(SPECIAL_OPS)
STACK_OPS={0xbe:'PUSHGO',0xbf:'PUSHGOI',0xf2:'PUSHJ',0xf3:'PUSHJB',0xf8:'POP',0xfa:'SAVE',0xfb:'UNSAVE',0xfd:'SWYM'}
ISA.update(STACK_OPS)
ISA[0] = "HALT"  # XYZ=0 stops the CPU; any other XYZ is a general TRAP with the same opcode.
SPECIAL = ["rB","rD","rE","rH","rJ","rM","rR","rBB","rC","rN","rO","rS","rI","rT","rTT","rK",
           "rQ","rU","rV","rG","rL","rA","rF","rP","rW","rX","rY","rZ","rWW","rXX","rYY","rZZ"]
RAM_BYTES = 4096 + 32
MASK = (1 << 64)-1
LOAD_BASES=set(range(0x80,0x90,2))
STORE_BASES={0xa2,0xa6,0xaa,0xac,0xae,0xb4}
SPECIAL_IMPLEMENTED=set(range(8))|{13,15}|set(range(21,32))  # 13=rT, 15=rK for TRAP
SPECIAL_STACK_READ_ONLY={10,11}  # rO, rS
SPECIAL_STACK_CHECKED={19,20}  # rG, rL


def memory_shape(op):
    base=op&~1
    if base in LOAD_BASES:
        width=1<<((base-0x80)//4)
        return width,bool(base&2),False
    if base in STORE_BASES:
        return (8 if base==0xb4 else 1<<((base-0xa0)//4)),True,True
    raise ValueError(f'Not a memory opcode: {op:02x}')


def ram_index(address):
    if 0 <= address < 4096:
        return address
    if 0x3fffffe0 <= address < 0x40000000:
        return 4096 + address - 0x3fffffe0
    raise ValueError(f"Unmapped test RAM address {address:#x}")


SLOTS = 512  # ring l[0..255] in slots 0..255, global g[k] in slot 256+k


def slot_name(slot):
    """Physical slot name in mmix-sim notation: l[i] for the local ring, g[k] for globals."""
    return f'l[{slot}]' if slot < 256 else f'g[{slot-256}]'


def register_slot(key, explicit_stack):
    """Case register key to physical slot. Without an explicit stack the fixture uses the flat
    window rG=128, rL=127, rO=0, so $k (k<128) is ring slot k and $k (k>=128) is global."""
    if explicit_stack:
        match = re.fullmatch(r'([lg])\[(0|[1-9][0-9]*)\]', key)
        if not match or int(match[2]) >= 256: raise ValueError(f'Invalid register slot: {key}')
        return int(match[2]) + (256 if match[1] == 'g' else 0)
    match = re.fullmatch(r'\$(0|[1-9][0-9]*)', key)
    if not match or int(match[1]) >= 256: raise ValueError(f'Invalid GPR: {key}')
    k = int(match[1])
    return k if k < 128 else 256+k


def integer(value):
    if not isinstance(value, str):
        raise ValueError("Architectural integers must be strings")
    return int(value, 0)


def required_coverage(op):
    """Mandatory semantic boundaries, independent of executed-case discovery."""
    if not op:return ['stop','trap_basic','trap_x_field','trap_marginal_operands','trap_global_operands',
                      'trap_y_255','trap_locals_preserved','trap_rk_cleared']
    if op==0xfd:return ['swym_zero','swym_fields','swym_no_grow']
    if op in (0xbe,0xbf,0xf2,0xf3):
        return ['basic','x_zero','x_marginal','x_global','x_global_l_zero','return_value','ring_wrap',
                'spill_fill_g40','spill_fill_g200','spill_fill_unaligned','spill_partial']
    if op==0xf8:
        return ['pop_zero','pop_one','pop_two','pop_above_l','pop_return_offset','pop_g_clamp',
                'pop_l_reaches_g','pop_multi_fill']
    if op==0xfa:
        return ['save_l_zero','save_locals','save_limit','save_pending','save_x_boundary',
                'save_special_values','save_x_not_global','save_invalid_z']
    if op==0xfb:
        return ['unsave_round_trip','unsave_special_values','unsave_after_put_rg','unsave_g_below_32',
                'unsave_z_unaligned','unsave_ra_masked','unsave_count_clamp','unsave_invalid_x']
    if op in GO_OPS:
        return ['basic','destination_register0','destination_register255','destination_alias_y',
                'next_instruction','backward','low_bits','wraparound','target_fault'] + (
                ['immediate_0','immediate_255'] if op&1 else ['destination_alias_z'])
    if op in ADDRESSES:
        return ['basic','destination_register0','destination_register255','zero','one','sign_before','sign_boundary','max_wyde','enables_preserved']
    if op in SPECIAL_OPS:
        stack=['rl','rg','ro','rs'] if op==0xfe else ['rl_shrink','rl_no_grow','rg_raise','rg_lower_clear',
              'rg_lower_pending','rg_below_32','rg_below_l']
        return ['basic','destination_register0','destination_register255','ra_supported',
                'unsupported_system_register','invalid_y','invalid_index'] + (['ra_unsupported_bits'] if op!=0xfe else []) + stack
    if op in BRANCHES:
        tags=['basic','destination_register0','destination_register255','enables_preserved',
              'offset_extreme','offset_sign_boundary','offset_before_sign_boundary','offset_near']
        if op<0xf0:
            tags += ['not_taken']+[f'condition_{n}' for n in range(7)]
            if not op&1:tags += ['zero_not_taken']
        if not op&1:tags += ['next_line','cache_conflict','next_instruction']
        if op in (0x4b,0x5b,0xf1):tags += ['counted_loop']
        return tags
    if op in CONSTANTS:
        return (['compose_64bit'] if op==0xe0 else []) + ['basic','zero','destination_register0','destination_register255',
                'destination_alias_y','destination_alias_z','sticky_events','enables_preserved',
                'carry_between_wydes','wraparound'] + [f'wyde_{n:04x}' for n in (0,1,0xff,0x100,0x7fff,0x8000,0xffff,0x1234)] + [
                'old_zero','old_ones','old_sign_bit','old_signed_max']
    base=op&~1
    if base in LOAD_BASES|STORE_BASES:
        return ['address_'+str(n) for n in (0,1,7,8,15,16,23,24,31,32)]+['range_error','last_valid_octa','address_wrap','destination_alias_y']+([] if op&1 else ['destination_alias_z'])+['value_zero','value_sign_bit','value_max_positive','value_ones']
    tags=['basic','zero','max_plus_one','min_one','minus_one_one','min_minus_one','opposite_sign','alternating',
          'destination_alias_y','destination_register0','destination_register255','sticky_events']
    if op&1:tags += ['immediate_'+str(n) for n in (0,1,127,128,255)]
    else:tags += ['destination_alias_z','sources_alias','all_alias_register255']
    if base in (0x34,0x36):tags += ['y_byte_'+str(n) for n in (0,1,127,128,255)]
    if 0x60<=base<=0x7e:tags += ['condition_'+str(n) for n in range(7)]
    if 0x38<=base<=0x3e:
        tags += [f'{prefix}_{n}' for prefix in ('shift','shift_zero') for n in (0,1,31,32,63,64,65,255)]
        if not op&1:tags += ['shift_max']
    if base in (0x18,0x1a):tags += ['product_high','product_signed_overflow']
    if base in (0x1c,0x1e):tags += ['zero_divisor','negative_nonexact','negative_divisor','both_negative','signed_min_overflow']
    if base==0x1e:tags += [f'high_{n:x}' for n in (0,2,3,4,MASK)]
    if base==0xd8:tags += ['mask_zero','mask_ones','mask_alternating']
    if 0xc0<=base<=0xce:tags += ['single_bit_'+str(n) for n in (0,7,8,31,32,63)]
    if base in (0x18,0x1c,0x20,0x24,0x34,0x38):tags += ['overflow_enabled']
    if base==0x1c:tags += ['divide_zero_enabled']
    return tags
