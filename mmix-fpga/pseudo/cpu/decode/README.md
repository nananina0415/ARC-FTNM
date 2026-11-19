# 명령어 디코더

`InstructionDecode`는 조합회로이며 ALU/상수 명령 106개, 메모리 명령 28개,
분기 명령 34개, GETA 2개, GET/PUT/PUTI 3개와 정확한 HALT까지 174개 항목을 해석한다.
상수 명령까지의 이전 RTL은 전체 4,864회 프로그램 검사를 통과했다.
분기 추가와 공통 조건 모듈 분리 이후의 RTL은 아직 시뮬레이션하지 않았다.

| opcode | 처리 |
|---|---|
| #18~#1F | MUL/MULU/DIV/DIVU 및 즉치형 |
| #20~#2F | ADD/ADDU/SUB/SUBU/2ADDU/4ADDU/8ADDU/16ADDU 및 즉치형 |
| #30~#37 | CMP/CMPU/NEG/NEGU 및 즉치형 |
| #38~#3F | SL/SLU/SR/SRU 및 즉치형 |
| #40~#5F | B*/PB* 8조건, 앞/뒤 분기 |
| #60~#7F | CS/ZS 각 8조건 및 즉치형 |
| #C0~#CF | OR/ORN/NOR/XOR/AND/ANDN/NAND/NXOR 및 즉치형 |
| #D8~#D9 | MUX/MUXI |
| #80~#8F | LDB/LDBU/LDW/LDWU/LDT/LDTU/LDO/LDOU 및 즉치형 |
| #A2~#A3, #A6~#A7, #AA~#AF | STBU/STWU/STTU/STO/STOU 및 즉치형 |
| #B4~#B5 | STCO/STCOI |
| #E0~#E3 | SETH/SETMH/SETML/SETL |
| #E4~#E7 | INCH/INCMH/INCML/INCL |
| #E8~#EB | ORH/ORMH/ORML/ORL |
| #EC~#EF | ANDNH/ANDNMH/ANDNML/ANDNL |
| #F0~#F1 | JMP/JMPB |
| #F4~#F5 | GETA/GETAB |
| #F6~#F7, #FE | PUT/PUTI, GET (지원 레지스터 제한은 아래 참조) |

메모리 명령은 memory/store 제어신호로 분리하며 주소 계산·AXI 요청은 데이터 접근 경로가
담당한다. store의 X는 목적지 쓰기 번호가 아니라 별도 단계의 데이터 읽기 번호로 재사용한다.

