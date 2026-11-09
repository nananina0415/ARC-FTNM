"""Materialize reviewable .mms + expectation sidecars for the supported ISA."""
from pathlib import Path
import json

from .catalog import ADDRESSES, BRANCHES, CONSTANTS, ISA, LOAD_BASES, STORE_BASES, MASK, SPECIAL, SPECIAL_IMPLEMENTED, SPECIAL_OPS, memory_shape, required_coverage
from .reference import execute

ROOT = Path(__file__).resolve().parents[2]


def write_case(root, op, label, a=7, b=3, *, x=3, y=1, z=2, rd=0, ra=0,
               rm=0xaaaaaaaa55555555, alias=None, memory=None, payload=None):
    mnemonic = ISA[op]
    constant = op in CONSTANTS
    immediate = constant or bool(op&1)
    neg = (op&~1) in (0x34,0x36)
    memory_op = (op&~1) in LOAD_BASES|STORE_BASES
    if immediate: b &= 0xffff if constant else 255
    if alias == "xy": x=y
    if alias == "xz" and not immediate: x=z
    if alias == "yz" and not immediate: z=y; b=a
    if alias == "all" and not immediate: x=y=z=255; b=a
    registers, special = [0]*256, [0]*32
    special[19] = 255  # MMIXAL default rG metadata, not a register-window implementation.
    special[1], special[3], special[5], special[6], special[21] = rd,0x1122334455667788,rm,0x8877665544332211,ra
    registers[y] = a&MASK
    if not immediate: registers[z]=b&MASK
    if x not in (y,z) or (immediate and x!=y): registers[x]=0xdeadbeef12345678
    mem = dict(memory or {})
    if memory_op:
        registers[y] = a&MASK
        if not immediate: registers[z] = b&MASK
        if (op&~1) in STORE_BASES and x not in (y,z): registers[x]=0x0123456789abcdef
        if payload is not None:registers[x]=payload&MASK
    encoded_y = y
    encoded_z = b if immediate else z
    if constant:
        encoded_y, encoded_z = b>>8, b&255
        registers = [0]*256
        registers[x] = a&MASK
        # Deliberately distinct values catch accidental reads from encoded Y/Z.
        for r in (encoded_y,encoded_z):
            if r!=x:registers[r]=0x8765432112345678
        word = (op<<24)|(x<<16)|(encoded_y<<8)|encoded_z
        instruction = f'{mnemonic} ${x},#{b:04x}'
    elif op == 0:
        word, instruction = 0,"TRAP 0,Halt,0"
    else:
        word = (op<<24)|(x<<16)|(encoded_y<<8)|encoded_z
        middle = str(encoded_y) if neg else f"${encoded_y}"
        last = str(encoded_z) if immediate else f"${encoded_z}"
        # MMIXAL chooses the immediate opcode from the final operand's type.
        assembly_mnemonic = mnemonic[:-1] if immediate else mnemonic
        first=str(x) if (op&~1)==0xb4 else f'${x}'
        instruction = f"{assembly_mnemonic} {first},{middle},{last}"
    answer = execute(op,x,encoded_y,encoded_z,registers,special,mem)
    name = f"{mnemonic.lower()}.{label}"
    directory = root / "instructions" / mnemonic.lower()
    directory.mkdir(parents=True,exist_ok=True)
    source = directory / (label+".mms")
    text = f"% {name}\n        LOC 0\nMain    {instruction}\n"
    if op != 0: text += "        TRAP 0,Halt,0\n"
    source.write_text(text,encoding="utf-8",newline="\n")
    spec_diff = {SPECIAL[i]:f"0x{value:016x}" for i,value in enumerate(answer["special"]) if value != special[i]}
    reg_diff = {f"${i}":f"0x{value:016x}" for i,value in enumerate(answer["registers"]) if value != registers[i]}
    mem_diff = {f"0x{addr:x}":f"{value:02x}" for addr,value in answer["memory"].items() if value != mem.get(addr,0xa5)}
    case = {"schema":1,"id":name,"target":{"mnemonic":mnemonic,"opcode":f"0x{op:02x}","pc":"0x0","word":f"0x{word:08x}","executions":1},
            "tags":[label,"immediate" if immediate else "register"],
            "initial":{"entry":"0x0","registers":{f"${i}":f"0x{v:016x}" for i,v in enumerate(registers) if v},
                       "special":{SPECIAL[i]:f"0x{v:016x}" for i,v in enumerate(special) if v},
                       "memory":{f"0x{k:x}":f"{v:02x}" for k,v in mem.items()}},
            "expect":{"stop":answer["stop"],"reason":answer["reason"],"registers":reg_diff,"special":spec_diff,"memory":mem_diff,
                      "unchanged_other_state":True,"subject_effects":{"register_writes":answer["register_writes"],"memory_writes":answer["memory_writes"]}},
            "coverage":[label],"profiles":["no_stall","backpressure"],"limits":{"cycles":2000,"wall_seconds":60}}
    source.with_suffix(".case.json").write_text(json.dumps(case,indent=2)+"\n",encoding="utf-8")
    return name


