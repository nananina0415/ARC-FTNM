"""Inventory linked MMIX code and compare opcode support without executing it."""
import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import json
from pathlib import Path
import re
import struct

from cpu_suite.catalog import SPECIAL

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def elf_code(path):
    blob = path.read_bytes()
    if blob[:6] != b'\x7fELF\x02\x02':
        raise ValueError('Requires big-endian ELF64; raw data must not be disassembled as code')
    header = struct.unpack_from('>HHIQQQIHHHHHH', blob, 16)
    if header[1] != 80:
        raise ValueError('ELF machine is not EM_MMIX')
    shoff, entsize, count, names_index = header[5], header[10], header[11], header[12]
    if entsize != 64 or not count or names_index >= count:
        raise ValueError('Unsupported ELF section table')
    sections = []
    for index in range(count):
        values = struct.unpack_from('>IIQQQQIIQQ', blob, shoff+index*entsize)
        row = dict(zip(('name_offset','type','flags','address','offset','size','link','info','align','entry_size'),values))
        row['index'] = index
        if row['type'] != 8 and row['offset']+row['size'] > len(blob):
            raise ValueError('Truncated ELF section')
        sections.append(row)
    def cstring(data, offset):
        return data[offset:data.index(b'\0',offset)].decode('utf-8',errors='replace')
    names = sections[names_index]
    strings = blob[names['offset']:names['offset']+names['size']]
    for row in sections:
        row['name'] = cstring(strings,row['name_offset'])
    symbols = []
    for section in sections:
        if section['type'] != 2:
            continue
        strings_section = sections[section['link']]
        strings = blob[strings_section['offset']:strings_section['offset']+strings_section['size']]
        if section['entry_size'] != 24:
            raise ValueError('Unsupported symbol table')
        for offset in range(section['offset'],section['offset']+section['size'],24):
            name,info,other,shndx,address,size = struct.unpack_from('>IBBHQQ',blob,offset)
            if name and shndx and shndx < len(sections):
                symbols.append({'name':cstring(strings,name),'type':info&15,
                                'section':shndx,'address':address,'size':size})
    words, code_sections, objects, registers = [], [], [], []
    for section in sections:
        if section['name'].startswith('.MMIX.reg_contents') and section['size']:
            if section['size']%8 or section['address']%8:
                raise ValueError('Malformed MMIX register initialization section')
            for offset in range(0,section['size'],8):
                reg=(section['address']+offset)//8
                if reg>=256:raise ValueError('Invalid MMIX initial register')
                value=int.from_bytes(blob[section['offset']+offset:section['offset']+offset+8],'big')
                registers.append({'register':reg,'value':f'0x{value:016x}'})
    for section in sections:
        if section['type'] != 1 or not section['flags']&4:
            continue
        data = blob[section['offset']:section['offset']+section['size']]
        excluded = bytearray(len(data))
        local = [s for s in symbols if s['section']==section['index']]
        for symbol in local:
            if symbol['type']==1 and symbol['size']:
                start = symbol['address']-section['address']
                end = start+symbol['size']
                if start<0 or end>len(data):
                    raise ValueError('Object symbol exceeds executable section')
                excluded[start:end] = b'\x01'*(end-start)
                objects.append(symbol)
        functions = sorted([s for s in local if s['type']==2 and s['size']],key=lambda s:s['address'])
        code_sections.append({**section,'sha256':hashlib.sha256(data).hexdigest(),
                              'object_bytes_excluded':sum(excluded),'trailing_bytes':len(data)%4})
        for offset in range(0,len(data)-3,4):
            if any(excluded[offset:offset+4]):
                continue
            address = section['address']+offset
            if address&3:
                raise ValueError('Unaligned MMIX code word')
            word = int.from_bytes(data[offset:offset+4],'big')
            function = next((s['name'] for s in functions if s['address']<=address<s['address']+s['size']),None)
            words.append({'address':address,'word':word,'opcode':word>>24,
                          'x':(word>>16)&255,'y':(word>>8)&255,'z':word&255,
                          'section':section['name'],'function':function})
    if not words:
        raise ValueError('ELF has no executable MMIX words')
    return words,code_sections,objects,header[3],registers


