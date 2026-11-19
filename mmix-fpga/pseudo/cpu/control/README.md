# 파이프라인 제어

Control은 7단계 파이프라인 IF → ID → RF → PRE → EX → POST → WB의 진행/멈춤/비움을 정한다.
reset vector 0에서 시작하며 HALT와 FAULT에서는 reset까지 정지한 명령의 PC를 `pc`에 유지한다.

| 단계 | 위치 | 동작 |
|---|---|---|
| IF | Fetch | 요청 PC로 I-cache를 읽는다. 적중이면 다음 클록에 응답한다 |
| ID | Fetch 응답 + CPU의 ID 해독기 | 레지스터 읽기 번호를 낸다. 분기 예측으로 다음 요청 주소를 정한다 |
| RF | `rf_*` | 레지스터 블록 RAM 값이 나온다. WB 값을 전달받아 PRE 피연산자에 저장한다 |
| PRE(연산 전처리) | `pre_*`, `pv_*`, CPU의 PRE 해독기 | 해독, 단독 실행·load-use·미지원 판정, 피연산자 수집과 즉치 선택, EX 피연산자의 전달 경로 선택 |
| EX(연산) | `ex_*`, ALU | ALU, 유효 주소, 분기 조건과 목표, PC+4. PRE가 담아 둔 레지스터 값으로 연산만 한다 |
| POST(연산 후처리) | `post_*`, CPU의 POST 해독기 | rA·rH·rR·rJ·특수 레지스터 쓰기, 레지스터 스택 명령, load/store 요청, 예측 실패·trip 판정, fault·HALT·방향 전환 |
| WB | `wb_*` | 레지스터 쓰기. load·store는 메모리 응답을 여기서 기다린다 |

뒤 단계가 비지 않으면 앞 단계는 멈춘다. WB가 메모리 응답을 기다리면 POST가 멈추고,
POST가 멈추면 EX, PRE, RF, ID(Fetch 응답) 순서로 멈춘다. MUL·DIV는 ALU 결과가 나올 때까지(`alu.ready`) EX에 머문다.

## 책임 분리: EX는 연산만

EX는 상태를 쓰지 않고 판정도 하지 않는다. EX의 입력(ALU 선택, 피연산자, 분기 조건·오프셋·기준 주소)은
모두 PRE가 담아 둔 레지스터 값이다. EX 피연산자 앞에는 바로 앞 명령의 POST 값을 고르는 2:1 선택만 있고,
그 선택 신호(`ex_fwd*`)도 PRE가 정해 둔 레지스터 값과 `post_write`의 AND다.

- PRE: 명령을 해독하고 단독 실행·load-use·미지원(DIVU의 rD ≠ 0)을 판정한다. 피연산자를 모으고 즉치를 고른다.
- EX: 결과 값, V·D 이벤트, rH·rR 값, 분기 조건 결과와 목표, 유효 주소를 POST로 넘긴다.
- POST: 받은 결과로 상태를 쓰고 fault·HALT·방향 전환을 처리한다. 앞 명령(WB)이 끝나는 클록에만 처리하므로
  WB의 데이터 접근 오류가 뒤 명령보다 먼저 정지시킨다.
- POST가 방향을 바꾸거나 정지하는 클록에 EX·PRE·RF·ID의 뒤 명령은 `kill`로 비운다.
  EX·PRE는 부수효과가 없으므로 비우기만 하면 된다.

## 분기 예측과 방향 전환

ID에서 PB*(opcode bit 4)와 JMP는 taken으로 예측해 ID 명령 PC + 오프셋을 바로 요청한다.
B*와 그 밖의 명령은 ID 명령 PC + 4를 요청한다. ID 명령을 RF로 넘기는 클록에 다음 주소를
요청하므로 taken 예측에도 버리는 명령이 없다.

POST가 다음 경우를 판정하고, 그 명령이 POST를 떠나는 클록에 방향을 바꾸며 EX·PRE·RF·ID를 비운다.
예측 실패는 EX가 낸 분기 조건 결과(`post_taken`)와 예측(PB*·JMP는 taken)을 POST에서 비교한다.

