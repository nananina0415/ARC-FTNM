# 데이터 접근과 L1 D-cache

현재는 [캐시 구성](../cache/README.md)에 따라 32바이트 라인 8개를 사용한다.
읽기 적중은 캐시에서 응답하고, 미스는 아래 AXI 경로로 채운다.
쓰기는 write-through/no-write-allocate이며 정상 B 응답 후 적중 라인의 바이트를 갱신한다.

MemoryAccess는 주소·store 여부·데이터를 ready/valid로 받아 한 번에 요청 하나를 처리한다.
64비트 octa load/store를 지원하며 CPU의 주소 계산·명령어 해독과 분리되어 있다.
주소나 실행할 명령 수, 예제 데이터는 하드웨어에 고정하지 않는다.

LDO/LDOU 및 즉치형 #8C~#8F, STO/STOU 및 즉치형 #AC~#AF를 디코더에 추가했다.
주소는 $Y+$Z 또는 $Y+Z이며 MMIX 규칙에 따라 8바이트로 내림 정렬한다.
상위 주소 [63:30]이 0이 아니면 AXI 요청 없이 오류 응답한다.

MIG의 256비트 인터페이스에서 32바이트 beat 하나를 읽고 주소 [4:3]으로 octa를 선택한다.
MMIX big-endian과 AXI byte lane의 차이를 읽기/쓰기 양쪽에서 변환한다.
쓰기는 32바이트 정렬 주소에 full-width beat를 보내되 WSTRB의 해당 8바이트만 활성화한다.
따라서 다른 octa를 덮어쓰거나 read-modify-write로 대체하지 않는다.

AW와 W는 독립적으로 수락될 때까지 각각 VALID와 payload를 유지한다.
둘 다 수락된 뒤 B 응답을 기다린다. CPU는 B 응답 완료 전에 다음 명령으로 넘어가지 않는다.
RRESP/BRESP 오류와 잘못된 ID는 오류 응답하며, 읽기는 RLAST까지 소진한다.

ReadSelect는 직렬 제어유닛의 단계 선택을 따른다. 일반적인 여러 outstanding 요청의
round-robin arbiter는 아니다. 제어유닛은 fetch 응답 수신을 마친 뒤 데이터 접근을
시작하고, 데이터 응답을 마친 뒤 fetch로 돌아가므로 트랜잭션 중 선택을 바꾸지 않는다.
캐시나 여러 outstanding 요청을 추가할 때는 명시적 arbiter로 확장해야 한다.

STO는 세 레지스터를 필요로 할 수 있으므로 기존 읽기 포트 두 개를 단계별로 재사용한다.
EXECUTE에서 Y/Z로 계산한 주소를 저장하고 MEMORY_REQUEST에서 읽기 포트 1을 X로 전환한다.
요청 수락 시 MemoryAccess가 주소와 store 데이터를 함께 보관한다.
load 완료 시 별도 writeback mux를 통해 목적지 X에 저장한다. store는 범용 레지스터에 쓰지 않는다.

데이터 접근 오류는 현재 fault_reason bit 0으로 정지한다. 실제 예외 처리기 진입,
byte/wyde/tetra 접근, MMU, 타임아웃, 다중 outstanding은 아직 구현하지 않았다.
