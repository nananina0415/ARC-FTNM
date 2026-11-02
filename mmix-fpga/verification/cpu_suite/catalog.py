"""Independent ISA catalog; never extracted from the DUT decoder."""
PAIRS = {
    0x18: "MUL", 0x1a: "MULU", 0x1c: "DIV", 0x1e: "DIVU",
    0x20: "ADD", 0x22: "ADDU", 0x24: "SUB", 0x26: "SUBU",
    0x28: "2ADDU", 0x2a: "4ADDU", 0x2c: "8ADDU", 0x2e: "16ADDU",
    0x30: "CMP", 0x32: "CMPU", 0x34: "NEG", 0x36: "NEGU",
    0x38: "SL", 0x3a: "SLU", 0x3c: "SR", 0x3e: "SRU",
    **{0x60+2*i: n for i,n in enumerate(["CSN","CSZ","CSP","CSOD","CSNN","CSNZ","CSNP","CSEV",
                                         "ZSN","ZSZ","ZSP","ZSOD","ZSNN","ZSNZ","ZSNP","ZSEV"])},
    0x8c: "LDO", 0x8e: "LDOU", 0xac: "STO", 0xae: "STOU",
    **{0xc0+2*i: n for i,n in enumerate(["OR","ORN","NOR","XOR","AND","ANDN","NAND","NXOR"])},
    0xd8: "MUX",
}
ISA = {op+imm: name+("I" if imm else "") for op,name in PAIRS.items() for imm in (0,1)}
ISA[0] = "HALT"
SPECIAL = ["rB","rD","rE","rH","rJ","rM","rR","rBB","rC","rN","rO","rS","rI","rT","rTT","rK",
           "rQ","rU","rV","rG","rL","rA","rF","rP","rW","rX","rY","rZ","rWW","rXX","rYY","rZZ"]
RAM_BYTES = 4096 + 32
MASK = (1 << 64)-1


def ram_index(address):
    if 0 <= address < 4096:
        return address
    if 0x3fffffe0 <= address < 0x40000000:
        return 4096 + address - 0x3fffffe0
    raise ValueError(f"Unmapped test RAM address {address:#x}")


def integer(value):
    if not isinstance(value, str):
        raise ValueError("Architectural integers must be strings")
    return int(value, 0)


def required_coverage(op):
    """Mandatory semantic boundaries, independent of executed-case discovery."""
    if not op:return ['stop']
    base=op&~1
    if base in (0x8c,0x8e,0xac,0xae):
        return ['address_'+str(n) for n in (0,1,7,8,15,16,23,24,31,32)]+['range_error','last_valid_octa','destination_alias_y']+([] if op&1 else ['destination_alias_z'])
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
