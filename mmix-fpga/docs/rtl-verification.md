# 생성 Verilog 검증 결과

검증일: 2026-11-02. 사용자의 명시적인 검증 요청에 따라 수행했다.
대상은 `src/cpu/sources.f`에 나열된 Verilog 55개와 top `s_3_cpu_3_Cpu`다.

## 결론

실행한 구문 검사와 시뮬레이션 범위에서는 의도한 설계와 일치했다.
0번지 ADD 한 개만 처리하는 전용 회로가 아니라 PC로 연속 실행하고 HALT에서 멈추며,
레지스터 의존성과 32바이트 블록 경계 너머의 명령도 처리했다.
이 결과를 전체 MMIX 사양 준수나 FPGA 동작 보장으로 확대 해석하지 않는다.

| 검사 | 결과 |
|---|---|
| 현재 소스/변환기에서 재생성한 58개 artifact와 src/cpu 비교 | 일치 |
| 독립된 두 번의 변환 결과 비교 | 동일 |
| pyslang 12.1.0의 55개 모듈 분석 | 오류·경고 0건 |
| Icarus Verilog 컴파일과 ALU 시뮬레이션 | 7,380개 벡터 통과 |
| 디코더 전체 opcode 시뮬레이션 | 520개 경우 통과 |
| CPU와 지연/역압을 갖는 AXI RAM 모델 | 아래 7개 시나리오 통과 |

## CPU 시나리오

| 프로그램/조건 | 확인한 결과 |
|---|---|
| ADD $1,$2,$3; HALT | 리셋 초기값 0+0 저장 1회, 페치 2회 후 정지 |
| ADDI로 7과 9 준비 → ADD 결과 16 → 의존 ADD 결과 23 → HALT | 실제 레지스터 갱신·읽기와 연속 실행, 저장 4회 |
| ADDI 10회 누적 → ADD로 두 배 → HALT | 결과 10/20, 명령 12개 페치, 32바이트 경계 통과 |
| ADDI로 1 → SLUI로 최솟값 비트 패턴 → ADD 최솟값+최솟값 → HALT | 결과 0, rA의 V event=1, enable=0, 정상 완료 |
| 미지원 opcode #01 | 저장 없이 fault_reason=2 |
| AXI RRESP=SLVERR | 저장 없이 fault_reason=1 |
| 기대하지 않은 AXI RID=1 | 저장 없이 fault_reason=1 |

모든 CPU 시나리오에 calibration 대기, 주기적인 ARREADY 지연, 읽기 응답 지연을 적용했다.
ARVALID 정체 중 주소 유지, ARSIZE=5/ARLEN=0/INCR/ID=0, 기대 주소 블록,
명령어 바이트 순서, 쓰기 횟수·목적지·결과, 정지 후 추가 요청/쓰기 없음을 검사했다.
AXI 쓰기 채널도 비활성 상태임을 확인했다.

## 검증 중 수정

최초 HDL 분석에서 표준 산술 소자에 경고 9건이 나왔다. 부호 변환과 폭 절삭의
의도를 변환기에서 명시하도록 바꾸고 `src/cpu`를 다시 생성했다.

- Adder: carry-in도 N+1비트로 0 확장해 같은 폭끼리 더함.
- Multiplier: signed 확장과 unsigned 비트 결과 변환을 명시.
- Divider: divisor를 2N비트로 확장하고 전체 몫/나머지에서 하위 N비트를 명시적으로 선택.

이후 HDL 분석에서 진단 0건, 모든 기능 시뮬레이션 통과를 확인했다.
생성 Verilog를 수동 수정하지 않고 crosscompiler/builtins.py에서 수정했다.

## 재현 및 증거

- [검증 프로그램](../verification/verify_generated.py)
- [실행 방법](../verification/README.md)
- 실행 로그: `build/verification/slang.log`, `alu.log`, `decoder.log`, `cpu.log`
- 집계: `build/verification/results.json`
- 생성 테스트벤치와 Icarus 컴파일 로그도 같은 폴더에 저장했다.

검증 도구는 `.tools`에 로컬로 준비했으며 시스템 PATH를 변경하지 않았다.
`.tools`와 `build`는 Git에서 제외한다.

## 확인하지 않은 범위

- Vivado 합성·배치배선·클록 타이밍과 자원 사용량.
- 실제 MIG/DDR3 모델 또는 보드에 연결한 동작, 클록·리셋 도메인 통합.
- 모든 64비트 입력 조합의 형식 등가성. ALU 검사는 경곗값·고정 seed 난수 기반이다.
- 미지원 명령, 실제 TRAP/trip 처리기 진입, 인터럽트와 레지스터 스택.
- 모든 AXI 비정상 시퀀스·응답 미도착·진행 중 리셋에 대한 별도 시나리오.
- HALT는 현재 런타임의 TRAP 0,Halt,0 직접 정지 구현이며 일반 TRAP 구현은 아니다.

특히 내부 삼상 버스를 사용하므로 실제 Vivado 합성에서의 net 처리와 자원·타이밍은
별도 확인이 필요하다. 시뮬레이션 통과를 물리 구현의 보장으로 표현하지 않는다.
