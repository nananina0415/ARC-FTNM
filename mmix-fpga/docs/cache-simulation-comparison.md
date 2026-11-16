# L1 캐시 적용 전후 시뮬레이션 비교

## 결과

동일한 LDO/LDO/ADD/STO/HALT 프로그램에서 실행 구간이 **77클록 → 57클록**으로
줄었다. **20클록 절감, 실행 시간 약 26.0% 감소, 약 1.35배 속도 향상**이다.
AXI 읽기는 7회에서 3회로 줄었으며, 저장 결과는 두 경우 모두 RAM[12]=579다.

| 항목 | 캐시 없음 | I/D 캐시 적용 | 변화 |
|---|---:|---:|---|
| 실행 구간 | 77클록 | 57클록 | 20클록 절감 |
| 같은 10ns 클록에서 실행 구간 | 770ns | 570ns | 26.0% 감소 |
| 명령어 AXI 읽기 | 5회 | 2회 | 60% 감소 |
| 데이터 AXI 읽기 | 2회 | 1회 | 50% 감소 |
| 전체 AXI 읽기 | 7회 | 3회 | 57.1% 감소 |
| 읽기 응답 데이터 양 | 224바이트 | 96바이트 | 128바이트 감소 |
| AXI store | 1회 | 1회 | write-through로 동일 |
| RAM[12] | 579 | 579 | 동일 |

실행 구간은 reset=0 및 calibration=1이 처음 관측된 스냅샷부터 HALT가 처음
관측된 스냅샷까지의 차이다. 시작은 양쪽 모두 클록 9(t=96ns)이고,
HALT는 캐시 없음 클록 86(t=866ns), 캐시 적용 클록 66(t=666ns)이다.
클록 번호는 0 기준이며 모든 스냅샷은 상승 에지 +1ns다.
이 수치는 합성 후 실제 FPGA 주파수 측정값이 아니다.

전체 기록 개수는 102개에서 82개로 바뀌었다. 여기에는 초기 reset/calibration 대기와
HALT 후 관찰 구간도 있으므로 성능 비교에는 이 전체 길이 대신 위 실행 구간을 썼다.

## 실행 조건과 근거

- 프로그램: RAM의 10/11번 64비트 워드(바이트 주소 80/88)에 저장한 123/456을
  읽어 더하고, 12번 워드(주소 96)에 579를 저장한 뒤 HALT.
- 동일한 10ns clock, reset/calibration 순서, READY 지연과 읽기 응답 지연 모델.
- AW/W는 각각 다른 주기로 READY를 주고 B 응답은 11사이클 지연.
- 매 시뮬레이션 reset으로 캐시가 비어 있는 cold-start 조건.
- 이전 cycles.jsonl/HTML/log를 build/cache-comparison/before에 보존한 뒤 새 RTL 실행.
- 비교는 실제 기록에서 계산했으며, RTL 실행 대신 컴파일러 IR을 평가한 결과가 아니다.

레지스터 저장 시점도 앞당겨졌다.

| 저장 | 캐시 없음 클록 | 캐시 적용 클록 |
|---|---:|---:|
| $1 ← 123 | 26 | 26 |
| $2 ← 456 | 42 | 32 |
| $3 ← 579 | 50 | 35 |

## 빨라진 이유와 남는 지연

첫 명령어 미스 때 같은 라인의 명령어 8개를 채운다. 그 뒤 LDO/ADD/STO의
명령어 접근은 캐시에서 처리한다. 10/11번 워드도 같은 데이터 라인에 있으므로
첫 데이터 읽기로 함께 채워지고 두 번째 LDO는 AXI 없이 응답한다.

명령어 조회 5번 중 3번, 데이터 조회 2번 중 1번이 적중했다.
store 완료 시 I-cache 전체를 무효화하는 현재 정책 때문에 HALT는 다시 AXI로
읽는다. 따라서 이 예제의 명령어 읽기는 1회가 아니라 2회다.

데이터 쓰기는 write-through이므로 AW/W/B 대기가 남아 있고, CPU는 여전히
직렬 실행 구조다. 캐시를 넣었다고 모든 명령이 1클록 실행이 되지는 않는다.
이 결과는 해당 짧은 프로그램과 지연 모델의 결과이며 일반적인 성능 보장은 아니다.

## 함께 확인한 사항

- 생성 Verilog 73개: pyslang 구문/elaboration 진단 0건.
- 프로그램 결과, load/store/레지스터 쓰기 수, big-endian 저장 및 주변 메모리 보존 통과.
- RAM[10]/RAM[11] load는 두 명령이지만 AXI 데이터 읽기는 한 번임을 검사.
- 8개 라인 동시 유지, byte-mask store 갱신, no-write-allocate, 충돌 교체 및 전체 무효화 통과.
- 기존 CPU 7개 회귀 시나리오도 캐시 적용 후 통과. 연속 12명령 프로그램은
  명령어 읽기 12회 대신 캐시라인 읽기 2회로 완료.
- 실제 MIG/DDR3·보드, Vivado 합성·타이밍은 이번 검증에 포함하지 않음.

## 기록과 재현

- 최신 슬라이더: [캐시 적용 HTML](../build/memory-program/cycles.html)
- 이전 슬라이더: [캐시 없는 HTML](../build/cache-comparison/before/cycles.html)
- 원시 비교: build/cache-comparison/comparison.json
- 최신 원시 덤프/로그: build/memory-program/cycles.jsonl, memory_program.log
- 캐시 단위 검사와 회귀 로그: build/verification/cache.log, cpu.log

```powershell
python verification/simulate_memory_program.py --trace
python verification/verify_generated.py --only cache
python verification/verify_generated.py --only cpu
python verification/compare_cache_runs.py
```

비교 스크립트는 보존한 캐시 없는 기록을 입력으로 사용한다. 현재 RTL을 다시 실행해서
캐시 없는 기록을 재구성하는 스크립트는 아니며, before 기록을 새 결과로 덮어쓰지 않는다.
