"""Validate assertions before producing fixtures; no simulator dependencies."""
import re

from .catalog import ISA, SPECIAL, integer, ram_index


def validate(case):
    def require(condition, message):
        if not condition:raise ValueError(message)

    def number(value, bits):
        require(isinstance(value,str) and re.fullmatch(r'0x[0-9a-f]+',value),f'Invalid {bits}-bit hex integer: {value!r}')
        require(0<=integer(value)<1<<bits,f'Integer outside {bits}-bit range: {value}')

    require(case.get('schema')==1,'Requires schema 1')
    require(isinstance(case.get('id'),str) and re.fullmatch(r'[a-z0-9_.]+',case['id']),'Invalid case id')
    target=case['target'];number(target['opcode'],8);number(target['word'],32);number(target['pc'],64)
    opcode=integer(target['opcode'])
    require(ISA.get(opcode)==target['mnemonic'],'Target is absent from supported ISA or mnemonic mismatches opcode')
    require(integer(target['word'])>>24==opcode,'Target word/opcode mismatch')
    if opcode==0:require(integer(target['word'])==0,'Only exact TRAP 0,Halt,0 is supported')
    require(type(target['executions']) is int and target['executions']>0,'Invalid target execution count')
    for i in range(4):ram_index(integer(target['pc'])+i)
    initial,expect=case['initial'],case['expect']
    number(initial['entry'],64)
    require(expect['unchanged_other_state'] is True,'Requires unchanged_other_state=true')
    require(expect['stop'] in ('halt','fault'),'Unknown stop condition')
    require(type(expect['reason']) is int and 0<=expect['reason']<=7,'Invalid fault reason')
    require((expect['reason']==0)==(expect['stop']=='halt'),'Stop condition/fault reason mismatch')
    for state in (initial,expect):
        for key,value in state['registers'].items():
            require(re.fullmatch(r'\$(0|[1-9][0-9]*)',key) and 0<=int(key[1:])<256,f'Invalid GPR: {key}')
            number(value,64)
        for key,value in state['special'].items():
            require(key in SPECIAL,f'Unknown special register: {key}');number(value,64)
            if key=='rA':require(integer(value)&~0xc0c0==0,'Unimplemented rA bits requested')
        for key,value in state['memory'].items():
            number(key,64);ram_index(integer(key))
            require(isinstance(value,str) and re.fullmatch(r'[0-9a-f]{2}',value),f'Invalid memory byte: {value!r}')
    for name in ('register_writes','memory_writes'):
        value=expect['subject_effects'][name]
        require(type(value) is int and value>=0,f'Invalid {name}')
    for name in ('cycles','wall_seconds'):
        value=case['limits'][name]
        require(type(value) is int and value>0,f'Invalid {name} limit')
    for name in ('profiles','coverage'):
        values=case[name]
        require(isinstance(values,list) and values and all(isinstance(x,str) for x in values),f'Invalid {name}')
        require(len(set(values))==len(values),f'Duplicate {name}')
    require(set(case['profiles'])<=set(('no_stall','backpressure')),'Unknown RAM profile')
