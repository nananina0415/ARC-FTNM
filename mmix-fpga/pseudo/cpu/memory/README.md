# 데이터 접근과 L1 D-cache

`MemoryAccess`는 주소·접근 폭·부호 확장 여부·store 데이터의 ready/valid 요청을 받아
한 번에 하나의 접근을 처리한다. 명령어 해석과 주소 계산은 CPU/Control이 담당한다.
특정 프로그램의 주소나 실행 횟수는 회로에 넣지 않는다.

| 명령 | 동작 |
|---|---|
| LDB/LDBU, LDW/LDWU, LDT/LDTU, LDO/LDOU 및 즉치형 | 1/2/4/8바이트 읽기, signed는 부호 확장, unsigned는 0 확장 |
| STBU/STWU/STTU, STO/STOU 및 즉치형 | 하위 1/2/4/8바이트 저장 |
| STCO/STCOI | X 필드의 unsigned 8비트 상수를 64비트로 0 확장해 octa 저장 |

주소는 `$Y+$Z` 또는 `$Y+Z`의 64비트 합이다. MMIX 규칙에 따라 접근 폭으로
내림 정렬한다. 상위 주소 [63:30]이 0이 아니면 AXI 요청 없이 접근 오류를 반환한다.
signed 부분 저장 STB/STW/STT의 overflow/V 처리는 아직 구현하지 않았다.

`lane.pseudo`의 `AlignLane`, `LoadLane`, `StoreLane`이 정렬, MMIX big-endian 변환,
부호 확장, byte lane과 WSTRB 생성을 맡는다. 크기 입력은 log2(bytes), 즉 0/1/2/3이다.
정렬된 접근은 32바이트 라인을 넘지 않는다. 요청 수락 때 주소·크기·확장 방식·데이터를
캡처하므로 AXI 대기 중에도 payload가 유지된다. 캐시 적중 경로도 같은 LoadLane을 사용한다.

[캐시 구성](../cache/README.md)은 32바이트 라인 8개다. 읽기 적중은 캐시에서 응답하고
미스는 AXI에서 라인을 채운다. 쓰기는 write-through/no-write-allocate이며 정상 B 응답 뒤
적중 라인의 해당 바이트만 갱신한다. CPU는 store 응답 뒤 I-cache를 무효화한다.

MIG의 256비트 AXI 포트에는 32바이트 정렬 주소와 full-width 단일 beat를 보낸다.
store는 해당 바이트만 WSTRB로 활성화하며, read-modify-write를 하지 않는다.
AW와 W는 독립적으로 수락될 때까지 VALID와 payload를 유지하고 이후 B 응답을 기다린다.
잘못된 응답 ID와 RRESP/BRESP는 오류로 처리하며 읽기는 RLAST까지 소비한다.

Control은 EXECUTE에서 주소를 캡처하고 MEMORY_REQUEST에서 일반 store의 읽기 포트 1을
$X로 전환한다. STCO는 별도 상수 입력을 선택한다. load 결과만 MEMORY_COMMIT에서
GPR에 쓰며, store는 GPR을 쓰지 않는다. 데이터 오류는 fault_reason bit 0이다.

AXI 읽기 선택기는 직렬 Control 단계에 따른다. 여러 outstanding 요청을 지원하려면
명시적인 arbiter와 응답 추적이 필요하다. MMU, MMIO 주소 변환, 타임아웃은 미구현이다.

현재 폭 확장 경로는 Verilog를 생성했으며 시뮬레이션은 실행하지 않았다.