def checklist_rows(path):
    rows = {}
    for number,line in enumerate(path.read_text(encoding='utf-8').splitlines(),1):
        if not line.startswith('|'):
            continue
        cells = [c.strip() for c in line.strip('|').split('|')]
        for index,cell in enumerate(cells):
            match = re.fullmatch(r'(?:~~)?#([0-9A-Fa-f]{2})(?:~~)?',cell)
            if match:
                opcode = int(match[1],16)
                excluded = '~~' in cell
                checked = lambda value: bool(re.fullmatch(r'\[[xX]\]',value))
                rows[opcode] = {'line':number,'mnemonic':cells[index+1].replace('~~',''),
                                'excluded':excluded,'cycle':not excluded and index>=2 and checked(cells[index-2]),
                                'unit':not excluded and index>=2 and checked(cells[index-1])}
                break
    if len(rows)!=256:
        raise ValueError(f'Expected a 256-opcode checklist, got {len(rows)}')
    return rows


def render(report):
    metadata = report['build']
    missing = [r for r in report['opcodes'] if r['unsupported_words']]
    lines = ['# Doom MMIX 명령어 요구사항과 CPU 구현 현황','',
             '이 문서는 실제로 링크한 FPGA용 Doom 바이너리의 **정적 명령어 목록**이다. '
             '게임을 실행해 수집한 명령어 실행 빈도나 전체 CPU 적합성 검증 결과가 아니다.','',
             f"- 원본: [{metadata['repository']} @ `{metadata['commit']}`]({metadata['source_url']})",
             f"- 컴파일러: `{metadata['compiler']}` / `{metadata['target']}`",
             f"- Binutils: `{metadata['binutils']}`",
             f"- 빌드 옵션: `{' '.join(metadata['cflags'])}`",
             f"- 코드 단어: **{report['instruction_words']:,}개**, opcode **{len(report['opcodes'])}종**",
             f"- 필요한 동작을 전부 지원하지 못하는 opcode: **{len(missing)}종** (TRAP 부분 지원 포함)",
             f"- 코드 섹션의 부동소수점 명령: **{report['floating_point_words']:,}개**",
             f"- 체크리스트 유닛 체크: **{sum(r['checklist']['unit'] for r in report['opcodes'])}/{len(report['opcodes'])}종**",
             f"- 체크리스트 사이클 체크: **{sum(r['checklist']['cycle'] for r in report['opcodes'])}/{len(report['opcodes'])}종**",'',
             '사용된 opcode의 행은 체크리스트에 모두 존재한다. 부족한 항목은 행이 없는 것이 아니라 '
             '유닛 체크가 없거나 해당 피연산자 동작이 구현되지 않은 것이다. 분석기는 현재 체크리스트를 읽으며 체크 상태를 변경하지 않는다.','',
             '## 빌드와 분석 범위','']
    lines += [f'- {item}' for item in metadata['adaptations']]
    lines += ['', '실제 산출물과 재현 명령은 아래에 기록했다. 소스의 최적화 수준 지정이 없어 '
              '-O0을 명시했고, 컴파일러 기본 표준 차이와 C99 이후 문법을 위해 -std=gnu11을 사용했다. '
              'MMIX_SIM을 정의하지 않은 FPGA 빌드이며, 이식 코드와 커스텀 libc·드라이버·crt0를 함께 링크했다.', '',
              '원본을 무수정 빌드한 결과로 오해하면 안 된다. 위의 경로·누락 의존성·두 API 호출을 '
              '보정한 빌드다. 컴파일러 버전이나 최적화 옵션을 바꾸면 명령어 종류와 개수도 달라질 수 있다. '
              '별도 프로세스로 실행할 nnix-os 전체 바이너리와 WAD 데이터는 이 명령어 집계에 포함하지 않았다.', '',
              'ELF의 실행 가능한 PROGBITS 섹션만 읽고, 해당 섹션 안에서도 크기가 명시된 '
              'STT_OBJECT 심볼 범위는 제외했다. .rodata/.data/.bss와 MMIX 초기 레지스터 섹션은 '
              '명령어로 세지 않았다. 코드에 남은 정렬 명령과 도달하지 않는 함수는 정적 목록에 '
              '포함될 수 있다. GNU objdump의 SET 같은 별칭 대신 실제 첫 바이트 opcode를 기준으로 '
              '즉치형·앞/뒤 분기를 구분했다.','',
              '| 코드 섹션 | 시작 주소 | 크기 | 제외한 객체 데이터 |',
              '|---|---:|---:|---:|']
    for section in report['code_sections']:
        lines.append(f"| {section['name']} | `0x{section['address']:x}` | {section['size']:,} B | {section['object_bytes_excluded']:,} B |")
    lines += ['', '## 실제 사용 opcode 전체','',
              '유닛/사이클 칸은 현재 체크리스트의 표시를 그대로 옮겼다. 사이클 미체크를 유닛 '
              '미구현과 동일하게 취급하지 않는다. 구현 판정은 별도의 현재 ISA 목록과 실제 TRAP '
              '피연산자를 함께 대조한다. 체크가 있어도 GCC ABI와 OS 기능까지 갖췄다는 뜻은 아니다.','',
              '| opcode | 명령어 | 정적 개수 | 유닛 | 사이클 | 현재 CPU | 첫 주소 |',
              '|---|---|---:|:---:|:---:|---|---|']
    for row in report['opcodes']:
        check = row['checklist']
        lines.append(f"| `#{row['opcode']:02X}` | `{row['mnemonic']}` | {row['count']:,} | {'✓' if check['unit'] else '—'} | {'✓' if check['cycle'] else '—'} | {row['status']} | `0x{row['first_address']:x}` |")
    lines += ['', '## 부족한 명령어','', ', '.join(f"`{r['mnemonic']}` (`#{r['opcode']:02X}`)" for r in missing) or '없음.', '',
              '체크리스트의 유닛 미체크 항목은 '+(', '.join(f"`{r['mnemonic']}`" for r in report['opcodes'] if not r['checklist']['unit']) or '없음')+'이다.', '',
              '구현 순서는 다음처럼 묶는 것이 좋다. 명령어 수를 줄이기 위해 프로그램에 맞춘 '
              '특례를 넣지 않고, 기존 주소 계산·메모리·특수 레지스터·제어 경로를 확장한다.','',
              '| 묶음 | 이 빌드에 등장하는 미구현 명령 | 필요한 작업 |',
              '|---|---|---|']
    for title,ops,work in [
        ('기본 실행',{0xfd},'SWYM의 무부작용 PC 진행'),
        ('주소·상수 저장',{0xf4,0xf5,0xb4,0xb5},'PC 상대 주소 writeback, 상수 store'),
        ('작은 정수 메모리',set(range(0x80,0x8c))|set(range(0xa0,0xac)),'접근 폭·확장·byte strobe·캐시 갱신'),
        ('특수 레지스터',{0xfe,0xf6,0xf7},'실제 사용된 rJ 읽기/쓰기와 rR 읽기 및 대상 레지스터 의미'),
        ('호출·복귀',{0xf2,0xf3,0xbe,0xbf,0xf8},'레지스터 스택과 rJ, 상대/간접 목표 및 복귀 제어'),
        ('운영체제 진입',{0},'HALT 특례와 분리된 트랩 진입·nnix-os 핸들러·복귀')]:
        members=[r['mnemonic'] for r in missing if r['opcode'] in ops]
        if members:lines.append(f"| {title} | {', '.join(members)} | {work} |")
    lines += ['',
              '## opcode 외에 필요한 실행 기반','',
              '- **레지스터 스택과 호출 ABI:** PUSHJ/PUSHGO/POP은 PC 이동만으로 구현할 수 없다. '
              '현재의 평면 256개 레지스터 파일에 rL/rG, 윈도 이동, rJ, spill/fill 및 메모리 스택 처리가 필요하다.',
              '- **일반 TRAP와 OS 복귀:** CPU는 현재 정확한 TRAP 0,0,0만 정지로 처리한다. '
              '앱의 TRAP 1,Y,Z 시스템콜에는 트랩 진입·상태 보존·핸들러 실행·복귀와 nnix-os가 필요하다.',
              '- **작은 정수 메모리 접근:** 현재 ISA 목록의 지원 여부와 실행 검증은 별개다. '
              '폭별 부호/0 확장·부분 쓰기·캐시 갱신을 검증해야 하며, signed 부분 store의 V 예외는 '
              '이 빌드에서 쓰는 unsigned store와 별도의 구현 항목이다.',
              '- **주소 공간과 I/O:** 현재 CPU의 RAM 경로는 30비트 MIG AXI다. '
              '드라이버의 MMIO 주소는 2^48 기반이므로 주소 디코딩/별도 주변장치 경로가 필요하다. '
              '프레임버퍼·타이머·USB·오디오·SD/파일시스템도 opcode 구현만으로 제공되지 않는다.',
              '- **시작 환경:** crt0는 BIOS가 $253/$254/$255에 넘기는 RAM 크기와 실행/링크 정보를 '
              '사용한다. BSS·힙·소프트웨어 스택과 초기 전역 레지스터 값도 준비해야 한다.',
              '- **시간 콜백:** 실제 main,c의 cb_gettime은 TODO 상태로 sec/usec에 0을 반환한다. '
              'README의 타이머 syscall 설명과 다르므로 게임 시간 공급도 연결해야 한다.','']
    registers=report['initial_global_registers']
    constraints=report.get('isa_constraints',{})
    if constraints:
        first=next(iter(constraints.values()))
        lines += ['현재 GET/PUT 지원 레지스터는 '+', '.join('`'+n+'`' for n in first['special_registers'])+
                  '이다. rA는 `'+first['ra_mask']+'` 마스크의 비트만 지원한다. '
                  '시스템·스택 레지스터를 저장 셀 접근으로 대신하지 않으며, 미지원 대상과 rA 값은 '
                  'CPU에서 거부한다. 위 표의 GET/PUT 지원 판정은 바이너리에 나타난 rJ/rR 대상에 '
                  '대해 이 범위를 확인한 결과다.','']
    if registers:
        lines += [f"이번 ELF에는 전역 레지스터 초기값 **{len(registers)}개 "
                  f"(${min(r['register'] for r in registers)}~${max(r['register'] for r in registers)})**가 "
                  '`.MMIX.reg_contents.linker_allocated`에 들어 있다. 이 비적재 섹션의 초기화 '
                  '메타데이터는 RAW 파일에 포함되지 않는다. 로더/시작 환경이 이 초기값과 rG를 '
                  '준비해야 하며, $253/$254의 BIOS 전달값·스택 포인터 규약과도 조정해야 한다. '
                  '대안으로 -mno-base-addresses 빌드를 검토할 수 있지만 그 변형은 별도 재빌드·재분석 '
                  '대상이며 이 문서의 통계와 섞지 않았다. 초기값 전체는 JSON에 보존했다.','']
    lines += [
              '## TRAP 및 특수 레지스터 피연산자','',
              '| 명령 | 피연산자 | 정적 개수 |', '|---|---|---:|']
    for name, values in report['operands'].items():
        for operand,count in sorted(values.items()):
            lines.append(f'| {name} | `{operand}` | {count} |')
    lines += ['', '## 재현 정보','', f"- ELF 진입점: `0x{report['entry']:x}`"]
    if metadata.get('compiler_archive_sha256'):
        lines.append('- 컴파일러 ZIP SHA-256: `'+metadata['compiler_archive_sha256']+'`')
    for name,item in report['artifacts'].items():
        lines.append(f"- {name}: `{item['path']}` / SHA-256 `{item['sha256']}`")
    lines += ['', '재현 스크립트: [빌드](../verification/build_doom_audit.py), '
              '[ELF/RAW 분석과 체크리스트 대조](../verification/doom_audit.py). '
              '기계가 읽을 수 있는 결과는 [JSON](doom-instructions.json), '
              '[CSV](doom-instructions.csv)에 있다.']
    lines += ['', '빌드 명령:', '', '```text', *metadata['commands'], '```', '',
              '분석 명령:', '', '```powershell', metadata['analysis_command'], '```', '',
              '연결된 원본 파일:', '']
    lines += [f'- [{title}]({url})' for title,url in metadata['references'].items()]
    lines += ['', '본 문서 생성 과정에서 Doom 실행, CPU 시뮬레이션, 브라우저 조작은 수행하지 않았다.', '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--elf',type=Path,required=True)
    parser.add_argument('--raw',type=Path,required=True)
    parser.add_argument('--manifest',type=Path,required=True)
    parser.add_argument('--opcodes',type=Path,default=ROOT/'.tools/mmix/mmix-sim.w')
    parser.add_argument('--checklist',type=Path,default=ROOT/'op체크리스트.md')
    parser.add_argument('--isa',type=Path,default=ROOT/'test/cpu/isa.json')
    parser.add_argument('--output',type=Path,default=ROOT/'docs/doom-instructions.md')
    args = parser.parse_args()
    names = re.findall(r'^\{"([A-Z0-9]+)",0x[0-9a-f]+,',args.opcodes.read_text(encoding='utf-8'),re.M)
    if len(names)!=256 or names[0]!='TRAP' or names[-1]!='TRIP':
        raise ValueError('Expected the official 256-entry MMIXware opcode table')
    words,sections,objects,entry,registers = elf_code(args.elf)
    raw = args.raw.read_bytes()
    for section in sections:
        part = raw[section['address']:section['address']+section['size']]
        if hashlib.sha256(part).hexdigest()!=section['sha256']:
            raise ValueError('ELF executable bytes differ from RAW binary; do not infer RAW code layout')
    checklist = checklist_rows(args.checklist)
    support = {int(r['opcode'],16):r for r in json.loads(args.isa.read_text())['instructions'] if r['supported']}
    def supported(word):
        op=word['opcode']
        if op==0:return word['word']==0
        if op not in support:return False
        if op in (0xfe,0xf6,0xf7):
            index=word['z'] if op==0xfe else word['x']
            if word['y'] or index>=32:return False
            allowed=support[op].get('special_registers',SPECIAL)
            if SPECIAL[index] not in allowed:return False
            if op==0xf7 and index==21 and word['z']&~int(support[op].get('ra_mask','0xffffffffffffffff'),16):return False
        return True
    grouped = defaultdict(list)
    operands = defaultdict(Counter)
    for word in words:
        op = word['opcode'];grouped[op].append(word)
        if op==0:operands['TRAP'][f"{word['x']},{word['y']},{word['z']}"]+=1
        if op==0xfe:
            z = word['z'];operands['GET'][SPECIAL[z] if z<32 else f'invalid({z})']+=1
        if op in (0xf6,0xf7):
            x = word['x'];operands['PUTI' if op&1 else 'PUT'][SPECIAL[x] if x<32 else f'invalid({x})']+=1
    rows=[]
    for opcode,instances in sorted(grouped.items()):
        check=checklist[opcode]
        if check['mnemonic']!=names[opcode]:
            raise ValueError(f'Checklist opcode/name mismatch: {opcode:02x}')
        bad=[w for w in instances if not supported(w)]
        unsupported=len(bad)
        status='지원 경로 있음' if not unsupported else '미구현'
        if opcode==0:status='HALT만 지원 / 일반 TRAP 미구현' if unsupported else 'HALT 특례 지원'
        rows.append({'opcode':opcode,'mnemonic':names[opcode],'count':len(instances),
                     'unsupported_words':unsupported,'status':status,'checklist':check,
                     'first_address':instances[0]['address'],
                     'first_unsupported_address':bad[0]['address'] if bad else None,
                     'examples':[{k:w[k] for k in ('address','word','function')} for w in instances[:3]]})
    artifacts = {name:{'path':str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path),'sha256':sha(path)}
                 for name,path in [('ELF',args.elf.resolve()),('RAW',args.raw.resolve()),
                                   ('checklist',args.checklist.resolve()),('ISA',args.isa.resolve()),
                                   ('opcode_source',args.opcodes.resolve())]}
    report={'schema':1,'build':json.loads(args.manifest.read_text(encoding='utf-8')),
            'entry':entry,'instruction_words':len(words),'code_sections':sections,'excluded_objects':objects,
            'initial_global_registers':registers,
            'isa_constraints':{f'{op:02x}':{k:v for k,v in row.items() if k in ('special_registers','ra_mask')}
                               for op,row in support.items() if 'special_registers' in row},
            'opcodes':rows,'operands':{k:dict(v) for k,v in operands.items()},'artifacts':artifacts,
            'floating_point_words':sum(r['count'] for r in rows if 1<=r['opcode']<=0x17)}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(render(report),encoding='utf-8')
    args.output.with_suffix('.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    with args.output.with_suffix('.csv').open('w',encoding='utf-8-sig',newline='') as out:
        writer=csv.writer(out);writer.writerow(['opcode','mnemonic','static_words','unit_checked','cycle_checked','status'])
        for r in rows:writer.writerow([f'{r["opcode"]:02X}',r['mnemonic'],r['count'],r['checklist']['unit'],r['checklist']['cycle'],r['status']])
    print(json.dumps({'words':len(words),'opcodes':len(rows),'missing':sum(r['unsupported_words']>0 for r in rows),
                      'fp_words':report['floating_point_words'],'report':str(args.output)},ensure_ascii=False))


if __name__=='__main__':main()
