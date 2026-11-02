# PC 기반 실행 제어

Control은 64비트 PC를 유지하며 reset vector 0에서 실행을 시작한다.
일반 명령의 저장이 끝나면 PC를 4 증가시키고 다음 명령을 요청한다.
실행 횟수나 HALT 주소를 고정하지 않는다.

| 단계 | 동작 |
|---|---|
| REQUEST | 현재 PC로 요청을 유지. MIG calibration 완료 후 Fetch가 수락 |
| WAIT | 응답 대기. 정상 명령은 응답 버퍼에 유지 |
| EXECUTE | ALU 명령은 저장 후 PC+4. 메모리 명령은 주소를 캡처. HALT는 HALTED |
| MEMORY_REQUEST | load/store 요청 수락 대기. store는 읽기 포트 1을 X로 전환 |
| MEMORY_WAIT | AXI 데이터 또는 쓰기 응답 대기. 오류면 FAULT |
| MEMORY_COMMIT | load 결과 저장 또는 store 완료 후 PC+4, REQUEST로 복귀 |
| HALTED | 추가 요청과 쓰기 없이 reset까지 정지. done=1 |
| FAULT | 오류 정지. 추가 요청과 쓰기 없이 reset까지 정지 |

PC는 일반 명령이 정상 실행된 에지에서만 갱신한다. HALT/FAULT에서는 해당 명령
주소를 유지한다. 분기 추가 시 pc_add.sum 앞에 다음 PC 선택 회로를 추가할 수 있다.
명령어는 Fetch 버퍼가 유지하고 EXECUTE 종료 에지에서 소비한다.

메모리 명령은 MEMORY_COMMIT까지 명령어를 유지한다. load는 이 단계에서 범용
레지스터에 쓰고, store는 AXI B 응답이 완료된 뒤 이 단계로 진입한다.
메모리 접근 오류도 fault_reason[0]으로 기록한다. 연결은 memory/README.md를 참고한다.

HALT는 MMIX 도구에서 사용하는 종료 요청 TRAP 0,Halt,0(#00000000)으로 해석한다.
별도 MMIX opcode를 만들거나 SWYM 등 다른 명령을 덮어쓰지 않는다.
이는 현재 런타임의 직접 정지 동작이며 일반 TRAP/운영체제 예외 진입 구현은 아니다.
다른 TRAP 인코딩은 미지원이다.

fault_reason[0]은 페치 오류, [1]은 미지원/유효하지 않은 명령, [2]는 trip이다.
오류 원인은 reset까지 유지한다. AXI 응답이 없으면 WAIT에서 대기한다.
GET/PUT 버스만 비활성화했으며 ALU의 rA/rH/rR 직접 경로는 유지한다.
trip은 현재 결과 쓰기 없이 FAULT로 정지한다.

## ADD 다음 HALT 프로그램

FPGA 쪽에서 RAM에 아래 바이트를 순서대로 적재한다.

| 바이트 주소 | 명령 | 바이트(OP X Y Z) |
|---|---|---|
| 0 | ADD $1,$2,$3 | 20 01 02 03 |
| 4 | TRAP 0,Halt,0 | 00 00 00 00 |

CPU는 ADD 저장 후 PC=4로 진행하고, 두 번째 명령을 해석해 정지한다.
같은 32바이트 블록이라도 캐시가 없으므로 두 번째 명령에 대해 다시 AXI 읽기를 수행한다.
리셋 후 $2=$3=0이므로 $1에는 0이 저장된다. 비영 초기값 주입은 추가하지 않았다.
ADD를 여러 개 배치해도 HALT 전까지 순서대로 실행한다.

CPU의 AXI 쓰기 채널은 비활성 상태이며 프로그램 적재는 외부에서 수행한다.
보드의 클록/리셋/calibration 연결은 fetch/README.md를 따른다.

프로토콜 관례: [MMIX Sources](https://mmix.cs.hm.edu/src/index.html).
회로 의사코드이며 RTL 변환/합성/시뮬레이션/보드 동작 확인은 수행하지 않았다.
