# 명령어 페치와 L1 I-cache

[캐시 구성](../cache/README.md)에 따라 32바이트 라인 512개(16KB)를 사용한다.
요청을 받은 클록에 캐시를 읽고, 다음 클록에 적중이면 AXI 읽기 없이 응답한다.
미스에서 명령어 8개를 함께 채운 뒤 캐시를 다시 읽어 응답한다.
응답 명령어는 캐시 읽기 결과에서 고르며 별도 응답 버퍼는 없다.
store 응답 때 Memory가 준 주소의 라인만 무효화한다.
아래 AXI 설명은 미스 경로에 해당한다.

`Cpu.axi`는 FPGA RAM 컨트롤러/AXI interconnect에 연결하는 AXI4 master다.
제공된 `mig_7series_0_stub.v`에 맞춰 주소 30비트, 데이터 256비트, ID 4비트다.
WSTRB는 32비트이며 MIG에 없는 ARREGION/AWREGION 포트는 두지 않는다.
현재 AR/R 채널만 사용한다.
fetch 자체는 읽기 전용이다. CPU의 데이터 접근 유닛이 AW/W/B를 사용한다.
AXI4-Lite/AXI-Stream 포트가 아니다.

## 제어유닛 연결

`Fetch.control`은 CPU 내부의 `Control.fetch`에 연결되어 있다.
Control은 PC의 주소를 요청하며 일반 명령 후 PC를 4 증가시킨다. HALT에서 정지한다.

- `req_pc`에 물리 바이트 주소를 넣고 `req_valid`를 올린다.
- `req_valid && req_ready`인 클록 상승 에지에서 주소가 수락된다.
- `res_valid`가 올라오면 `res_pc`, `instruction`, `error`가 유효하다.
- `res_valid && res_ready`인 클록 상승 에지에서 응답이 소비된다.
- `error=1`인 응답의 명령어는 실행하지 않고 제어유닛에서 예외 처리한다.

한 번에 한 요청만 처리한다. 요청 주소와 응답은 각각 수락될 때까지 유지한다.
PC 증가와 분기 대상 선택은 제어유닛 담당이다. 진행 중인 AXI 요청은 취소하지
않으므로 분기로 불필요해진 응답도 소비해서 버린 뒤 새 주소를 요청한다.

## 메모리 읽기

내부 PC는 MMIX의 64비트를 유지한다. PC 하위 2비트는 GO 목표에서 그대로 남을 수 있으며 명령어 선택에는
쓰지 않는다(MMIX 사양: GO 주소의 하위 2비트는 무시). PC 상위 [63:30]은 0이어야 한다. 위반하면 AXI 요청 없이 오류 응답한다.
주소를 잘라서 다른 주소로 접근하지 않는다. 이는 AXI 포트의 주소 범위 검사이며,
실제 장착된 RAM 용량과 주소 맵은 별도로 FPGA 구성에 따른다.
32바이트 정렬 주소에서 ARLEN=0, ARSIZE=5인 단일 beat를 읽고 PC의 [4:2]로
8개 중 하나의 명령어를 선택한다. 정상 응답의 라인 전체를 캐시에 채워 인접 명령어에 재사용한다.
AXI RDATA의 lane 0은 가장 낮은 바이트 주소이며, MMIX big-endian 바이트 순서로
명령어를 조립한다. instruction[24..32]=OP, [16..24]=X, [8..16]=Y, [0..8]=Z.
RAM에는 프로그램의 원래 바이트 순서로 적재해야 한다.

정상 응답은 RID=0, RRESP=OKAY, RLAST=1이다. 다른 응답은 오류로 보고한다.
예상과 달리 여러 beat가 오면 RLAST까지 소진하고 오류 응답한다.
응답이 아예 오지 않으면 대기하며 타임아웃 회로는 없다.

## FPGA 연결 조건

- `Cpu.clock`에 MIG의 `ui_clk`를 연결한다.
- `Cpu.init_calib_complete`에 MIG의 `init_calib_complete`를 연결한다.
  초기화 완료 전에는 새 요청을 받지 않는다. 진행 중 이 신호가 내려가도
  이미 발행한 ARVALID와 응답 수신은 유지한다.
- `Cpu.reset`은 active-high이고 최소 한 번의 클록 상승 에지를 포함해야 한다.
  AXI RAM/interconnect와 함께 리셋해야 하며, CPU만 리셋해서 진행 중인 응답을 잃지 않도록 한다.
  FPGA 래퍼에서 `ui_clk_sync_rst` 및 MIG AXI `aresetn`의 비활성 상태를
  반영해 CPU reset을 만든다. `aresetn`은 MIG의 입력이며 CPU 출력이 아니다.
  reset 해제는 `ui_clk`에 동기화한다. calibration 대기는 reset과 별개다.
- `Cpu.axi.read.*`는 MIG의 `s_axi_ar*`, `s_axi_r*`에,
  `Cpu.axi.write.*`는 `s_axi_aw*`, `s_axi_w*`, `s_axi_b*`에 대응시킨다.
  주소/데이터/ID 폭 변환기는 필요하지 않다.
- MIG의 DDR3 핀, `sys_clk_p/n`, `sys_rst`, 유지보수 입력 `app_*_req`와
  상태 출력들은 FPGA 래퍼에서 연결한다. CPU의 AXI 인터페이스에 포함하지 않는다.
- ARPROT는 unprivileged/secure/instruction으로 고정했다. FPGA 주소 맵과 접근 권한을 이에 맞춘다.
- CPU 내부 ReadSelect가 명령어 fetch와 데이터 읽기를 선택한다.
  제어유닛이 트랜잭션 종료까지 선택을 유지하는 직렬 구조이며 여러 outstanding은 지원하지 않는다.

프로토콜 참고: [Arm AMBA AXI specification](https://developer.arm.com/-/media/Arm%20Developer%20Community/PDF/IHI0022H_amba_axi_protocol_spec.pdf).
