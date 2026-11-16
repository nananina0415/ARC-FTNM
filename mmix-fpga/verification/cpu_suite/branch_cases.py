"""Materialize real MMIXAL branch programs and literal sidecar assertions."""
import json

from .catalog import BRANCHES, MASK
from .reference import branch_decision


def save(root,op,label,source,pc,word,initial,expected,final_pc,*,writes=0,executions=1,fault=False):
    mnemonic=BRANCHES[op]
    directory=root/'instructions'/mnemonic.lower()
    directory.mkdir(parents=True,exist_ok=True)
    name=mnemonic.lower()+'.'+label
    (directory/(label+'.mms')).write_text('% '+name+'\n'+source,encoding='utf-8',newline='\n')
    case={'schema':1,'id':name,
          'target':{'mnemonic':mnemonic,'opcode':f'0x{op:02x}','pc':f'0x{pc:x}','word':f'0x{word:08x}','executions':executions},
          'tags':['branch',label],'initial':{'entry':'0x0',
             'registers':{f'${i}':f'0x{v:016x}' for i,v in initial.items()},
             'special':{'rG':'0xff','rA':'0xc0c0','rH':'0x1122334455667788','rR':'0x8877665544332211'},'memory':{}},
          'expect':{'stop':'fault' if fault else 'halt','reason':1 if fault else 0,'pc':f'0x{final_pc:016x}',
             'registers':{f'${i}':f'0x{v:016x}' for i,v in expected.items()},'special':{},'memory':{},
             'unchanged_other_state':True,'subject_effects':{'register_writes':writes,'memory_writes':0}},
          'coverage':[label],'profiles':['no_stall','backpressure'],'limits':{'cycles':2000,'wall_seconds':60}}
    (directory/(label+'.case.json')).write_text(json.dumps(case,indent=2)+'\n',encoding='utf-8')
    return name


def write_branch(root,op,label,value=1,*,x=1,delta=None):
    jump=op>=0xf0
    backward=bool(op&1)
    pc=32 if backward else 0
    if delta is None:delta=-4 if backward else 2
    bits=24 if jump else 16
    raw=delta&((1<<bits)-1)
    word=(op<<24)|raw|(0 if jump else x<<16)
    mnemonic=BRANCHES[op][:-1] if backward else BRANCHES[op]
    taken,next_pc=branch_decision(op,value,pc,delta)
    target=(pc+4*delta)&MASK
    fault=taken and not 0<=target<4096
    flag=4
    initial={x:value,flag:7}
    if not jump:
        # Distinguish $X from the bytes encoding the relative displacement.
        for r in (raw>>8,raw&255):
            if r not in (x,flag):initial[r]=0x5555555555555555
    distance=4*delta
    operand='@'+('+' if distance>=0 else '-')+str(abs(distance))
    instruction=mnemonic+' '+(operand if jump else f'${x},'+operand)
    source='        LOC 0\n'
    if backward:source+='Main    JMP Start\n        LOC 32\n'
    source+=('Start   ' if backward else 'Main    ')+instruction+'\n'
    source+=f'        ADDU ${flag},${flag},1\n        TRAP 0,Halt,0\n'
    flag_runs=not taken or target==pc+4
    writes=int(flag_runs)
    expected={flag:8} if flag_runs else {}
    final_pc=next_pc if fault or (taken and not flag_runs) else pc+8
    if taken and not fault and target!=pc+4 and target!=pc+8:
        source+=f'        LOC {target}\n        TRAP 0,Halt,0\n'
    return save(root,op,label,source,pc,word,initial,expected,final_pc,writes=writes,fault=fault)


def write_loop(root,op):
    # Counter and sum are initialized by the program, not by fixture magic.
    if op in (0x4b,0x5b):
        mnemonic='BNZ' if op==0x4b else 'PBNZ'
        source=('        LOC 0\nMain    SETL $1,3\n        SETL $2,0\n'
                'Loop    ADDU $2,$2,1\n        SUBU $1,$1,1\n'
                f'        {mnemonic} $1,Loop\n        TRAP 0,Halt,0\n')
        return save(root,op,'counted_loop',source,16,(op<<24)|0x01fffe,
                    {1:MASK,2:MASK},{1:0,2:3},20,writes=8,executions=3)
    source=('        LOC 0\nMain    SETL $1,3\n        SETL $2,0\n'
            'Loop    ADDU $2,$2,1\n        SUBU $1,$1,1\n'
            '        BZ $1,Done\n        JMP Loop\nDone    TRAP 0,Halt,0\n')
    return save(root,0xf1,'counted_loop',source,20,0xf1fffffd,
                {1:MASK,2:MASK},{1:0,2:3},24,writes=8,executions=2)


def generate(root):
    names=[]
    truth=[MASK,0,1,1,0,1,0,0]
    falsehood=[0,1,0,0,MASK,0,1,1]
    for op in sorted(BRANCHES):
        value=1 if op>=0xf0 else truth[(op>>1)&7]
        def emit(label,*args,**kwargs):
            names.append(write_branch(root,op,label,*args,**kwargs))
        emit('basic',value)
        emit('destination_register0',value,x=0)
        emit('destination_register255',value,x=255)
        emit('enables_preserved',value)
        bits=24 if op>=0xf0 else 16
        distances=(-(1<<bits),-(1<<(bits-1)),-(1<<(bits-1))-1,-1) if op&1 else ((1<<bits)-1,1<<(bits-1),(1<<(bits-1))-1,2)
        for label,delta in zip(('offset_extreme','offset_sign_boundary','offset_before_sign_boundary','offset_near'),distances):
            emit(label,value,delta=delta)
        if not op&1:
            for label,delta in [('next_line',8),('cache_conflict',64),('next_instruction',1)]:emit(label,value,delta=delta)
        if op<0xf0:
            no=falsehood[(op>>1)&7]
            emit('not_taken',no)
            if not op&1:emit('zero_not_taken',no,delta=0)
            for i,test in enumerate((0,1,2,MASK,MASK-1,1<<63,(1<<63)-1)):
                emit(f'condition_{i}',test)
        if op in (0x4b,0x5b,0xf1):names.append(write_loop(root,op))
    return names