def write_composition(root):
    """Construct a 64-bit constant independently of the old destination value."""
    directory=root/'instructions/seth'
    directory.mkdir(parents=True,exist_ok=True)
    (directory/'compose_64bit.mms').write_text(
        '% Construct all four wydes through ordinary instructions.\n'
        '        LOC 0\n'
        'Main    SETH $3,#0123\n'
        '        ORMH $3,#4567\n'
        '        ORML $3,#89ab\n'
        '        ORL $3,#cdef\n'
        '        TRAP 0,Halt,0\n',encoding='utf-8',newline='\n')
    case={'schema':1,'id':'seth.compose_64bit',
          'target':{'mnemonic':'SETH','opcode':'0xe0','pc':'0x0','word':'0xe0030123','executions':1},
          'tags':['integration','constant'],
          'initial':{'entry':'0x0','registers':{'$3':'0xffffffffffffffff'},
                     'special':{'rG':'0xff','rA':'0xc0c0'},'memory':{}},
          'expect':{'stop':'halt','reason':0,'registers':{'$3':'0x0123456789abcdef'},
                    'special':{},'memory':{},'unchanged_other_state':True,
                    'subject_effects':{'register_writes':4,'memory_writes':0}},
          'coverage':['compose_64bit'],'profiles':['no_stall','backpressure'],
          'limits':{'cycles':2000,'wall_seconds':60}}
    (directory/'compose_64bit.case.json').write_text(json.dumps(case,indent=2)+'\n',encoding='utf-8')
    return case['id']


