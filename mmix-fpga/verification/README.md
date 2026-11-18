# 생성 RTL 검증

사용자가 명시적으로 검증을 요청했을 때만 실행한다.
대상은 `src/cpu`에 생성된 실제 Verilog이며, Python으로 회로 IR을 대신 실행하지 않는다.

## 도구

- Python 3.10 이상.
- pyslang: `python -m pip install --target .tools/hdl -r verification/requirements.txt`.
- Icarus Verilog의 iverilog와 같은 폴더의 vvp.

## 실행

저장소 루트에서:

```powershell
python verification/verify_generated.py --iverilog path/to/iverilog.exe
```

`--rtl` 기본값은 src/cpu, `--out` 기본값은 build/verification이다.
`--only parity`, `lint`, `alu`, `decoder`, `cpu`로 한 검사를 선택할 수 있다.
`--only cache`는 LineCache의 reset 정리·라인 유지·충돌·masked store·라인 무효화·동시 쓰기 조회만 별도로 시뮬레이션한다.
테스트벤치와 컴파일/실행 로그, results.json을 출력 폴더에 저장한다.

검사 범위:

- parity: 현재 소스로 두 번 생성한 결과의 동일성 및 디스크 생성물과의 일치.
- lint: 모든 생성 모듈의 구문·elaboration과 진단.
- alu: 45개 연산 모드에 대해 경곗값 조합과 고정 seed 난수, 총 7,380개 벡터.
  기대값은 컴파일러 평가기와 별개의 Python 정수 연산으로 계산한다.
  결과·조건부 쓰기·쓰기 허용·rA event/enable·trip·rH/rR 쓰기를 비교한다.
- decoder: 256개 opcode의 valid/invalid 입력과 HALT/TRAP 변형, 총 520개 경우.
- cpu: AXI RAM 모델을 연결한 실제 RTL 시뮬레이션. 초기화 대기, AR backpressure,
  응답 지연, 바이트 lane과 PC 진행, 실제 레지스터 의존성, HALT/FAULT 정지 확인.

CPU의 일곱 시나리오는 [검증 보고서](../docs/rtl-verification.md)에 기록했다.
메모리 load/add/store 프로그램은 `python verification/simulate_memory_program.py`로
별도 실행하며 [프로그램 보고서](../docs/memory-program-simulation.md)에 결과와 파형 경로를 기록했다.
이 검사는 전 입력에 대한 형식 등가 증명이나 Vivado 합성·타이밍 검증이 아니다.

## 클록별 덤프와 HTML

```powershell
python verification/simulate_memory_program.py --trace
```

첫 데이터셋(123+456)만 실제 RTL로 실행하고 매 상승 에지의 +1ns에 상태를 기록한다.
해당 에지의 handshake/레지스터 쓰기는 갱신 전 값으로 따로 기록한다.

- build/memory-program/cycles.jsonl: 클록별 원시 덤프.
- build/memory-program/cycles.html: 모든 데이터를 내장한 독립 HTML 뷰어.
- 범용 레지스터 슬롯 512개(로컬 ring l[0..255], 글로벌 g[0..255]), 특수 레지스터 32개, RAM 모델 전체 256바이트, PC/상태/AXI 신호.
- 슬라이더·재생·변화 지점 이동, 이전 클록 대비 강조, 16진수/정수 표시, 바이트/워드 덤프.
- 정수 표시는 JavaScript BigInt를 사용하고 X/Z는 문자열로 유지한다.
- 실제 보드 RAM 전체를 덤프하는 것이 아니라 시뮬레이션 RAM 모델의 256바이트를 기록한다.

덤프를 다시 시뮬레이션하지 않고 HTML만 재생성하려면 verification/cycle_trace.py의
render(trace_path, html_path)를 호출한다. 브라우저나 서버 없이 파일 생성이 가능하다.

캐시 적용 전후의 실제 기록 비교는 `python verification/compare_cache_runs.py`로
집계한다. 결과와 기준은 [캐시 비교 보고서](../docs/cache-simulation-comparison.md)에 기록했다.
