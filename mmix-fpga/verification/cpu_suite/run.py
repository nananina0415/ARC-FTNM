"""Assemble .mms programs, execute the real CPU RTL, assert sidecar expectations."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import html
import json
import os
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET

from .assembler import assemble
from .catalog import RAM_BYTES, SPECIAL, integer, ram_index
from .rtl import bench
from .validate import validate

ROOT = Path(__file__).resolve().parents[2]


def hex_file(path, values, width):
    path.write_text(''.join(f'{v:0{width}x}\n' for v in values),encoding='ascii')


def prepare(path, output, mmixal, profiles):
    case=json.loads(path.read_text(encoding='utf-8'))
    validate(case)
    source=path.with_name(path.name.removesuffix('.case.json')+'.mms')
    directory=output/'cases'/case['id'];directory.mkdir(parents=True,exist_ok=True)
    image,provenance=assemble(mmixal,source,directory)
    if image.entry!=integer(case['initial']['entry']) or image.entry!=0:
        raise ValueError('CPU currently resets at address zero; unsupported MMO entry')
    mem=[0xa5]*RAM_BYTES;regs=[0]*256;spec=[0]*32
    spec[SPECIAL.index('rG')]=image.global_threshold
    for addr,byte in image.memory.items(): mem[ram_index(addr)]=byte
    for index,value in image.registers.items(): regs[index]=value
    for key,value in case['initial']['registers'].items():regs[int(key[1:])]=integer(value)
    for key,value in case['initial']['special'].items():spec[SPECIAL.index(key)]=integer(value)
    for key,value in case['initial']['memory'].items():
        addr=integer(key);byte=int(value,16)
        if addr in image.memory and image.memory[addr]!=byte:raise ValueError(f'Image/fixture memory conflict at {addr:#x}')
        mem[ram_index(addr)]=byte
    if spec[21]&~0xc0c0: raise ValueError('Unimplemented rA bits requested')
    pc=integer(case['target']['pc'])
    if int.from_bytes(bytes(mem[ram_index(pc+i)] for i in range(4)),'big')!=integer(case['target']['word']):
        raise ValueError('MMIXAL encoding does not match target word')
    if integer(case['target']['word'])>>24!=integer(case['target']['opcode']):raise ValueError('Target opcode mismatch')
    expected=(regs.copy(),spec.copy(),mem.copy())
    for key,value in case['expect']['registers'].items():expected[0][int(key[1:])]=integer(value)
    for key,value in case['expect']['special'].items():expected[1][SPECIAL.index(key)]=integer(value)
    for key,value in case['expect']['memory'].items():expected[2][ram_index(integer(key))]=int(value,16)
    runs=[]
    for profile in profiles:
        if profile not in case['profiles']:continue
        dest=directory/profile;dest.mkdir(exist_ok=True)
        hex_file(dest/'image.hex',mem,2);hex_file(dest/'registers.hex',regs,16);hex_file(dest/'special.hex',spec,16)
        hex_file(dest/'config.hex',[0 if profile=='no_stall' else 1,case['limits']['cycles'],pc,0],16)
        actual=dest/'actual.txt'
        if actual.exists():actual.unlink()
        runs.append({'id':case['id'],'profile':profile,'directory':dest,'case':case,'expected':expected,'provenance':provenance})
    return runs


def compare(run):
    path=run['directory']/'actual.txt'
    if not path.exists():raise RuntimeError('Simulator did not produce final state')
    lines=path.read_text().splitlines()
    done,fault,reason,cycles,attempts,writes,stores,reads,acks=map(int,lines[0].split())
    if len(lines)!=1+256+32+RAM_BYTES:raise ValueError('Incomplete state dump')
    values=[int(x,16) for x in lines[1:]]
    errors=[];case=run['case'];expect=case['expect']
    if (done,fault,reason)!=(int(expect['stop']=='halt'),int(expect['stop']=='fault'),expect['reason']):
        errors.append(f'stop done/fault/reason={done}/{fault}/{reason}')
    if attempts!=case['target']['executions']:errors.append(f'target executions={attempts}')
    effects=expect['subject_effects']
    if writes!=effects['register_writes']:errors.append(f'register writes={writes}, expected {effects["register_writes"]}')
    if stores!=effects['memory_writes'] or acks!=stores:errors.append(f'stores/acks={stores}/{acks}')
    offset=0
    for label,expected in zip(('register','special','memory'),run['expected']):
        for i,(a,b) in enumerate(zip(values[offset:offset+len(expected)],expected)):
            if a!=b:errors.append(f'{label}[{i}]: actual=0x{a:x}, expected=0x{b:x}')
        offset+=len(expected)
    if errors:raise AssertionError('; '.join(errors[:12]))
    return {'cycles':cycles,'axi_reads':reads,'stores':stores}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--all',action='store_true',help='Require every ISA entry and required coverage in both profiles')
    parser.add_argument('--instruction',action='append',default=[])
    parser.add_argument('--case',action='append',default=[])
    parser.add_argument('--profile',choices=['both','no_stall','backpressure'],default='both')
    parser.add_argument('--jobs',type=int,default=4)
    parser.add_argument('--batch-size',type=int,default=64)
    parser.add_argument('--mmixal',type=Path,default=ROOT/'.tools/mmix/mmixal.exe')
    parser.add_argument('--iverilog',type=Path,default=ROOT/'.tools/iverilog/app/bin/iverilog.exe')
    parser.add_argument('--output',type=Path,default=ROOT/'build/test/cpu')
    args=parser.parse_args()
    if args.all and (args.instruction or args.case or args.profile!='both'):parser.error('--all cannot filter coverage')
    if args.jobs<1 or args.batch_size<1:parser.error('jobs and batch size must be positive')
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=True)
    profiles=['no_stall','backpressure'] if args.profile=='both' else [args.profile]
    cases=sorted((ROOT/'test/cpu/instructions').rglob('*.case.json'))
    if args.instruction:cases=[p for p in cases if p.parent.name.upper() in [x.upper() for x in args.instruction]]
    if args.case:cases=[p for p in cases if json.loads(p.read_text())['id'] in args.case]
    if not cases:parser.error('No cases selected')
    identifiers=[json.loads(p.read_text(encoding='utf-8'))['id'] for p in cases]
    if len(identifiers)!=len(set(identifiers)):parser.error('Duplicate case ids would overwrite artifacts')
    if not args.mmixal.exists() or not args.iverilog.exists():parser.error('MMIXAL/Icarus missing; see test/cpu/README.md setup')
    results=[];runs=[];start=time.monotonic()
    print(f'Assembling {len(cases)} programs',flush=True)
    with ThreadPoolExecutor(max_workers=args.jobs) as executor:
        futures={executor.submit(prepare,p,output,args.mmixal,profiles):p for p in cases}
        for future in as_completed(futures):
            try:runs.extend(future.result())
            except Exception as exc:results.append({'id':str(futures[future].relative_to(ROOT)),'status':'ASSEMBLY_OR_IMAGE_ERROR','error':str(exc)})
    runs.sort(key=lambda r:(r['id'],r['profile']))
    if not runs and not results:parser.error('Selected cases do not use the requested profiles')
    rtl=ROOT/'src/cpu';text=bench(rtl);tb=output/'cpu_suite_tb.sv';tb.write_text(text,encoding='utf-8')
    sources=[(rtl/name).resolve() for name in (rtl/'sources.f').read_text().splitlines()]
    fingerprint=hashlib.sha256(text.encode()+b''.join(p.read_bytes() for p in sources)).hexdigest()
    executable=output/'cpu_suite.vvp';stamp=output/'rtl.sha256'
    if not executable.exists() or not stamp.exists() or stamp.read_text()!=fingerprint:
        print('Compiling common CPU testbench',flush=True)
        compiled=subprocess.run([str(args.iverilog),'-g2012','-s','tb','-o',str(executable),*[str(p) for p in sources],str(tb)],capture_output=True,text=True,timeout=180)
        (output/'compile.log').write_text(compiled.stdout+compiled.stderr)
        if compiled.returncode:raise RuntimeError('RTL compilation failed: '+compiled.stderr[-4000:])
        stamp.write_text(fingerprint)
    runtime=args.iverilog.with_name('vvp.exe' if os.name=='nt' else 'vvp')
    batches=[runs[i:i+args.batch_size] for i in range(0,len(runs),args.batch_size)]
    def simulate(index,batch):
        queue=output/f'queue-{index}.txt'
        queue.write_text(str(len(batch))+'\n'+'\n'.join(r['directory'].as_posix() for r in batch)+'\n')
        error=None
        try:
            proc=subprocess.run([str(runtime),str(executable),'+queue='+queue.as_posix()],capture_output=True,text=True,timeout=max(r['case']['limits']['wall_seconds'] for r in batch)*len(batch))
            (output/f'batch-{index}.log').write_text(proc.stdout+proc.stderr)
            if proc.returncode:error='Simulator failure: '+(proc.stdout+proc.stderr)[-1800:]
        except subprocess.TimeoutExpired:error='Simulator wall timeout'
        found=[]
        for run in batch:
            item={k:run[k] for k in ('id','profile','provenance')};item['artifacts']=str(run['directory'].relative_to(output))
            item['opcode']=run['case']['target']['opcode'];item['coverage']=run['case']['coverage']
            try:
                if error:raise RuntimeError(error)
                item.update(compare(run));item['status']='PASS'
            except Exception as exc:item['status']='FAIL';item['error']=str(exc)
            found.append(item)
        return found
    print(f'Executing {len(runs)} runs in {len(batches)} batches ({args.jobs} workers)',flush=True)
    with ThreadPoolExecutor(max_workers=args.jobs) as executor:
        futures=[executor.submit(simulate,i,b) for i,b in enumerate(batches)]
        for count,future in enumerate(as_completed(futures),1):
            rows=future.result();results.extend(rows)
            print(f'Batches {count}/{len(batches)}; completed {len(results)}; failures {sum(r["status"]!="PASS" for r in results)}',flush=True)
    catalog=json.loads((ROOT/'test/cpu/isa.json').read_text())['instructions'];missing=[]
    for instruction in catalog:
        for profile in profiles:
            covered={tag for r in results if r['status']=='PASS' and r.get('opcode')==instruction['opcode'] and r['profile']==profile for tag in r['coverage']}
            absent=set(instruction['required_coverage'])-covered
            if absent:missing.append({'mnemonic':instruction['mnemonic'],'profile':profile,'missing':sorted(absent)})
    failed=sum(r['status']!='PASS' for r in results)
    report={'schema':1,'programs':len(cases),'runs':len(runs),'passed':len(results)-failed,'failed':failed,'rtl_sha256':fingerprint,
            'elapsed_seconds':round(time.monotonic()-start,2),'full_coverage_required':args.all,'missing_coverage':missing,'results':results}
    (output/'results.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    coverage_failed=bool(args.all and missing)
    suite=ET.Element('testsuite',name='MMIX CPU',tests=str(len(results)+int(coverage_failed)),failures=str(failed+int(coverage_failed)))
    for r in results:
        node=ET.SubElement(suite,'testcase',name=r['id'],classname=r.get('profile','assembly'))
        if r['status']!='PASS':ET.SubElement(node,'failure',message=r['status']).text=r['error']
    if args.all and missing:ET.SubElement(ET.SubElement(suite,'testcase',name='required_coverage'),'failure').text=json.dumps(missing)
    ET.ElementTree(suite).write(output/'junit.xml',encoding='utf-8',xml_declaration=True)
    rows=''.join('<tr>'+''.join('<td>'+html.escape(str(r.get(k,'')))+'</td>' for k in ('id','profile','status','cycles','error'))+'</tr>' for r in sorted(results,key=lambda r:(r['id'],r.get('profile',''))))
    (output/'report.html').write_text(f'<!doctype html><meta charset="utf-8"><title>MMIX CPU tests</title><style>body{{font:14px system-ui}}td,th{{padding:5px;border-bottom:1px solid #ddd}}table{{border-collapse:collapse}}</style><h1>MMIX CPU</h1><p>{len(cases)} programs / {len(runs)} runs / {failed} failed</p><p>Missing required coverage: {len(missing)}</p><table><tr><th>Case<th>Profile<th>Status<th>Cycles<th>Error</tr>{rows}</table>',encoding='utf-8')
    coverage_note=f'missing coverage groups={len(missing)}' if args.all else 'partial selection; full-suite coverage not required'
    print(f'{report["passed"]} PASS, {failed} FAIL; {coverage_note}; {report["elapsed_seconds"]}s',flush=True)
    raise SystemExit(1 if failed or (args.all and missing) else 0)


if __name__=='__main__':main()
