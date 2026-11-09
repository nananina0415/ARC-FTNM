"""Reproduce the pinned Doom FPGA build; never run Doom or the CPU simulator."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]


def command(args,cwd,log,env):
    result=subprocess.run([str(x) for x in args],cwd=cwd,env=env,capture_output=True)
    log.write_bytes(result.stdout+result.stderr)
    if result.returncode:
        raise RuntimeError(f'{log}:\n'+(result.stdout+result.stderr).decode('utf-8',errors='replace')[-9000:])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,default=ROOT/'.tools/doom-audit/ARC-FTNM')
    parser.add_argument('--tools',type=Path,default=ROOT/'.tools/doom-audit/prebuilt/mmix/bin')
    parser.add_argument('--compiler',type=Path)
    parser.add_argument('--compiler-url',default='https://mmix.cs.hm.edu/exe/gcc-4.8.5.zip')
    parser.add_argument('--output',type=Path,default=ROOT/'build/doom-audit')
    parser.add_argument('--cmake',type=Path,default=Path('C:/msys64/ucrt64/bin/cmake.exe'))
    parser.add_argument('--ninja',type=Path,default=Path('C:/msys64/ucrt64/bin/ninja.exe'))
    args=parser.parse_args()
    source=args.source.resolve();out=args.output.resolve();tools=args.tools.resolve()
    out.mkdir(parents=True,exist_ok=True)
    suffix='.exe' if os.name=='nt' else ''
    compiler=args.compiler.resolve() if args.compiler else next(
        (tools/(name+suffix) for name in ('mmix-gcc','mmix-knuth-mmixware-gcc') if (tools/(name+suffix)).exists()),
        tools/('mmix-gcc'+suffix))
    if not compiler.exists():raise FileNotFoundError(compiler)
    # Two committed call sites disagree with their actual function prototypes.
    for relative,before,after in [
        ('apps/doom/src/main,c','doom_get_sound_buffer(AUDIO_BUF_BYTES)','doom_get_sound_buffer()'),
        ('apps/doom/PureDOOM/src/DOOM/d_main.c','I_Error("W_Init: IWAD not found: %s", iwad);',
         'I_Error("W_Init: IWAD not found");')]:
        path=source/relative;text=path.read_text(encoding='utf-8')
        if before in text:path.write_text(text.replace(before,after),encoding='utf-8')
        elif after not in text:raise RuntimeError(f'Unexpected upstream call site: {relative}')
    root_cmake=source/'CMakeLists.txt'
    if root_cmake.exists() and 'arc_doom_instruction_audit' not in root_cmake.read_text():
        raise RuntimeError('Refusing to overwrite an upstream root CMakeLists.txt')
    root_cmake.write_text('''cmake_minimum_required(VERSION 3.15)
project(arc_doom_instruction_audit C ASM)
configure_file(apps/doom/src/main,c ${CMAKE_CURRENT_SOURCE_DIR}/apps/doom/src/main.c COPYONLY)
add_subdirectory(apps/doom)
target_sources(doom PRIVATE nnix-os/libc/args.c)
target_compile_options(doom PRIVATE -O0 -std=gnu11)
target_link_options(doom PRIVATE -Wl,-Map=${CMAKE_BINARY_DIR}/doom.map)
''',encoding='utf-8')
    toolchain=out/'toolchain.cmake'
    toolchain.write_text(f'''set(CMAKE_SYSTEM_NAME Generic)
set(CMAKE_C_COMPILER "{compiler.as_posix()}")
set(CMAKE_ASM_COMPILER "{compiler.as_posix()}")
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
''',encoding='utf-8')
    env=dict(os.environ)
    env['PATH']=str(tools)+os.pathsep+str(args.cmake.parent)+os.pathsep+env['PATH']
    env['PYTHONUTF8']='1'
    configure=[args.cmake,'-S',source,'-B',out/'cmake','-G','Ninja',
               f'-DCMAKE_MAKE_PROGRAM={args.ninja.as_posix()}',f'-DCMAKE_TOOLCHAIN_FILE={toolchain.as_posix()}',
               '-DMMIX_SIM=OFF','-DCMAKE_EXPORT_COMPILE_COMMANDS=ON']
    command(configure,ROOT,out/'configure.log',env)
    command([args.cmake,'--build',out/'cmake','--parallel','2'],ROOT,out/'build.log',env)
    ninja=(out/'cmake/build.ninja').read_text(encoding='utf-8')
    link_line=next(line for line in ninja.splitlines() if line.startswith('build ') and 'C_EXECUTABLE_LINKER__doom_' in line)
    objects=re.findall(r'\S+\.obj',link_line.split(' |',1)[0])
    if not objects:raise RuntimeError('Cannot recover original link order from Ninja')
    elf_command=[compiler,'-nostdlib','-melf','-T',source/'apps/doom/link.ld',
                 '-Wl,--oformat=elf64-mmix',f'-Wl,-Map={out.as_posix()}/doom-elf.map',
                 '-Wl,-e,_start',*objects,'-o',out/'doom.elf']
    command(elf_command,out/'cmake',out/'link-elf.log',env)
    shutil.copyfile(out/'cmake/apps/doom/doom',out/'doom.bin')
    print('Built FPGA Doom:',out/'doom.bin','and',out/'doom.elf')
    (out/'build-commands.json').write_text(json.dumps({'configure':list(map(str,configure)),
          'build':[str(args.cmake),'--build',str(out/'cmake'),'--parallel','2'],
          'elf_link':list(map(str,elf_command))},indent=2)+'\n')
    def capture(argv):
        return subprocess.run(list(map(str,argv)),cwd=ROOT,env=env,capture_output=True,check=True).stdout.decode('utf-8',errors='replace').strip()
    commit=capture(['git','-C',source,'rev-parse','HEAD'])
    source_base=f'https://github.com/nananina0415/ARC-FTNM/blob/{commit}/'
    ld=compiler.with_name(compiler.name.replace('gcc','ld'))
    compiler_version=capture([compiler,'--version']).splitlines()[0]
    binutils_version=capture([ld,'--version']).splitlines()[0]
    manifest={'repository':'nananina0415/ARC-FTNM','commit':commit,
       'source_url':f'https://github.com/nananina0415/ARC-FTNM/tree/{commit}/apps/doom',
       'compiler':compiler_version,
       'target':capture([compiler,'-dumpmachine']),
       'binutils':binutils_version,
       'cflags':['-D__BIG_ENDIAN__','-ffreestanding','-nostdlib','-fomit-frame-pointer','-fno-jump-tables','-O0','-std=gnu11'],
       'adaptations':[
           '커밋에 없는 루트 CMakeLists.txt를 분석용 wrapper로 만들어 Doom 하위 프로젝트를 추가했다.',
           '실제 파일명 main,c를 CMake가 요구하는 main.c로 내용 변경 없이 복사했다.',
           'crt0.s가 참조하는 _args_init/_crt_argc/_crt_argv를 제공하도록 기존 libc/args.c를 링크 목록에 추가했다.',
           '0인자 함수 선언에 맞춰 doom_get_sound_buffer(AUDIO_BUF_BYTES)를 doom_get_sound_buffer()로 변경했다.',
           'I_Error는 단일 문자열 함수이므로 잘못된 두 인자 호출을 I_Error("W_Init: IWAD not found")로 변경했다. IWAD 미존재 오류의 파일명 표시는 생략되며 정상 경로는 바꾸지 않았다.',
           '원본의 기본 무최적화 빌드를 -O0으로 명시하고 C99 이후 문법을 위해 -std=gnu11을 사용했다.',
           '원본 RAW 링커 스크립트로 doom.bin을 만들고 동일 객체/순서/스크립트에 출력 형식만 ELF로 지정한 동반 파일을 만들었다. 분석기는 코드 바이트 일치를 요구한다.',
           f'{compiler_version} / {binutils_version}을 사용했다. 원본 Linux 경로의 컴파일러 버전은 저장소에 고정돼 있지 않다.'],
       'commands':[
           'git clone --filter=blob:none --no-checkout --single-branch --branch main https://github.com/nananina0415/ARC-FTNM.git .tools/doom-audit/ARC-FTNM',
           'git -C .tools/doom-audit/ARC-FTNM sparse-checkout init --no-cone',
           "git -C .tools/doom-audit/ARC-FTNM sparse-checkout set '/apps/doom/*' '!/apps/doom/freedoom-0.13.0/' '/nnix-os/' '/drivers/' '/tools/' '/README.md' '/.gitmodules'",
           f'git -C .tools/doom-audit/ARC-FTNM checkout --detach {commit}',
           'download '+args.compiler_url+' and extract to .tools/doom-audit/prebuilt',
           subprocess.list2cmdline(['python',str(Path(__file__).relative_to(ROOT)),*sys.argv[1:]])],
       'analysis_command':'python verification/doom_audit.py --elf build/doom-audit/doom.elf --raw build/doom-audit/doom.bin --manifest build/doom-audit/manifest.json',
       'references':{'Doom CMake':source_base+'apps/doom/CMakeLists.txt',
                     'RAW linker script':source_base+'apps/doom/link.ld',
                     'startup':source_base+'nnix-os/libc/crt0.s',
                     'FPGA main':source_base+'apps/doom/src/main,c',
                     'MMIO address layout':source_base+'nnix-os/src/device/types.h',
                     'MMIX Windows compiler distribution':'https://mmix.cs.hm.edu/exe/index.html'}}
    patch=capture(['git','-C',source,'-c','core.safecrlf=false','diff','--','apps/doom/src/main,c','apps/doom/PureDOOM/src/DOOM/d_main.c'])
    (out/'source-adaptations.patch').write_text(patch+'\n',encoding='utf-8')
    archive=ROOT/'.tools/doom-audit/gcc-4.8.5.zip'
    if archive.exists() and '/prebuilt/mmix/' in compiler.as_posix():
        manifest['compiler_archive_sha256']=hashlib.sha256(archive.read_bytes()).hexdigest()
    (out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':main()