| 경우 | 새 주소 |
|---|---|
| B*/PB* 예측 실패 | taken이면 목표, 아니면 PC+4 |
| GO | $Y+$Z |
| 단독 실행 명령 완료 | PUSHJ·POP은 상대 목표, PUSHGO는 $Y+$Z, TRAP은 rT, 그 밖은 PC+4 |
| StackControl retry(L을 늘린 뒤 다시 실행) | 같은 명령 |

조건 분기는 읽기 포트 r1로 $X를 읽는다. JMP는 XYZ 전체를 오프셋으로 쓰며 조건은 검사하지 않는다.
앞/뒤 방향은 opcode bit 0으로 해석하고 기준 주소는 분기 명령의 PC다. 목표 덧셈은 ALU와 분리한
`RelativeAddress`로 한다(ID용과 EX용 각각). EX의 `Branch` 안에 `condition::Condition`이 있고 ALU의
`Cond`도 같은 정의를 별도로 인스턴스화한다. 기준 주소는 PRE가 고른다(PUSHJ는 현재 PC, POP은 rJ).
rJ는 PUSH(단독 실행)만 바꾸고 그 뒤 명령은 비우므로 PRE에서 읽은 값이 실행 시점의 값이다.

## 데이터 위험

레지스터 번호는 논리 번호로 비교한다. α, L, G를 바꾸는 명령은 모두 단독 실행하고 뒤 명령을 비우므로
파이프라인 안의 명령끼리는 번호와 슬롯의 대응이 같다.

PRE → EX로 넘기는 클록을 기준으로 앞 명령의 위치에 따라 처리한다.

| 앞 명령의 위치 | 처리 |
|---|---|
| EX (바로 앞) | `ex_fwd*`를 담아 두고, EX에서 그 명령의 POST 값(`post_value`)을 쓴다 |
| POST | PRE → EX로 넘길 때 POST 값을 저장한다 |
| WB | PRE → EX로 넘길 때 WB 값(load 응답 포함)을 저장한다 |
| 그보다 앞 | RF → PRE로 넘길 때와 PRE에 머무는 동안 WB 값을 받아 둔다. 더 앞은 Registers가 직전 클록 쓰기 값을 낸다(reg/README.md) |

- 레지스터 읽기 번호는 다음 클록에 RF에 있을 명령의 것을 낸다. RF가 멈추면 RF 명령 번호로 다시 읽는다.
- 같은 번호를 여러 앞 명령이 쓰면 가까운 쪽(EX > POST > WB) 값을 쓴다. 거짓 조건의 CS*처럼 실제로
  쓰지 않는 명령은 `post_write`/`wb_write`가 0이라 그 앞 값이 남는다.
- load 결과는 WB에서 나오므로, EX·POST의 load나 응답을 받지 못한 WB의 load가 쓰는 번호를 읽는
  PRE 명령은 기다린다(load-use). load 바로 뒤 명령은 2클록 멈춘다.
  비교 대상은 읽기 포트 r1, r2 번호와 일반 store의 $X다.

## 단독 실행

GET, PUT, HALT와 `StackRequired`가 판정한 명령(PUSHJ/PUSHGO, POP, PUT rG, SAVE, UNSAVE, TRAP,
marginal `$X`에 써서 L을 늘리는 명령)은 POST에서 단독으로 실행한다. 판정은 PRE가 한다.
L·G를 바꾸는 명령은 모두 단독 실행이고 끝나면 뒤 명령을 비우므로, PRE에서 읽은 rL·rG로 판정해도 된다.

1. PRE에서 앞 명령이 모두 끝날 때(EX·POST·WB가 빌 때)까지 기다린 뒤 EX를 지나 POST로 간다.
   POST에 있는 동안 레지스터 읽기 포트와 특수 레지스터 번호는 POST 명령의 것이다.
2. POST의 첫 클록은 읽기(블록 RAM 동기 읽기)만 하고 다음 클록부터 실행한다(`post_read`).
3. GET/PUT은 그 클록에 끝난다. 레지스터 스택 명령은 StackControl을 시작하고 retire/retry/fault까지 기다린다.
4. 끝나면 뒤 명령을 비우고 다음 주소부터 다시 페치한다. retry면 같은 명령부터 다시 페치한다.

