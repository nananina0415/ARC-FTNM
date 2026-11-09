# PC 및 분기 실행 제어

Control은 64비트 PC를 보유하며 reset vector 0에서 시작한다. 정상 일반 명령과
메모리 명령은 완료 시 PC+4로 진행하고, 분기는 조건에 따라 목표 PC를 선택한다.
HALT와 FAULT에서는 reset까지 해당 PC를 유지한다.

| 단계 | 동작 |
|---|---|
| REQUEST | 현재 PC 요청을 Fetch가 수락할 때까지 유지 |
| WAIT | 페치 결과 또는 오류 대기 |
| EXECUTE | ALU/분기 완료, 메모리 주소 캡처, 또는 HALT/fault 진입 |
| MEMORY_REQUEST | 데이터 요청 수락 대기 |
| MEMORY_WAIT | load 데이터 또는 store B 응답 대기 |
| MEMORY_COMMIT | load 결과 저장 또는 store 완료 후 PC+4 |
| HALTED | done=1, 추가 요청과 레지스터 쓰기 없음 |
| FAULT | fault=1, 추가 요청과 레지스터 쓰기 없음 |
| STACK | StackControl이 레지스터 스택 동작을 수행. 끝나면 EXECUTE 재실행, 명령 완료, 또는 FAULT |

## 분기 구조

`Control.branch: Branch` 안에 `condition::Condition`과 `RelativeAddress`를 배치한다.
ALU의 `Cond`도 같은 Condition 정의를 별도로 인스턴스화한다. 모듈 정의를 재사용하며
물리 회로 하나를 공유하지는 않는다. 조건 판정은 쓰기 허용과 예외 신호에 독립적이다.

| 구성 요소 | 역할 |
|---|---|
| InstructionDecode | 분기 종류, 조건 번호, 읽을 $X, signed 바이트 오프셋 해석 |
| Condition | N/Z/P/OD/NN/NZ/NP/EV 판정 |
| RelativeAddress | 현재 명령 PC + 오프셋 계산 |
| Branch | 조건 결과와 무조건 분기 여부로 taken 결정 |
| Control.next_pc | PC+4 또는 분기 목표 선택 |

조건 분기는 읽기 포트 1로 $X를 읽는다. JMP는 XYZ 전체를 오프셋으로 쓰며 조건은
검사하지 않는다. PB*는 B*와 동일한 실행 의미를 가지며 예측기는 아직 구현하지 않았다.
앞/뒤 방향은 opcode bit 0으로 해석한다. YZ/XYZ 자체를 signed 16/24비트 값으로
해석하지 않는다. 기준 주소도 PC+4가 아닌 현재 분기 명령의 PC다.

목표 주소 덧셈은 ALU와 분리하고 기존 PC+4 덧셈기는 순차 실행에 사용한다.
`next_pc`는 정상 EXECUTE 완료 시 분기 taken이면 목표 주소, 아니면 PC+4를 선택한다.
현재 직렬 Fetch는 분기 명령을 EXECUTE까지 유지하고 완료 에지에서 소비한다.
다음 REQUEST는 갱신된 PC로 일반 I-cache 조회/페치를 수행한다. 분기 해결 중
추측 요청을 발행하지 않으므로 별도의 추측 응답 취소 절차는 필요하지 않다.

`retire_execute`는 분기를 포함한 일반 명령의 완료 신호다. ALU 쓰기용 `execute_en`은
여기서 분기를 제외하므로 B/PB/JMP는 범용 레지스터와 rA/rH/rR을 쓰지 않는다.
주소 덧셈은 64비트 modulo 연산이며 산술 예외를 일으키지 않는다. 접근할 수 없는
목표는 다음 Fetch에서 오류로 보고하며 FAULT의 PC에는 잘못된 목표 주소가 남는다.
실행 횟수나 예제의 분기 주소를 하드코딩하지 않는다.

지원하는 제어 이동은 #40~#5F의 B/PB, #F0~#F1의 JMP/JMPB, 레지스터 스택의
PUSHJ/PUSHGO/POP이다. GO는 아직 구현하지 않았다. RelativeAddress는 PUSHJ에서
현재 PC, POP에서 rJ를 기준으로 쓴다.

## 레지스터 스택

슬롯 구조는 [reg/README.md](../reg/README.md)를 따른다. EXECUTE에서 `stack.required`이면
STACK으로 넘어가고 ALU 쓰기, PC 진행, 메모리 시작을 하지 않는다. 명령 버퍼는 완료까지 유지한다.

