# 10·11번 워드를 더해 12번 워드에 저장

프로젝트의 WORD_SIZE=64 기준이다. MMIX 용어로는 octa이며 10·11·12는 0 기준
워드 번호다. 실제 바이트 주소는 각각 80·88·96이다.

## 프로그램

```text
0:   LDO  $1,$0,80
4:   LDO  $2,$0,88
8:   ADD  $3,$1,$2
12:  STO  $3,$0,96
16:  TRAP 0,Halt,0
```

리셋 후 $0=0을 주소의 기준으로 사용한다. MMIX의 $0이 하드웨어 zero 레지스터라는 뜻은 아니다.
명령어 바이트는 8D 01 00 50 / 8D 02 00 58 / 20 03 01 02 / AD 03 00 60 / 00 00 00 00이다.
[어셈블리 소스](../verification/programs/add_memory.mms)를 참고한다.
테스트벤치는 위 인코딩을 RAM에 적재하며 MMIX assembler를 실행한 것은 아니다.

## 실제 RTL 시뮬레이션 결과

Icarus Verilog로 src/cpu의 생성 RTL을 실행했다. pyslang의 65개 모듈 분석도 진단 0건이다.

| RAM[10] | RAM[11] | HALT 후 RAM[12] |
|---:|---:|---:|
| 123 | 456 | 579 |
| 72623859790382856 | 4822678189205111 | 77446537979587967 |
| 0 | 0 | 0 |
| 18446744073709551615 | 1 | 0 |
| 987654321 | 123456789 | 1111111110 |

네 번째 행은 저장된 64비트 패턴을 unsigned로 표시한 것이며 ADD에서는 -1+1=0이다.
각 경우 데이터 load 2회, store 1회, 범용 레지스터 쓰기 3회, 전체 읽기 7회를 확인했다.
전체 읽기는 명령어 fetch 5회와 데이터 읽기 2회다.

확인 사항:

- 실제 RAM 응답을 받아 두 피연산자가 레지스터에 적재됨.
- ADD 결과가 STO의 데이터로 전달되고, 96~103번 바이트에 big-endian으로 저장됨.
- WSTRB=0x000000FF로 이웃 데이터와 프로그램·입력값은 변경되지 않음.
- AR/AW/W READY를 서로 다른 주기로 지연해 VALID와 payload 유지 확인.
- store B 응답을 11사이클 지연해, 쓰기 응답 전에 HALT하지 않는지 확인.
- HALT 뒤 추가 fetch·store·레지스터 쓰기 없음.

## 구현 변경

기존 CPU에 데이터 접근 경로가 없어서 재사용 가능한 MemoryAccess, 직렬 ReadSelect,
주소 계산·store 데이터 준비·load writeback과 제어 단계를 추가했다.
CPU는 메모리의 10/11/12번 워드나 특정 프로그램 내용을 알지 못한다.
변환기는 수정하지 않고 의사코드에서 RTL을 다시 생성했다.

기존 CPU 7개 시나리오와 전체 opcode 디코더 520개 경우도 회귀 검사에서 통과했다.
재생성한 68개 artifact와 디스크 생성물의 일치 및 결정론적 출력도 확인했다.

## 실행 및 파형

```powershell
python -m crosscompiler pseudo --top cpu::Cpu --out src/cpu
python verification/simulate_memory_program.py
```

테스트벤치: build/memory-program/memory_program.sv
실행 로그: build/memory-program/memory_program.log
집계: build/memory-program/results.json
파형: build/memory-program/memory_program.vcd

파형에는 program_counter와 memory_word_10/11/12 및 주요 AXI 핸드셰이크가 들어간다.
실제 MIG/DDR3 모델 대신 byte-addressed AXI RAM 모델을 사용했다.
Vivado 합성·배치배선·실제 보드 동작을 확인한 결과는 아니다.