GET 값과 SAVE의 $X는 POST가 WB로 넘겨 명령의 레지스터 쓰기로 관찰된다.
L 증가의 0 쓰기, hole, fill 같은 내부 쓰기는 StackControl이 직접 한다.

## 레지스터 스택

슬롯 구조는 [reg/README.md](../reg/README.md)를 따른다.

레지스터는 블록 RAM 동기 읽기라 StackControl이 슬롯을 직접 지정해 읽는 단계(spill 모으기,
POP_READ, POP_HOLE, UNSAVE_HOLE, 글로벌·특수 레지스터 SAVE, TRAP의 $Y·$255)는 Registers의
`r1_valid`/`special.valid`가 1인 클록에만 진행한다. 번호가 바뀐 첫 클록에는 한 클록 기다린다.

| StackControl 단계 | 동작 |
|---|---|
| GROW, CHECK | `l[α+L]←0`, L+1. `(미저장 칸 수 + L) mod 256 = 0`이면 SPILL. 끝나면 retry |
| SPILL_GATHER, SPILL_REQUEST, SPILL_WAIT | n = min(미저장 칸 수, 4 − (rS/8 mod 4))칸을 한 클록에 하나씩 모아 `M8[rS..rS+8n)←l[γ..γ+n)`을 라인 쓰기 1회로, rS+8n |
| PUSH_COMMIT | `l[α+n]←n`, rJ←PC+4, L−(n+1), rO+8(n+1), PC←목표 |
| POP_READ, POP_BASE, POP_HOLE | y 읽기, 빈 ring이면 FILL, hole에서 k 읽기, 미저장 칸 수 <= k이면 FILL |
| FILL_ADDRESS, FILL_REQUEST, FILL_WAIT | m칸: rS−8m, 라인 읽기 1회 뒤 한 클록에 하나씩 `l[γ..γ+m)←M8[rS..rS+8m)`. POP은 m = min(rS 앞 라인 칸 수(rS 정렬이면 4), ring 빈칸), UNSAVE는 1 |
| POP_COMMIT | `L←min(k+X',G)`, L>k이면 `l[α-1]←y`, PC←rJ+4YZ, rO−8(k+1) |
| G_CLEAR, G_COMMIT | 새로 글로벌이 된 칸을 0으로, G 갱신 |
| SAVE_HOLE, SAVE_FLUSH | hole을 쓰고 L←0, 미저장 칸을 모두 SPILL |
| SAVE_REQUEST, SAVE_WAIT, SAVE_COMMIT | 글로벌, 특수 레지스터 12개, (rG,rA) 저장. 값이 최신인 클록에 `save_word`에 받아 두고 다음 클록부터 요청한다. rO←rS, $X←rS−8 |
| UNSAVE_BASE, UNSAVE_ADDRESS, UNSAVE_REQUEST, UNSAVE_WAIT | rS←($Z 정렬)+8, (rG,rA), 특수 레지스터, 글로벌 순서로 복원 |
| UNSAVE_HOLE, UNSAVE_LOCALS, UNSAVE_COMMIT | FILL로 hole과 로컬 k개를 읽고 rO←rS, L←min(k,G) |

특수 레지스터는 GET/PUT 버스로 옮기고 rG만 직접 경로를 쓴다.

X<G인 PUSH는 일반 목적지와 같이 marginal이면 GROW를 거친다. X>=G인 PUSH는 GROW를 한 번
거쳐 n=L-1로 hole을 쓴다. `$X`가 목적지인 명령은 조건이 거짓인 CS*여도 먼저 L을 늘린다.
spill/fill은 MemoryAccess의 라인 경로(32바이트 라인 안 octa 여러 개)를 사용하며 접근 오류는 fault_reason bit 0이다.
ring이 256칸이고 L<=G<=255라서 레지스터 스택에는 범위 fault가 없다.

## 메모리·정지·오류

load/store는 POST에서 MemoryAccess에 요청하고, 수락되면 WB로 넘어가 응답을 기다린다.
주소(`post_address`)와 store 데이터(`post_store_data`)는 EX가 넘긴 값이다.
store 완료에는 AXI B 응답이 필요하다. MemoryAccess는 한 번에 한 요청만 처리하므로 다음
메모리 명령은 앞 응답을 받은 뒤 요청한다. 일반 store 데이터 $X는 읽기 포트 x에서 오고
STCO는 X 바이트 상수를 쓴다. 부분 접근의 폭과 확장 방식은 MemoryAccess에 전달한다.

