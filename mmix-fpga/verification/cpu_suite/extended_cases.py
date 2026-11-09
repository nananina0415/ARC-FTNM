"""GETA and GET/PUT operand coverage plus mixed memory/cache programs."""
import json

from .catalog import ADDRESSES, ISA, MASK, SPECIAL, SPECIAL_IMPLEMENTED, SPECIAL_OPS, SPECIAL_STACK_CHECKED, SPECIAL_STACK_READ_ONLY
from .reference import execute


def save(root,op,label,source,word,*,pc=0,registers=None,special=None,expected=None,
         expected_special=None,memory=None,expected_memory=None,writes=1,stores=0,
         fault=False,executions=1,final_pc=None):
    directory=root/'instructions'/ISA[op].lower()
    directory.mkdir(parents=True,exist_ok=True)
    name=ISA[op].lower()+'.'+label
    (directory/(label+'.mms')).write_text('% '+name+'\n'+source,encoding='utf-8',newline='\n')
    initial_special={'rG':'0xff','rA':'0xc0c0'}
    initial_special.update({SPECIAL[i]:f'0x{v:016x}' for i,v in (special or {}).items()})
    case={'schema':1,'id':name,'target':{'mnemonic':ISA[op],'opcode':f'0x{op:02x}',
          'pc':f'0x{pc:x}','word':f'0x{word:08x}','executions':executions},
          'tags':[label],'initial':{'entry':'0x0',
          'registers':{f'${i}':f'0x{v:016x}' for i,v in (registers or {}).items()},
          'special':initial_special,'memory':{f'0x{i:x}':f'{v:02x}' for i,v in (memory or {}).items()}},
          'expect':{'stop':'fault' if fault else 'halt','reason':2 if fault else 0,
          'pc':f'0x{(pc if fault else pc+4) if final_pc is None else final_pc:016x}',
          'registers':{f'${i}':f'0x{v:016x}' for i,v in (expected or {}).items()},
          'special':{SPECIAL[i]:f'0x{v:016x}' for i,v in (expected_special or {}).items()},
          'memory':{f'0x{i:x}':f'{v:02x}' for i,v in (expected_memory or {}).items()},
          'unchanged_other_state':True,'subject_effects':{'register_writes':writes,'memory_writes':stores}},
          'coverage':[label],'profiles':['no_stall','backpressure'],'limits':{'cycles':2000,'wall_seconds':60}}
    (directory/(label+'.case.json')).write_text(json.dumps(case,indent=2)+'\n',encoding='utf-8')
    return name


def address_case(root,op,label,raw=2,x=3):
    pc=32 if op&1 else 0
    delta=raw-(65536 if op&1 else 0)
    address=(pc+delta*4)&MASK
    expr='@'+('+' if delta>=0 else '-')+str(abs(delta*4))
    source='        LOC 0\n'
    if pc:source+='Main    JMP Start\n        LOC 32\n'
    source+=('Start   ' if pc else 'Main    ')+f'GETA ${x},{expr}\n        TRAP 0,Halt,0\n'
    registers={x:0xdeadbeef12345678}
    for r in (raw>>8,raw&255):
        if r!=x:registers[r]=MASK
    return save(root,op,label,source,(op<<24)|(x<<16)|raw,pc=pc,registers=registers,expected={x:address})


def special_case(root,op,label,index=4,value=0x123456789abcdef,*,gpr=3,malformed=None):
    get=op==0xfe
    x,y,z=(gpr,0,index) if get else (index,0,value&255 if op==0xf7 else gpr)
    registers={gpr:0xdeadbeef12345678 if get else value&MASK}
    special={19:255,21:0xc0c0}
    if get and index in SPECIAL_IMPLEMENTED:
        special[index]=value&0xc0c0 if index==21 else value&MASK
    if not get and op==0xf7 and z!=gpr:registers[z]=MASK
    word=(op<<24)|(x<<16)|(y<<8)|z
    if malformed=='y':y=1;word|=1<<8
    if malformed=='index':
        if get:z=32;word=(op<<24)|(x<<16)|32
        else:x=32;word=(op<<24)|(32<<16)|z
    if malformed:
        source=f'        LOC 0\nMain    TETRA #{word:08x}\n        TRAP 0,Halt,0\n'
        answer=None
    else:
        if get:instruction=f'GET ${gpr},{SPECIAL[index]}'
        else:instruction=f'PUT {SPECIAL[index]},'+(str(z) if op==0xf7 else f'${gpr}')
        source='        LOC 0\nMain    '+instruction+'\n        TRAP 0,Halt,0\n'
        r=[0]*256;s=[0]*32
        for i,v in registers.items():r[i]=v
        for i,v in special.items():s[i]=v
        answer=execute(op,x,y,z,r,s,{})
    fault=answer is None or answer['stop']=='fault'
    expected={} if fault else {i:v for i,v in enumerate(answer['registers']) if v!=registers.get(i,0)}
    expected_special={} if fault else {i:v for i,v in enumerate(answer['special']) if v!=special.get(i,0)}
    return save(root,op,label,source,word,registers=registers,special=special,
                expected=expected,expected_special=expected_special,
                writes=int(get and not fault),fault=fault,executions=0 if fault else 1)