def generate(root):
    names=[]
    for op,mnemonic in sorted(ISA.items()):
        if op in BRANCHES or op in ADDRESSES or op in SPECIAL_OPS:continue
        def emit(label,*args,**kwargs): names.append(write_case(root,op,label,*args,**kwargs))
        if op == 0:
            emit("stop");continue
        if op in CONSTANTS:
            old=0x0123456789abcdef
            emit('basic',old,0x1234)
            emit('zero',0,0)
            for imm in (0,1,0xff,0x100,0x7fff,0x8000,0xffff,0x1234):
                emit(f'wyde_{imm:04x}',old,imm)
            for label,value in [('old_zero',0),('old_ones',MASK),('old_sign_bit',1<<63),('old_signed_max',(1<<63)-1)]:
                emit(label,value,0x8001)
            emit('destination_register0',old,0x1234,x=0)
            emit('destination_register255',old,0x1234,x=255)
            emit('destination_alias_y',old,0x1234,x=0x12)
            emit('destination_alias_z',old,0x1234,x=0x34)
            emit('sticky_events',old,0xffff,ra=0xc0)
            emit('enables_preserved',MASK,0xffff,ra=0xc0c0)
            shift=16*(3-(op&3))
            emit('carry_between_wydes',(1<<(shift+16))-1,1)
            emit('wraparound',MASK,1)
            continue
        base = op&~1
        if base in LOAD_BASES|STORE_BASES:
            width,unsigned,store=memory_shape(op)
            for offset in (0,1,7,8,15,16,23,24,31,32):
                address=(0x100+offset)&~(width-1)
                mem={address+i:v for i,v in enumerate(bytes.fromhex("0180ff7f55aa1234"))}
                emit(f"address_{offset}",0x100,offset,memory=mem)
            emit("range_error",0x40000000,0)
            emit("last_valid_octa",0x40000000-width,0,memory={0x40000000-width+i:i+1 for i in range(width)})
            emit('address_wrap',MASK-63,192,memory={0x80+i:i+1 for i in range(width)})
            emit("destination_alias_y",0x100,8,alias="xy")
            if not op&1: emit("destination_alias_z",0x100,8,alias="xz")
            for label,value in [('value_zero',0),('value_sign_bit',1<<(width*8-1)),
                                ('value_max_positive',(1<<(width*8-1))-1),('value_ones',(1<<(width*8))-1)]:
                mem={0x100+i:v for i,v in enumerate(value.to_bytes(width,'big'))}
                if store:mem={0x100+i:0x5a for i in range(width)}
                emit(label,0x100,0,memory=mem,payload=value if store else None)
            if base==0xb4:
                for value in (0,1,127,128,255):emit(f'constant_{value}',0x100,0,x=value)
            continue
        vectors=[("basic",7,3),("zero",0,0),("max_plus_one",(1<<63)-1,1),
                 ("min_one",1<<63,1),("minus_one_one",MASK,1),("min_minus_one",1<<63,MASK),
                 ("opposite_sign",MASK-6,3),("alternating",0xaaaaaaaaaaaaaaaa,0x5555555555555555)]
        for label,a,b in vectors: emit(label,a,b)
        if op&1:
            for imm in (0,1,127,128,255):emit(f"immediate_{imm}",0x8000000000000081,imm)
        if base in (0x34,0x36):
            for yy in (0,1,127,128,255):emit(f"y_byte_{yy}",7,255,y=yy)
        if 0x60<=base<=0x7e:
            for i,a in enumerate((0,1,2,MASK,MASK-1,1<<63,(1<<63)-1)):emit(f"condition_{i}",a,0x7b)
        if 0x38<=base<=0x3e:
            for amount in (0,1,31,32,63,64,65,255):
                emit(f"shift_{amount}",0x8000000000000001,amount)
                emit(f"shift_zero_{amount}",0,amount)
            if not op&1:emit("shift_max",MASK,MASK)
        if base in (0x18,0x1a):
            emit("product_high",1<<32,1<<32)
            emit("product_signed_overflow",1<<63,MASK)
        if base in (0x1c,0x1e):
            for label,a,b in [("zero_divisor",123,0),("negative_nonexact",MASK-6,3),("negative_divisor",7,MASK-2),("both_negative",MASK-6,MASK-2),("signed_min_overflow",1<<63,MASK)]:emit(label,a,b)
        if base == 0x1e:
            for high in (0,2,3,4,MASK):emit(f"high_{high:x}",MASK,3,rd=high)
        if base == 0xd8:
            for label,mask in [("mask_zero",0),("mask_ones",MASK),("mask_alternating",0x5555555555555555)]:emit(label,MASK,0,rm=mask)
        if 0xc0<=base<=0xce:
            for bit in (0,7,8,31,32,63):emit(f"single_bit_{bit}",1<<bit,0x55)
        emit("destination_alias_y",7,3,alias="xy")
        if not op&1:
            emit("destination_alias_z",7,3,alias="xz")
            emit("sources_alias",7,7,alias="yz")
            emit("all_alias_register255",7,7,alias="all")
        emit("destination_register0",7,3,x=0)
        emit("destination_register255",7,3,x=255)
        emit("sticky_events",7,3,ra=0xc0)
        if base in (0x18,0x1c,0x20,0x24,0x34,0x38):
            if base in (0x18,0x1c):a,b=1<<63,MASK
            elif base == 0x24:a,b=1<<63,1
            elif base == 0x34:a,b=0,1<<63
            elif base == 0x38:a,b=1<<62,1
            else:a,b=(1<<63)-1,1
            emit("overflow_enabled",a,b,ra=1<<14)
        if base == 0x1c:emit("divide_zero_enabled",123,0,ra=1<<15)
    names.append(write_composition(root))
    from .branch_cases import generate as generate_branches
    names.extend(generate_branches(root))
    from .extended_cases import generate as generate_extended
    names.extend(generate_extended(root))
    catalog=[{"mnemonic":name,"opcode":f"0x{op:02x}","supported":True,
              "required_coverage":required_coverage(op),
              **({'special_registers':[SPECIAL[i] for i in sorted(SPECIAL_IMPLEMENTED)],'ra_mask':'0xc0c0'} if op in SPECIAL_OPS else {})}
             for op,name in sorted(ISA.items())]
    (root/"isa.json").write_text(json.dumps({"schema":1,"instructions":catalog},indent=2)+"\n",encoding="utf-8")
    (root/"profiles.json").write_text(json.dumps({"no_stall":{"id":0},"backpressure":{"id":1}},indent=2)+"\n",encoding="utf-8")
    print(f"Wrote {len(names)} assembly cases for {len(ISA)} ISA entries")
    return names


if __name__ == "__main__":
    generate(ROOT / "test/cpu")
