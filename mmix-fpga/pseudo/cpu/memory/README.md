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
캡처하므로 AXI 대기 중에도 payload가 유지된다.

[캐시 구성](../cache/README.md)은 32바이트 라인 512개(16KB)다. 요청을 받은 클록에
캐시를 읽고, load는 다음 클록에 적중이면 바로 응답한다. 미스는 AXI에서 라인을 채운 뒤
캐시를 다시 읽어 응답한다. load 값과 `line_read_data`는 캐시 읽기 결과를 LoadLane과
LineSwap에 통과시킨 값이며 별도 응답 레지스터는 없다.
쓰기는 write-through/no-write-allocate이며 정상 B 응답 뒤 적중 라인의 해당 바이트만
갱신한다. 오류 B 응답이면 그 라인을 무효화한다. store 응답 때 그 주소를
`instruction_invalidate_address`로 내보내 I-cache의 같은 라인을 무효화한다.

MIG의 256비트 AXI 포트에는 32바이트 정렬 주소와 full-width 단일 beat를 보낸다.
store는 해당 바이트만 WSTRB로 활성화하며, read-modify-write를 하지 않는다.
AW와 W는 독립적으로 수락될 때까지 VALID와 payload를 유지하고 이후 B 응답을 기다린다.
잘못된 응답 ID와 RRESP/BRESP는 오류로 처리하며 읽기는 RLAST까지 소비한다.

레지스터 스택 spill/fill은 `line` 경로를 쓴다. 요청 하나가 32바이트 라인 안의 octa 여러 개를
옮긴다. `line_write_data`의 octa j(비트 64j부터)는 MMIX 값 그대로이며 `line.pseudo`의
`LineSwap`이 byte lane 순서로 바꾼다. 쓰기는 `line_mask`에서 고른 octa의 8바이트씩만
WSTRB로 활성화하고 캐시도 같은 마스크로 갱신한다. 읽기는 라인 전체를 같은 형식으로
`line_read_data`에 돌려준다. 일반 load/store 경로는 바뀌지 않는다.

Control은 파이프라인 EX 단계에서 요청하고 WB 단계에서 응답을 받는다. 일반 store 데이터 $X는
레지스터 읽기 포트 x에서 오고, STCO는 별도 상수 입력을 선택한다. load 결과만 WB에서
GPR에 쓰며, store는 GPR을 쓰지 않는다. 데이터 오류는 fault_reason bit 0이다.

`ReadSelect`는 명령어 페치와 데이터 읽기의 AXI 읽기를 중재한다. 데이터 쪽이 우선이고
ARVALID를 낸 클록부터 RLAST 응답까지 같은 쪽을 유지한다. 여러 outstanding 요청은 지원하지 않는다.
MMU, MMIO 주소 변환, 타임아웃은 미구현이다.

현재 폭 확장 경로는 Verilog를 생성했으며 시뮬레이션은 실행하지 않았다.