def integrations(root):
    names=[]
    source=('        LOC 0\nMain    SETL $1,#0100\n        LDOU $2,$1,0\n'
            '        SETL $3,#abcd\n        STWU $3,$1,2\n        LDOU $4,$1,0\n'
            '        LDB $5,$1,2\n        LDWU $6,$1,2\n        LDTU $7,$1,0\n        TRAP 0,Halt,0\n')
    names.append(save(root,0xa7,'masked_store_then_cached_loads',source,0xa7030102,pc=12,
        memory={0x100+i:i+1 for i in range(8)},expected_memory={0x102:0xab,0x103:0xcd},
        expected={1:0x100,2:0x0102030405060708,3:0xabcd,4:0x0102abcd05060708,
                  5:0xffffffffffffffab,6:0xabcd,7:0x0102abcd},writes=7,stores=1,final_pc=32))
    source=('        LOC 0\nMain    SETL $1,2\n        PUT rD,$1\n'
            '        SETL $2,3\n        SETL $3,4\n        DIVU $4,$2,$3\n        GET $5,rR\n'
            '        SETL $1,#00ff\n        PUT rM,$1\n        MUX $6,$2,$3\n'
            '        GET $7,rD\n        TRAP 0,Halt,0\n')
    names.append(save(root,0xf6,'alu_direct_round_trip',source,0xf6010001,pc=4,
        expected={1:255,2:3,3:4,4:1<<63,5:3,6:3,7:2},
        expected_special={1:2,5:255,6:3},writes=8,final_pc=40))
    return names


def generate(root):
    names=[]
    for op in ADDRESSES:
        for label,raw in [('basic',2),('zero',0),('one',1),('sign_before',0x7fff),('sign_boundary',0x8000),('max_wyde',0xffff),('enables_preserved',0xffff)]:
            names.append(address_case(root,op,label,raw))
        for x in (0,255):names.append(address_case(root,op,f'destination_register{x}',0x1234,x))
    for op in SPECIAL_OPS:
        names.append(special_case(root,op,'basic'))
        for gpr in (0,255):names.append(special_case(root,op,f'destination_register{gpr}',value=gpr if op==0xf7 else 0x12345678,gpr=gpr))
        names.append(special_case(root,op,'ra_supported',index=21,value=0xc0c0 if op!=0xf7 else 0xc0))
        names.append(special_case(root,op,'unsupported_system_register',index=8))
        names.append(special_case(root,op,'invalid_y',malformed='y'))
        names.append(special_case(root,op,'invalid_index',malformed='index'))
        if op!=0xfe:names.append(special_case(root,op,'ra_unsupported_bits',index=21,value=1))
        for index in sorted(SPECIAL_IMPLEMENTED):
            for value in ((0,MASK) if op==0xfe else (0,1,255) if op==0xf7 else (0,0x123456789abcdef,MASK)):
                names.append(special_case(root,op,f'{SPECIAL[index].lower()}_{value:x}',index,value))
        # stack_cases.py covers the accepted rO/rS reads and rG/rL accesses.
        accepted=SPECIAL_STACK_CHECKED|(SPECIAL_STACK_READ_ONLY if op==0xfe else set())
        for index in sorted(set(range(8,21))-accepted):names.append(special_case(root,op,f'unsupported_{SPECIAL[index].lower()}',index))
    names.extend(integrations(root))
    return names
