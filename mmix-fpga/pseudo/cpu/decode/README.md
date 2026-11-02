# 명령어 디코더

`InstructionDecode`는 조합회로이며 현재 구현된 ALU 명령 90개와 HALT 요청을 해석한다.

| opcode | 처리 |
|---|---|
| #18~#1F | MUL/MULU/DIV/DIVU 및 즉치형 |
| #20~#2F | ADD/ADDU/SUB/SUBU/2ADDU/4ADDU/8ADDU/16ADDU 및 즉치형 |
| #30~#37 | CMP/CMPU/NEG/NEGU 및 즉치형 |
| #38~#3F | SL/SLU/SR/SRU 및 즉치형 |
| #60~#7F | CS/ZS 각 8조건 및 즉치형 |
| #C0~#CF | OR/ORN/NOR/XOR/AND/ANDN/NAND/NXOR 및 즉치형 |
| #D8~#D9 | MUX/MUXI |
| #8C~#8F | LDO/LDOU 및 즉치형 |
| #AC~#AF | STO/STOU 및 즉치형 |

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

CPU에서는 페치 응답을 디코더에 연결하고 피연산자 선택, 레지스터 읽기/쓰기,
ALU 및 특수 레지스터 직접 배선을 연결했다. `Cpu.decoded`는 제어유닛용 출력이다.
내부 `Control.execute_en`은 제어유닛이 저장 단계에만 활성화하며, 디코더의
valid와 AND해 ALU의 write_en으로 보낸다. `Cpu.trip_req`도 valid로 한정한다.

현재 명령어는 페치 응답 버퍼가 유지한다. 제어유닛은 저장 단계가 끝날 때까지
`Fetch.control.res_ready=0`으로 명령어를 유지하고, 저장하는 에지에서 응답을
소비할 수 있다. execute_en을 여러 클록 동안 올려 두면 매 클록 쓰기가 발생하므로
저장 단계에서만 1로 만들어야 한다.

PC 진행과 GET/PUT은 디코더에 포함하지 않는다. 내부 Control이 PC+4 기반 연속 실행과
HALT/미지원 명령/페치 오류/trip의 정지를 담당한다. HALT에서는 execute_en을
활성화하지 않는다. 분기와 실제 예외 처리기 진입은 아직 구현하지 않았다.