| StackControl 단계 | 동작 |
|---|---|
| GROW, CHECK | `l[α+L]←0`, L+1. `(미저장 칸 수 + L) mod R = 0`이면 SPILL. 끝나면 EXECUTE 재실행 |
| SPILL_REQUEST, SPILL_WAIT | `M8[rS]←l[γ]`, rS+8 |
| PUSH_COMMIT | `l[α+n]←n`, rJ←PC+4, L−(n+1), rO+8(n+1), PC←목표 |
| POP_READ, POP_BASE, POP_HOLE | y 읽기, 빈 ring이면 FILL, hole에서 k 읽기, 미저장 칸 수 <= k이면 FILL |
| FILL_ADDRESS, FILL_REQUEST, FILL_WAIT | rS−8, `l[γ]←M8[rS]` |
| POP_COMMIT | `L←min(k+X',G)`, L>k이면 `l[α-1]←y`, PC←rJ+4YZ, rO−8(k+1) |
| G_FLUSH, G_CLEAR, G_COMMIT | R이 바뀌면 전부 SPILL, 새로 글로벌이 된 칸을 0으로, G 갱신 |

X<G인 PUSH는 일반 목적지와 같이 marginal이면 GROW를 거친다. X>=G인 PUSH는 GROW를 한 번
거쳐 n=L-1로 hole을 쓴다. `$X`가 목적지인 명령은 조건이 거짓인 CS*여도 먼저 L을 늘린다.
spill/fill은 octa 크기로 MemoryAccess를 사용하며 접근 오류는 fault_reason bit 0이다.
L이 R에 닿는 경우와 hole 값 k가 R 이상인 경우는 fault_reason bit 1이다.

## 메모리·정지·오류

메모리 명령은 MEMORY_COMMIT 또는 접근 오류까지 페치 응답을 유지한다. 주소를
캡처한 뒤 store 데이터는 읽기 포트 1에서 $X를 읽는다. store 완료에는 AXI B 응답이
필요하다. CPU는 I/D-cache를 사용하며 AXI 데이터 쓰기도 활성화되어 있다.
프로그램 적재는 외부에서 수행한다.

HALT는 정확한 TRAP 0,Halt,0(#00000000) 런타임 관례다. 일반 TRAP/OS 예외 진입은
미구현이다. enable된 산술 예외는 결과 쓰기 없이 FAULT로 정지한다. ALU의 직접
특수 레지스터 갱신 경로는 유지하며 GET/PUT 버스는 전용 실행 enable로 제어한다.

fault_reason: bit 0=페치/데이터 접근, bit 1=미지원/무효 명령, bit 2=산술 trip.
오류 원인은 reset까지 유지한다.

검증 기록: 분기 추가 전 115개 지원 항목의 RTL은 프로그램/profile 4,864회와
CPU 7개 시나리오, ALU/디코더/캐시 검사 및 생성 소스 일치 검사를 통과했다.
현재 분기 구현과 공통 Condition 분리는 Verilog를 생성했으나 아직 시뮬레이션하지
않았다. 새 분기 프로그램과 명세는 test/cpu/instructions에 있다.

## 주소 생성과 특수 레지스터 접근

GETA/GETAB는 Branch의 RelativeAddress 가산기로 현재 명령 PC+offset을 계산한다.
`address_write_en`으로 결과를 $X에 쓰고 PC는 PC+4로 진행한다. 분기 taken은 발생하지 않는다.

GET/PUT/PUTI는 `special_get_en`/`special_put_en`으로 기존 특수 레지스터 버스를 제어한다.
GET은 읽기 버스 값을 GPR writeback mux에 전달하고, PUT은 $Z 또는 0 확장한 Z 바이트를
버스에 구동한다. 쓰기는 EXECUTE 완료 경계에서 한 번만 발생한다. 이 명령들과 GETA에서는
ALU execute_en을 차단해 사용하지 않는 ALU 결과나 rA/rH/rR 쓰기를 막는다.

CPU의 SpecialAccess는 실제 지원 레지스터와 rA/rG 쓰기 값을 decoded_valid에 반영한다.
미지원 시스템 레지스터나 무효 형식은 WAIT에서 fault_reason bit 1로 정지하며,
해당 명령의 EXECUTE나 레지스터 쓰기는 발생하지 않는다. rA의 V/D 이벤트 및 enable 비트는
GET/PUT 경로와 기존 ALU 경로에서 같은 저장 상태를 사용한다.

부분 메모리 접근은 폭과 확장 방식을 MemoryAccess에 전달한다. 일반 store는 기존 $X
읽기 단계를 사용하고 STCO는 X 바이트 상수 경로를 선택한다. 이번 확장은 시뮬레이션하지 않았다.