HALT는 MMIX 도구의 런타임 관례인 `TRAP 0,Halt,0`(32비트 값 #00000000)으로
해석하고 `halt=1`, `valid=1`을 출력한다. 일반 TRAP 처리기 구현과는 별개인 현재
런타임의 종료 동작이다. 다른 TRAP 인코딩은 아직 지원하지 않는다.
나머지 미지원 명령은 `unsupported=1`, `valid=0`으로 출력한다.
입력 `instruction_valid=0`이면 두 신호 모두 0이다. 미지원 명령은 Compare를
선택하고 쓰기를 차단한다. BDIF/SADD/MOR 등 미구현 비트 연산을 실행하지 않는다.

해독 결과는 ALU 선택/연산 번호, 레지스터 Y/Z/X 번호, 피연산자 즉치 선택과
0 확장된 Y/Z 즉치다. NEG/NEGU는 즉치형 여부와 무관하게 Y 필드를 즉치로 쓴다.
CS 조건 거짓의 쓰기 차단은 ALU의 `res_w_en`이 담당한다.

상수 명령의 YZ는 두 레지스터 번호가 아닌 하나의 unsigned 16비트 즉치다.
`WydeImmediate`는 opcode 하위 2비트로 YZ를 48/32/16/0비트 왼쪽에 배치하고
`imm_yz`로 내보낸다. 이 명령군에서는 `arg2_wyde=1`, `arg2_imm=0`이며,
홀수 opcode도 일반 Z-byte 즉치형으로 해석하지 않는다.

| 명령군 | 첫 피연산자 | 두 번째 피연산자 | ALU 선택/연산 |
|---|---|---|---|
| SET* | 0 (`arg1_zero=1`) | 위치 배치된 YZ | AddSub / ADDU |
| INC* | 기존 $X | 위치 배치된 YZ | AddSub / ADDU |
| OR* | 기존 $X | 위치 배치된 YZ | Bitwise / OR |
| ANDN* | 기존 $X | 위치 배치된 YZ | Bitwise / ANDN |

상수 명령은 읽기 포트 1에 X를 선택한다. SET은 나머지 세 wyde를 0으로 만들고,
INC는 전체 64비트 덧셈에서 carry를 전파하되 overflow를 무시한다.
이 명령들은 rA/rH/rR을 변경하지 않는다. CPU의 피연산자 mux만 확장하고 기존
ALU 결과 쓰기와 PC+4 제어를 그대로 사용한다. 별도 실행 단계는 추가하지 않았다.

예를 들어 아래 프로그램은 기존 $3 값에 의존하지 않고 64비트 상수를 만든다.

```text
SETH $3,#0123
ORMH $3,#4567
ORML $3,#89ab
ORL  $3,#cdef
```

상수 명령의 기준은 공식 MMIXware `mmix-doc.w`의 immediate-wyde 명세다.

분기 명령은 `branch=1`로 분류한다. 조건 분기의 읽기 포트 1은 $X를 선택하고,
`branch_condition`은 opcode 비트 1~3의 N/Z/P/OD/NN/NZ/NP/EV 번호다.
JMP는 `branch_unconditional=1`이며 XYZ 전체를 오프셋으로 쓴다.
분기에는 일반 Z-byte 즉치 선택을 적용하지 않는다.

`RelativeOffset`은 조건 분기의 YZ 16비트 또는 JMP의 XYZ 24비트를 바이트
오프셋으로 변환한다. 부호는 필드의 최상위 비트가 아닌 opcode bit 0이다.
앞 분기는 `4*raw`, 뒤 분기는 `4*(raw-2^width)`이며 이를 64비트로 확장한다.
예를 들어 앞 분기의 YZ=#8000은 +131072이고, 뒤 분기의 YZ=#0000은 -262144다.
기준 주소는 현재 명령 PC이며 PC+4가 아니다. 목표 주소 계산과 PC 갱신은
`Control.Branch` 및 Control의 PC 선택기가 담당한다.

PB*는 B*와 같은 조건과 목표 주소를 사용한다. 현재는 예측기를 구현하지 않았으며
예측 힌트에 따른 선행 페치나 시간 차이는 없다. GO 및 레지스터 스택 호출은
아직 해독하지 않는다.

CPU는 같은 디코더를 세 단계에 둔다. ID 해독기는 페치 응답에서 레지스터 읽기 번호와
분기 예측을, PRE 해독기는 PRE 명령에서 피연산자·즉치 선택과 EX 제어·판정을,
POST 해독기는 POST 명령에서 상태 쓰기·레지스터 스택·메모리 요청·정지를 정한다.
EX는 디코더를 쓰지 않고 PRE가 담아 둔 값으로 연산한다. `Cpu.decoded`는 PRE 해독 결과다.
`Cpu.trip_req`는 POST의 유효한 ALU 명령으로 한정한다.

PC 저장은 Control이 담당한다. 내부 Control이 순차/분기 PC 갱신과
HALT/미지원 명령/페치 오류/trip의 정지를 담당한다. rA·rH·rR과 ALU 결과 쓰기는
POST가 ALU 명령에만 한다. 실제 예외 처리기 진입은 아직 구현하지 않았다.

메모리 크기는 `memory_size`의 log2(bytes), signed/unsigned 선택은 `load_unsigned`로
전달한다. `store_constant`는 STCO에서 $X 대신 0 확장한 X 바이트를 선택한다.
STB/STW/STT의 signed overflow 처리는 미지원이다.

GETA는 분기와 같은 RelativeOffset과 Control의 주소 가산기를 재사용해 현재 PC 기준
주소를 $X에 쓴다. PC 자체는 PC+4로 진행하며 ALU 및 특수 레지스터 쓰기는 발생하지 않는다.

GET은 Y=0, Z<32, PUT/PUTI는 Y=0, X<32인 형식만 해석한다. GET은 특수 레지스터를
$X에 읽고 PUT은 $Z, PUTI는 0 확장한 Z 바이트를 쓴다. CPU의 `SpecialAccess`가
대상 레지스터와 rA 값의 유효성을 추가 검사한다. 디코더 단독 valid와 달리 CPU의
공개 decoded.valid에는 이 검사도 반영한다. 지원 대상은
`rB,rD,rE,rH,rJ,rM,rR,rBB,rA,rF,rP,rW,rX,rY,rZ,rWW,rXX,rYY,rZZ` 19개다.
시스템/스택 레지스터 8~20번은 미지원이며, rA는 기존 구현 비트 6/7/14/15만 허용한다.
그 밖의 비트를 쓰려는 PUT은 무효 명령 fault로 처리한다.

현재 분기 및 이번 확장 RTL은 생성만 했으며 시뮬레이션하지 않았다.