# Doom MMIX 명령어 요구사항과 CPU 구현 현황

이 문서는 실제로 링크한 FPGA용 Doom 바이너리의 **정적 명령어 목록**이다. 게임을 실행해 수집한 명령어 실행 빈도나 전체 CPU 적합성 검증 결과가 아니다.

- 원본: [nananina0415/ARC-FTNM @ `ea4e8130950e10244650282e8ef671e845da89bf`](https://github.com/nananina0415/ARC-FTNM/tree/ea4e8130950e10244650282e8ef671e845da89bf/apps/doom)
- 컴파일러: `mmix-gcc.exe (GCC) 4.8.5` / `mmix`
- Binutils: `GNU ld (GNU Binutils) 2.25.1`
- 빌드 옵션: `-D__BIG_ENDIAN__ -ffreestanding -nostdlib -fomit-frame-pointer -fno-jump-tables -O0 -std=gnu11`
- 코드 단어: **118,949개**, opcode **77종**
- 필요한 동작을 전부 지원하지 못하는 opcode: **6종** (TRAP 부분 지원 포함)
- 코드 섹션의 부동소수점 명령: **0개**
- 체크리스트 유닛 체크: **71/77종**
- 체크리스트 사이클 체크: **11/77종**

사용된 opcode의 행은 체크리스트에 모두 존재한다. 부족한 항목은 행이 없는 것이 아니라 유닛 체크가 없거나 해당 피연산자 동작이 구현되지 않은 것이다. 분석기는 현재 체크리스트를 읽으며 체크 상태를 변경하지 않는다.

## 빌드와 분석 범위

- 커밋에 없는 루트 CMakeLists.txt를 분석용 wrapper로 만들어 Doom 하위 프로젝트를 추가했다.
- 실제 파일명 main,c를 CMake가 요구하는 main.c로 내용 변경 없이 복사했다.
- crt0.s가 참조하는 _args_init/_crt_argc/_crt_argv를 제공하도록 기존 libc/args.c를 링크 목록에 추가했다.
- 0인자 함수 선언에 맞춰 doom_get_sound_buffer(AUDIO_BUF_BYTES)를 doom_get_sound_buffer()로 변경했다.
- I_Error는 단일 문자열 함수이므로 잘못된 두 인자 호출을 I_Error("W_Init: IWAD not found")로 변경했다. IWAD 미존재 오류의 파일명 표시는 생략되며 정상 경로는 바꾸지 않았다.
- 원본의 기본 무최적화 빌드를 -O0으로 명시하고 C99 이후 문법을 위해 -std=gnu11을 사용했다.
- 원본 RAW 링커 스크립트로 doom.bin을 만들고 동일 객체/순서/스크립트에 출력 형식만 ELF로 지정한 동반 파일을 만들었다. 분석기는 코드 바이트 일치를 요구한다.
- Windows MMIX 배포의 GCC 4.8.5 / Binutils 2.25.1을 사용했다. 원본 Linux 경로의 컴파일러 버전은 저장소에 고정돼 있지 않다.

실제 산출물과 재현 명령은 아래에 기록했다. 소스의 최적화 수준 지정이 없어 -O0을 명시했고, 컴파일러 기본 표준 차이와 C99 이후 문법을 위해 -std=gnu11을 사용했다. MMIX_SIM을 정의하지 않은 FPGA 빌드이며, 이식 코드와 커스텀 libc·드라이버·crt0를 함께 링크했다.

원본을 무수정 빌드한 결과로 오해하면 안 된다. 위의 경로·누락 의존성·두 API 호출을 보정한 빌드다. 컴파일러 버전이나 최적화 옵션을 바꾸면 명령어 종류와 개수도 달라질 수 있다. 별도 프로세스로 실행할 nnix-os 전체 바이너리와 WAD 데이터는 이 명령어 집계에 포함하지 않았다.

ELF의 실행 가능한 PROGBITS 섹션만 읽고, 해당 섹션 안에서도 크기가 명시된 STT_OBJECT 심볼 범위는 제외했다. .rodata/.data/.bss와 MMIX 초기 레지스터 섹션은 명령어로 세지 않았다. 코드에 남은 정렬 명령과 도달하지 않는 함수는 정적 목록에 포함될 수 있다. GNU objdump의 SET 같은 별칭 대신 실제 첫 바이트 opcode를 기준으로 즉치형·앞/뒤 분기를 구분했다.

| 코드 섹션 | 시작 주소 | 크기 | 제외한 객체 데이터 |
|---|---:|---:|---:|
| .text | `0x0` | 475,796 B | 0 B |

## 실제 사용 opcode 전체

유닛/사이클 칸은 현재 체크리스트의 표시를 그대로 옮겼다. 사이클 미체크를 유닛 미구현과 동일하게 취급하지 않는다. 구현 판정은 별도의 현재 ISA 목록과 실제 TRAP 피연산자를 함께 대조한다. 체크가 있어도 GCC ABI와 OS 기능까지 갖췄다는 뜻은 아니다.

| opcode | 명령어 | 정적 개수 | 유닛 | 사이클 | 현재 CPU | 첫 주소 |
|---|---|---:|:---:|:---:|---|---|
| `#00` | `TRAP` | 19 | — | — | HALT만 지원 / 일반 TRAP 미구현 | `0xa0` |
| `#1A` | `MULU` | 89 | ✓ | — | 지원 경로 있음 | `0x47ac` |
| `#1B` | `MULUI` | 47 | ✓ | — | 지원 경로 있음 | `0x20bc` |
| `#1E` | `DIVU` | 138 | ✓ | — | 지원 경로 있음 | `0x1340` |
| `#1F` | `DIVUI` | 7 | ✓ | — | 지원 경로 있음 | `0x1c118` |
| `#21` | `ADDI` | 1 | ✓ | — | 지원 경로 있음 | `0x38` |
| `#22` | `ADDU` | 3,756 | ✓ | — | 지원 경로 있음 | `0x7c8` |
| `#23` | `ADDUI` | 16,120 | ✓ | — | 지원 경로 있음 | `0xb4` |
| `#26` | `SUBU` | 542 | ✓ | — | 지원 경로 있음 | `0xad0` |
| `#27` | `SUBUI` | 1,158 | ✓ | — | 지원 경로 있음 | `0xac` |
| `#30` | `CMP` | 1,080 | ✓ | — | 지원 경로 있음 | `0x2c` |
| `#31` | `CMPI` | 3,153 | ✓ | — | 지원 경로 있음 | `0x760` |
| `#32` | `CMPU` | 73 | ✓ | — | 지원 경로 있음 | `0xec38` |
| `#33` | `CMPUI` | 91 | ✓ | — | 지원 경로 있음 | `0x6dd4` |
| `#36` | `NEGU` | 696 | ✓ | — | 지원 경로 있음 | `0x1278` |
| `#37` | `NEGUI` | 187 | ✓ | — | 지원 경로 있음 | `0xb28` |
| `#3A` | `SLU` | 7 | ✓ | — | 지원 경로 있음 | `0x3b108` |
| `#3B` | `SLUI` | 11,428 | ✓ | — | 지원 경로 있음 | `0xc4` |
| `#3C` | `SR` | 4 | ✓ | — | 지원 경로 있음 | `0x53c5c` |
| `#3D` | `SRI` | 7,882 | ✓ | — | 지원 경로 있음 | `0x22c` |
| `#3F` | `SRUI` | 2,096 | ✓ | — | 지원 경로 있음 | `0xc8` |
| `#40` | `BN` | 204 | ✓ | — | 지원 경로 있음 | `0x3e20` |
| `#41` | `BNB` | 123 | ✓ | — | 지원 경로 있음 | `0x628` |
| `#42` | `BZ` | 1,522 | ✓ | — | 지원 경로 있음 | `0xc58` |
| `#43` | `BZB` | 21 | ✓ | — | 지원 경로 있음 | `0x11388` |
| `#44` | `BP` | 377 | ✓ | — | 지원 경로 있음 | `0xd28` |
| `#45` | `BPB` | 62 | ✓ | — | 지원 경로 있음 | `0x1d6c` |
| `#48` | `BNN` | 293 | ✓ | — | 지원 경로 있음 | `0x30` |
| `#49` | `BNNB` | 18 | ✓ | — | 지원 경로 있음 | `0x2230c` |
| `#4A` | `BNZ` | 1,094 | ✓ | — | 지원 경로 있음 | `0x940` |
| `#4B` | `BNZB` | 129 | ✓ | — | 지원 경로 있음 | `0x764` |
| `#4C` | `BNP` | 350 | ✓ | — | 지원 경로 있음 | `0xb48` |
| `#4D` | `BNPB` | 203 | ✓ | — | 지원 경로 있음 | `0x1f70` |
| `#60` | `CSN` | 358 | ✓ | — | 지원 경로 있음 | `0x1334` |
| `#68` | `CSNN` | 41 | ✓ | — | 지원 경로 있음 | `0x1404` |
| `#6C` | `CSNP` | 1 | ✓ | — | 지원 경로 있음 | `0x72c90` |
| `#80` | `LDB` | 5 | ✓ | — | 지원 경로 있음 | `0x65f48` |
| `#81` | `LDBI` | 454 | ✓ | — | 지원 경로 있음 | `0x68c` |
| `#85` | `LDWI` | 627 | ✓ | — | 지원 경로 있음 | `0x158` |
| `#89` | `LDTI` | 10,064 | ✓ | — | 지원 경로 있음 | `0xc0` |
| `#8D` | `LDOI` | 9,586 | ✓ | — | 지원 경로 있음 | `0x1d4` |
| `#8F` | `LDOUI` | 2 | ✓ | — | 지원 경로 있음 | `0x80` |
| `#A2` | `STBU` | 8 | ✓ | — | 지원 경로 있음 | `0x23a98` |
| `#A3` | `STBUI` | 254 | ✓ | — | 지원 경로 있음 | `0x5d8` |
| `#A7` | `STWUI` | 282 | ✓ | — | 지원 경로 있음 | `0x150` |
| `#AB` | `STTUI` | 5,292 | ✓ | — | 지원 경로 있음 | `0xb8` |
| `#AF` | `STOUI` | 2,190 | ✓ | — | 지원 경로 있음 | `0x34` |
| `#B5` | `STCOI` | 77 | ✓ | — | 지원 경로 있음 | `0x8324` |
| `#BF` | `PUSHGOI` | 469 | — | — | 미구현 | `0x1e8` |
| `#C0` | `OR` | 55 | ✓ | — | 지원 경로 있음 | `0xf8` |
| `#C1` | `ORI` | 13,487 | ✓ | — | 지원 경로 있음 | `0x0` |
| `#C5` | `NORI` | 2 | ✓ | — | 지원 경로 있음 | `0x17334` |
| `#C6` | `XOR` | 110 | ✓ | — | 지원 경로 있음 | `0x132c` |
| `#C7` | `XORI` | 21 | ✓ | — | 지원 경로 있음 | `0x1df0` |
| `#C8` | `AND` | 125 | ✓ | — | 지원 경로 있음 | `0xf0` |
| `#C9` | `ANDI` | 232 | ✓ | — | 지원 경로 있음 | `0x14a8` |
| `#E2` | `SETML` | 288 | ✓ | ✓ | 지원 경로 있음 | `0x110` |
| `#E3` | `SETL` | 5,862 | ✓ | ✓ | 지원 경로 있음 | `0x8` |
| `#E4` | `INCH` | 1,001 | ✓ | ✓ | 지원 경로 있음 | `0x18` |
| `#E5` | `INCMH` | 988 | ✓ | ✓ | 지원 경로 있음 | `0x14` |
| `#E6` | `INCML` | 1,286 | ✓ | ✓ | 지원 경로 있음 | `0x10` |
| `#E7` | `INCL` | 161 | ✓ | ✓ | 지원 경로 있음 | `0x3a70` |
| `#E8` | `ORH` | 3 | ✓ | ✓ | 지원 경로 있음 | `0x6fe48` |
| `#EA` | `ORML` | 4 | ✓ | ✓ | 지원 경로 있음 | `0x27218` |
| `#EB` | `ORL` | 18 | ✓ | ✓ | 지원 경로 있음 | `0x1b774` |
| `#EE` | `ANDNML` | 6 | ✓ | ✓ | 지원 경로 있음 | `0x13790` |
| `#EF` | `ANDNL` | 27 | ✓ | ✓ | 지원 경로 있음 | `0x4500` |
| `#F0` | `JMP` | 2,530 | ✓ | — | 지원 경로 있음 | `0xa4` |
| `#F1` | `JMPB` | 828 | ✓ | — | 지원 경로 있음 | `0x3c` |
| `#F2` | `PUSHJ` | 1,783 | — | — | 미구현 | `0x6c` |
| `#F3` | `PUSHJB` | 1,752 | — | — | 미구현 | `0x558` |
| `#F4` | `GETA` | 4 | ✓ | — | 지원 경로 있음 | `0x27df0` |
| `#F5` | `GETAB` | 82 | ✓ | — | 지원 경로 있음 | `0x1b64` |
| `#F6` | `PUT` | 4,002 | ✓ | — | 지원 경로 있음 | `0x1ec` |
| `#F8` | `POP` | 823 | — | — | 미구현 | `0x140` |
| `#FD` | `SWYM` | 116 | — | — | 미구현 | `0x1918` |
| `#FE` | `GET` | 673 | ✓ | — | 지원 경로 있음 | `0x1b4` |

## 부족한 명령어

`TRAP` (`#00`), `PUSHGOI` (`#BF`), `PUSHJ` (`#F2`), `PUSHJB` (`#F3`), `POP` (`#F8`), `SWYM` (`#FD`)

체크리스트의 유닛 미체크 항목은 `TRAP`, `PUSHGOI`, `PUSHJ`, `PUSHJB`, `POP`, `SWYM`이다.

구현 순서는 다음처럼 묶는 것이 좋다. 명령어 수를 줄이기 위해 프로그램에 맞춘 특례를 넣지 않고, 기존 주소 계산·메모리·특수 레지스터·제어 경로를 확장한다.

| 묶음 | 이 빌드에 등장하는 미구현 명령 | 필요한 작업 |
|---|---|---|
| 기본 실행 | SWYM | SWYM의 무부작용 PC 진행 |
| 호출·복귀 | PUSHGOI, PUSHJ, PUSHJB, POP | 레지스터 스택과 rJ, 상대/간접 목표 및 복귀 제어 |
| 운영체제 진입 | TRAP | HALT 특례와 분리된 트랩 진입·nnix-os 핸들러·복귀 |

## opcode 외에 필요한 실행 기반

- **레지스터 스택과 호출 ABI:** PUSHJ/PUSHGO/POP은 PC 이동만으로 구현할 수 없다. 현재의 평면 256개 레지스터 파일에 rL/rG, 윈도 이동, rJ, spill/fill 및 메모리 스택 처리가 필요하다.
- **일반 TRAP와 OS 복귀:** CPU는 현재 정확한 TRAP 0,0,0만 정지로 처리한다. 앱의 TRAP 1,Y,Z 시스템콜에는 트랩 진입·상태 보존·핸들러 실행·복귀와 nnix-os가 필요하다.
- **작은 정수 메모리 접근:** 현재 ISA 목록의 지원 여부와 실행 검증은 별개다. 폭별 부호/0 확장·부분 쓰기·캐시 갱신을 검증해야 하며, signed 부분 store의 V 예외는 이 빌드에서 쓰는 unsigned store와 별도의 구현 항목이다.
- **주소 공간과 I/O:** 현재 CPU의 RAM 경로는 30비트 MIG AXI다. 드라이버의 MMIO 주소는 2^48 기반이므로 주소 디코딩/별도 주변장치 경로가 필요하다. 프레임버퍼·타이머·USB·오디오·SD/파일시스템도 opcode 구현만으로 제공되지 않는다.
- **시작 환경:** crt0는 BIOS가 $253/$254/$255에 넘기는 RAM 크기와 실행/링크 정보를 사용한다. BSS·힙·소프트웨어 스택과 초기 전역 레지스터 값도 준비해야 한다.
- **시간 콜백:** 실제 main,c의 cb_gettime은 TODO 상태로 sec/usec에 0을 반환한다. README의 타이머 syscall 설명과 다르므로 게임 시간 공급도 연결해야 한다.

현재 GET/PUT 지원 레지스터는 `rB`, `rD`, `rE`, `rH`, `rJ`, `rM`, `rR`, `rBB`, `rA`, `rF`, `rP`, `rW`, `rX`, `rY`, `rZ`, `rWW`, `rXX`, `rYY`, `rZZ`이다. rA는 `0xc0c0` 마스크의 비트만 지원한다. 시스템·스택 레지스터를 저장 셀 접근으로 대신하지 않으며, 미지원 대상과 rA 값은 CPU에서 거부한다. 위 표의 GET/PUT 지원 판정은 바이너리에 나타난 rJ/rR 대상에 대해 이 범위를 확인한 결과다.

이번 ELF에는 전역 레지스터 초기값 **125개 ($130~$254)**가 `.MMIX.reg_contents.linker_allocated`에 들어 있다. 이 비적재 섹션의 초기화 메타데이터는 RAW 파일에 포함되지 않는다. 로더/시작 환경이 이 초기값과 rG를 준비해야 하며, $253/$254의 BIOS 전달값·스택 포인터 규약과도 조정해야 한다. 대안으로 -mno-base-addresses 빌드를 검토할 수 있지만 그 변형은 별도 재빌드·재분석 대상이며 이 문서의 통계와 섞지 않았다. 초기값 전체는 JSON에 보존했다.

## TRAP 및 특수 레지스터 피연산자

| 명령 | 피연산자 | 정적 개수 |
|---|---|---:|
| TRAP | `0,0,0` | 2 |
| TRAP | `1,1,0` | 1 |
| TRAP | `1,1,1` | 1 |
| TRAP | `1,1,2` | 1 |
| TRAP | `1,1,3` | 1 |
| TRAP | `1,1,4` | 1 |
| TRAP | `1,1,5` | 1 |
| TRAP | `1,10,0` | 1 |
| TRAP | `1,13,1` | 1 |
| TRAP | `1,14,0` | 1 |
| TRAP | `1,16,0` | 1 |
| TRAP | `1,17,0` | 1 |
| TRAP | `1,2,0` | 1 |
| TRAP | `1,3,0` | 1 |
| TRAP | `1,6,0` | 1 |
| TRAP | `1,9,0` | 1 |
| TRAP | `1,9,1` | 1 |
| TRAP | `1,9,2` | 1 |
| GET | `rJ` | 631 |
| GET | `rR` | 42 |
| PUT | `rJ` | 4002 |

## 재현 정보

- ELF 진입점: `0x0`
- 컴파일러 ZIP SHA-256: `e642b9d658f8f78d99700f9d4bca2c255314b5b82e4674ee39ba7a71a2b91599`
- ELF: `build\doom-audit\doom.elf` / SHA-256 `ce14efde091c0d4fdebd944bfd4ad9fe62ab7572036875f43ca35a72624e8f38`
- RAW: `build\doom-audit\doom.bin` / SHA-256 `4b22a14cd5d9b9abf986b1f3aa3c5630c13795c14acdbc6d347b5a8b1e72792b`
- checklist: `op체크리스트.md` / SHA-256 `9a1a9574c077f55f28613a2af5069b4d0b6d6c0f2631adc2c3654f238b543888`
- ISA: `test\cpu\isa.json` / SHA-256 `1722a070e45dc87e1e31495e0aacd7b9ada40f952048fc300e0b7dc0bbd398c9`
- opcode_source: `.tools\mmix\mmix-sim.w` / SHA-256 `b05457068a19702d817b72e3a4b0b517fcbeb1a05d72102dc1ae2c13f8495724`

재현 스크립트: [빌드](../verification/build_doom_audit.py), [ELF/RAW 분석과 체크리스트 대조](../verification/doom_audit.py). 기계가 읽을 수 있는 결과는 [JSON](doom-instructions.json), [CSV](doom-instructions.csv)에 있다.

빌드 명령:

```text
git clone --filter=blob:none --no-checkout --single-branch --branch main https://github.com/nananina0415/ARC-FTNM.git .tools/doom-audit/ARC-FTNM
git -C .tools/doom-audit/ARC-FTNM sparse-checkout init --no-cone
git -C .tools/doom-audit/ARC-FTNM sparse-checkout set '/apps/doom/*' '!/apps/doom/freedoom-0.13.0/' '/nnix-os/' '/drivers/' '/tools/' '/README.md' '/.gitmodules'
git -C .tools/doom-audit/ARC-FTNM checkout --detach ea4e8130950e10244650282e8ef671e845da89bf
download https://mmix.cs.hm.edu/exe/gcc-4.8.5.zip and extract to .tools/doom-audit/prebuilt
python verification\build_doom_audit.py --tools .tools/doom-audit/prebuilt/mmix/bin --compiler .tools/doom-audit/prebuilt/mmix/bin/mmix-gcc.exe
```

분석 명령:

```powershell
python verification/doom_audit.py --elf build/doom-audit/doom.elf --raw build/doom-audit/doom.bin --manifest build/doom-audit/manifest.json
```

연결된 원본 파일:

- [Doom CMake](https://github.com/nananina0415/ARC-FTNM/blob/ea4e8130950e10244650282e8ef671e845da89bf/apps/doom/CMakeLists.txt)
- [RAW linker script](https://github.com/nananina0415/ARC-FTNM/blob/ea4e8130950e10244650282e8ef671e845da89bf/apps/doom/link.ld)
- [startup](https://github.com/nananina0415/ARC-FTNM/blob/ea4e8130950e10244650282e8ef671e845da89bf/nnix-os/libc/crt0.s)
- [FPGA main](https://github.com/nananina0415/ARC-FTNM/blob/ea4e8130950e10244650282e8ef671e845da89bf/apps/doom/src/main,c)
- [MMIO address layout](https://github.com/nananina0415/ARC-FTNM/blob/ea4e8130950e10244650282e8ef671e845da89bf/nnix-os/src/device/types.h)
- [MMIX Windows compiler distribution](https://mmix.cs.hm.edu/exe/index.html)

본 문서 생성 과정에서 Doom 실행, CPU 시뮬레이션, 브라우저 조작은 수행하지 않았다.
