# L1 명령어·데이터 캐시

## 구성

| 항목 | I-cache | D-cache |
|---|---|---|
| 캐시라인 | 32바이트 = 4바이트 명령어 8개 | 32바이트 = 64비트 데이터 4개 |
| 라인 수 | 512개 | 512개 |
| 데이터 용량 | 16KB | 16KB |
| 매핑 | 직접 사상 | 직접 사상 |
| 채우기 | AXI에서 256비트 한 beat | AXI에서 256비트 한 beat |
| 쓰기 | CPU store 응답 때 그 주소의 라인만 무효화 | write-through / no-write-allocate |

두 캐시는 같은 LineCache 소자를 각각 하나씩 쓰며 상태를 공유하지 않는다.

30비트 RAM 주소에서 offset=[4:0], index=[13:5], tag=[29:14]이다.

| 저장 | 소자 | 내용 |
|---|---|---|
| 데이터 | `SyncRam<512,256,32>` | 라인 512개, 바이트 단위 쓰기 레인 32개 |
| tag | `SyncRam<512,17,1>` | [15:0] tag, [16] valid |

데이터와 tag 모두 블록 RAM에 두어 라인 선택 mux와 라인별 플립플롭을 쓰지 않는다.
LUT는 tag 비교, 쓰기 레인 선택, index 선택, reset 정리 카운터에만 쓴다.
같은 index의 다른 tag가 들어오면 그 라인을 교체한다.

## 조회

블록 RAM은 동기 읽기다. `read=1`인 클록에 `read_address`의 라인을 읽고 다음 클록부터
`line`과 tag가 나온다. `read=0`인 동안 마지막 읽기 결과를 그대로 유지한다.
`hit`는 유지 중인 결과의 valid·tag를 `compare_address`와 비교한다.

블록 RAM은 같은 클록에 쓴 칸을 읽으면 쓰기 전 값을 낸다. 그래서 읽는 클록에
캐시 쓰기(fill, store, invalidate)가 있었으면 그 조회는 miss로 본다.

## reset

블록 RAM 내용은 reset으로 지워지지 않는다. reset 뒤 512클록 동안 index 0부터
한 클록에 하나씩 valid를 지우며 그동안 `ready=0`이다. Fetch와 MemoryAccess는
`ready=0`인 동안 요청을 받지 않는다.

## 쓰기

| 입력 | 동작 |
|---|---|
| `fill_en` | `write_address` 라인 전체를 `write_data`로 채우고 tag와 valid=1을 쓴다 |
| `store_en` | `hit`일 때만 `store_mask` 바이트를 `write_data`로 바꾼다. tag·valid는 그대로 |
| `invalidate` | `invalidate_address` 라인의 valid를 지운다. 같은 클록의 fill보다 우선한다 |

invalidate와 fill이 겹치면 데이터도 쓰지 않는다. tag RAM 쓰기 포트가 하나이기 때문이며,
채우지 못한 라인은 다음 조회에서 miss가 되어 다시 읽는다.

## 사용 방식

Fetch와 MemoryAccess는 같은 순서로 캐시를 쓴다.

1. 요청을 받은 클록에 요청 주소로 캐시를 읽는다.
2. 다음 클록에 hit면 캐시 읽기 결과로 바로 응답한다. 받지 않으면 응답 유지 플래그를 세운다.
3. miss면 AXI로 라인을 읽는다. RLAST를 받은 정상 응답만 채운다.
4. 채운 다음 클록에 같은 주소로 다시 읽고, 그다음 클록에 hit로 응답한다.

응답 값은 항상 캐시 읽기 결과에서 고른다. 결과를 유지하는 동안 같은 캐시를 다시 읽지
않으므로 응답 버퍼를 따로 두지 않는다. RRESP/ID 오류나 잘못된 다중 beat 응답이면
채우지 않고 오류로 응답한다. 캐시 적중 경로에서도 주소 범위 검사는 유지한다.

데이터는 AXI byte lane 순서로 저장하고, CPU 값으로 선택할 때 big-endian 변환을 적용한다.
명령어는 주소 [4:2], octa는 [4:3]으로 라인 내 위치를 고른다.

## store

모든 store는 RAM에 쓴다. AW/W/B의 독립 핸드셰이크와 완료 대기를 유지한다.
D-cache는 store 요청을 받을 때도 그 주소의 라인을 읽어 두고, B 응답이 정상이면
동일 tag의 유효 라인일 때만 WSTRB 바이트를 갱신한다. 쓰기 미스는 라인을 채우지 않는다.
오류 응답이면 일부만 쓰였을 수 있는 그 라인을 무효화한다.
dirty bit, write-back, write buffer는 없다.

명령어와 데이터 주소 공간이 같으므로 store 응답 때(오류 응답 포함) 그 주소의 I-cache
라인을 무효화한다. 다른 라인은 유지한다. SYNCD/SYNCID와 DMA snoop은 없다.

## 확인 범위

의사코드를 Verilog로 변환했다. 캐시 단위 검사(`verification/verify_generated.py --only cache`)는
이 구조에 맞춰 작성했고 실행하지 않았다. Vivado 합성에서 블록 RAM으로 추론되는지,
타이밍, 보드 동작은 확인하지 않았다.