store는 I-cache의 같은 라인을 무효화하지만, 이미 페치해 파이프라인에 있는 뒤 명령은 바꾸지 않는다.
자기 수정 코드는 MMIX 사양대로 SYNCID 등으로 동기화해야 하며 SYNCID는 아직 구현하지 않았다.

HALT는 정확한 TRAP 0,Halt,0(#00000000) 런타임 관례다. 그 명령이 POST에서 실행되는 클록에 정지한다.
fault는 POST에서 처리한다. 페치 오류·무효 명령·DIVU의 rD ≠ 0은 PRE가 판정해 표시로 넘기고(그 명령은 EX에서
ALU를 돌리지 않는다), GET/PUT/SAVE의 허용 범위와 enable된 산술 trip, 레지스터 스택의 접근 오류는 POST가 판정한다.
데이터 접근 오류는 WB가 응답을 받을 때 난다.
rA/rH/rR 쓰기는 ALU 명령이 POST를 떠나는 클록에만 하고, fault인 명령은 쓰지 않는다.
뒤 명령은 POST가 처리하는 클록에 `kill`로 막히므로 fault 뒤 명령의 부수효과는 남지 않는다. `done`/`fault`는 진행 중인 페치 AXI 읽기가
끝난 뒤 1이 된다.

fault_reason: bit 0=페치/데이터 접근, bit 1=미지원/무효 명령, bit 2=산술 trip.
오류 원인은 reset까지 유지한다.

CPU의 SpecialAccess는 POST 명령의 번호와 값으로 실제 지원 레지스터와 rA/rG 쓰기 값을 판정한다(`post_allowed`).
미지원 시스템 레지스터나 무효 형식은 fault_reason bit 1로 정지하며 레지스터 쓰기는 없다.
rA/rH/rR 쓰기와 ALU 결과의 $X 쓰기는 PRE가 ALU 명령으로 분류한 명령(`post_alu`)에만 한다.

파이프라인 제어는 Verilog를 생성했으며 시뮬레이션은 실행하지 않았다.

## TRAP과 SWYM

TRAP은 StackControl의 TRAP_STEP에서 한 클록에 하나씩 수행한다.

| 단계 | 동작 |
|---|---|
| 0 | rWW←PC+4 |
| 1 | rXX←#80000000<<32 \| 명령어 |
| 2 | rYY←$Y (읽기 포트 1, 논리 번호) |
| 3 | rZZ←$Z |
| 4 | rBB←$255 (읽기 포트 1, 글로벌 슬롯) |
| 5 | $255←rJ (내부 쓰기) |
| 6 | rK←0, PC←rT, 명령 완료 |

특수 레지스터는 GET/PUT 버스로 쓴다. 레지스터 창과 L은 바꾸지 않으며 보존은 핸들러의
책임이다. RESUME은 아직 구현하지 않았으므로 핸들러에서 복귀할 수 없다.
SWYM은 X, Y, Z와 무관하게 아무 상태도 바꾸지 않고 PC+4로 진행한다. L도 늘리지 않는다.

## 주소 생성과 특수 레지스터 접근

GETA/GETAB는 RelativeAddress로 현재 명령 PC+offset을 계산해 `address_write_en`으로 $X에 쓴다.
GO는 $X에 PC+4를 쓰고 $Y+$Z(또는 $Y+Z)로 방향을 바꾼다. 주소 덧셈은 64비트 modulo 연산이며
산술 예외를 일으키지 않는다. 접근할 수 없는 목표는 그 주소의 페치 오류로 보고한다.

GET/PUT/PUTI는 `special_get_en`/`special_put_en`으로 특수 레지스터 버스를 제어한다.
GET은 POST에서 읽기 버스 값을 WB로 넘기고, PUT은 PRE가 고른 $Z 또는 0 확장한 Z 바이트를 버스에 구동한다.
rA의 V/D 이벤트 및 enable 비트는 GET/PUT 경로와 ALU 경로에서 같은 저장 상태를 사용한다.
