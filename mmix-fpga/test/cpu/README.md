# CPU 어셈블리 회귀 테스트 설계

상태: 명령어별 프로그램 테스트를 구현했다. 현재 184개 구현 항목(ALU/상수 106 opcode,
메모리 28 opcode, 분기 34 opcode, GETA 2 opcode, GO 2 opcode, GET/PUT 3 opcode, 레지스터 스택 7 opcode,
SWYM, HALT와 TRAP을 함께 다루는 #00)에 대해 3,819개 `.mms`/`.case.json` 쌍이 있다. TRAP/SWYM/GO와 spill/fill 묶음 전송
추가 후 현재 RTL은 아직 실행하지 않았다.
아래의 번호별 절은 확장 설계이며, 아직 구현하지 않은 기능은 아래 현황과 구분한다.

분기 추가 전, 상수 명령까지의 115개 항목 전체 실행(2026-11-02):
**2,432개 프로그램 × 2 profile = 4,864회 PASS,
0 FAIL, 필수 커버리지 누락 0**. 실제 실행 시간은 약 28분이었다.
실행 명령은 `python -m verification.cpu_suite --all --batch-size 512 --jobs 4`다.
ALU 7,380개 벡터, 디코더 520개 검사(전체 256 opcode), 캐시 검사,
CPU 7개 시나리오, 생성 소스 일치 검사(77개 산출물)도 통과했다.
상수 명령의 새 353개 프로그램은 두 profile에서 706회 모두 통과했다.
이 결과는 분기와 공통 조건 모듈을 추가하기 전의 RTL 결과이며,
분기 추가 후 현재 RTL의 검증 결과가 아니다.

분기 케이스는 B*/PB*/JMP의 앞/뒤 변형, 조건의 참/거짓, 0/부호/홀짝 경계,
16/24비트 오프셋 경계, 다음 명령/다음 캐시라인/충돌 index로의 이동,
목표 fetch 오류와 64비트 PC wraparound, counted loop를 다룬다.
범용·특수 레지스터 보존과 의도적으로 건너뛴 명령의 부작용도 비교한다.
`expect.pc`는 최종 PC를 검증하는 선택 항목이다. 잘못된 목표 주소가 같은 fetch
오류를 일으키더라도 올바른 주소 계산으로 오인하지 않도록 분기 케이스에 사용한다.
새 상태 덤프 header의 마지막 필드는 PC이며, 이전 9필드 덤프도 PC 기대값이
없는 기존 케이스에서는 읽을 수 있다.

상수 케이스는 16비트 즉치의 0/255/256/32767/32768/65535 경계,
기존 $X 읽기, Y/Z 바이트와 X 번호의 중복, SET의 나머지 비트 초기화,
INC의 wyde 사이 carry와 64비트 wraparound, 특수 레지스터 보존을 다룬다.
`seth.compose_64bit`는 SETH/ORMH/ORML/ORL을 연속 실행해 실제 프로그램 안에서
64비트 상수를 구성하는 사례다. 실행을 원할 때 사용할 명령은 다음과 같다.

```powershell
python -m verification.cpu_suite --instruction SETH --instruction SETMH --instruction SETML --instruction SETL --instruction INCH --instruction INCMH --instruction INCML --instruction INCL --instruction ORH --instruction ORMH --instruction ORML --instruction ORL --instruction ANDNH --instruction ANDNMH --instruction ANDNML --instruction ANDNL
```

## 레지스터 스택 케이스

물리 슬롯은 512개다. `l[i]`는 로컬 ring 슬롯 i(0~255), `g[k]`는 글로벌 $k의 슬롯(256+k)이다.

`initial.explicit_stack`이 없는 케이스는 레지스터를 `$k`로 적고 runner가 `rG=128`, `rL=127`을 주입한다.
rO=0이므로 `$0~$126`은 `l[0]~l[126]`, `$128~$255`는 `g[128]~g[255]`다. `$127`은 marginal이라 이 fixture의
프로그램에서 쓰지 않는다. `explicit_stack`이 참이면 케이스가 rG/rL/rO/rS를 직접 지정한다.

explicit_stack 케이스의 레지스터는 논리 번호가 아니라 물리 슬롯(`l[i]`, `g[k]`)으로 적고 최종 값을 비교한다. `stack_cases.py`의 프로그램과
`stack_model.py`의 독립 모델로 기대값을 만든다. 대상 PC의 `executions`는 그 명령이 실행된 횟수이므로
marginal `$X`로 L을 늘릴 때마다(retry 뒤 다시 실행) 1씩 늘어난다. 내부 0 쓰기, hole, fill은 `register_writes`에 세지
않고 spill은 `memory_writes`에 센다. spill은 32바이트 라인 안 최대 4칸을 쓰기 1회로 내보내므로
AXI 쓰기 트랜잭션 하나가 1로 세어진다([spill/fill 단위](../../pseudo/cpu/reg/README.md)).

## 실행 가능한 도구

프로젝트 루트에서 실행한다. 테스트는 사용자가 명시적으로 요청할 때만 실행한다.

```powershell
python -m verification.cpu_suite --all
python -m verification.cpu_suite --instruction ADD --instruction ADDI
python -m verification.cpu_suite --case div.divide_zero_enabled --profile backpressure
```

`--all`은 모든 케이스를 두 profile에서 실행하며, 지원 목록의 필수 경계 커버리지가
누락되어도 실패한다. 필터와 함께 쓸 수 없다. 기본 병렬성은 `--jobs 4`이며,
`--batch-size 64`를 조절할 수 있다. 예: 전체 실행은 `--batch-size 512`로 프로세스
시작 비용을 줄일 수 있다. 매 케이스 reset과 fixture 주입으로 상태를 초기화한다.
프로세스 격리가 필요하면 `--batch-size 1`을 사용한다.
필터 실행의 보고서에는 전체 목록 대비 누락도 남지만, `--all`이 아니면 선택하지
않은 명령어의 커버리지를 실패로 판정하지 않는다.

필요한 프로그램은 공식 MMIXAL과 Icarus Verilog(`iverilog`, `vvp`)다.
현재 로컬 기본 경로는 `.tools/mmix/mmixal.exe`,
`.tools/iverilog/app/bin/iverilog.exe`이며, `--mmixal`/`--iverilog`로 바꿀 수 있다.
Python runner는 표준 라이브러리만 사용한다. 경로에 공백이 있는 작업 폴더에서는
공백 없는 `--output` 경로를 지정한다(현재 simulator queue는 공백 구분이다).

MMIXAL은 [공식 MMIXware](https://cs.stanford.edu/~knuth/programs/mmix.tar.gz)의
`mmixal.w`와 `mmix-arith.w`를 CWEB `ctangle`로 변환하여 GCC로 빌드했다.
Windows에서는 빈 `empty.ch`를 명시해 `/dev/null` 기본 경로를 피했다.

```powershell
# .tools/mmix에서, CWEB ctangle과 GCC를 준비한 뒤:
../cweb/ctangle.exe mmix-arith.w empty.ch
../cweb/ctangle.exe mmixal.w empty.ch
gcc -O2 -std=gnu99 mmixal.c mmix-arith.c -o mmixal.exe
```

MMIXAL은 `ADDI`라는 철자를 받지 않으므로 즉치형 소스도 `ADD $3,$1,255`처럼 쓴다.
sidecar/catalog에서는 ADD와 ADDI를 구분한다. 생성된 MMO의 실제 명령어 바이트가
sidecar의 `target.word`와 일치해야 한다. 오류 후에도 부분 MMO를 만들 수 있어
MMIXAL의 종료 코드 외에 진단 메시지도 검사한다. 경고도 실패로 처리한다.

### 현재 검사 범위

- `.mms → MMIXAL → MMO v1 loader → RAM → 실제 생성 CPU RTL` 경로를 실행한다.
- fixture는 reset 후 실행 전에 플립플롭 저장 상태에 한 번 주입한다. 실행 중
  `force`나 기대값 주입은 없다. 대상 명령의 지원 여부와 무관하게 초기 상태는 fixture로 주입한다.
- GPR 슬롯 512개, 특수 레지스터 32개, 테스트 RAM 4,128바이트를 최종 덤프하여
  초기 상태와 명세의 차이만 반영한 기대 상태와 전부 비교한다.
- RAM은 `0..4095`와 마지막 32바이트 `0x3fffffe0..0x3fffffff`를 sparse mapping한다.
  초기화하지 않은 바이트는 A5다. rA는 구현된 비트 6/7/14/15만 주입할 수 있다.
- 대상 PC의 실행 횟수, GPR 쓰기 횟수, store와 B 응답 횟수,
  HALT/fault 및 원인, 정지 후 추가 활동도 검사한다. 기본 케이스는 대상 명령과
  HALT로 구성되며 연속 상수 구성과 분기/반복문 프로그램도 포함한다. 부작용 횟수는 프로그램
  전체를 대상으로 비교한다.
- 실행 횟수는 Control의 `executed`가 1이고 POST 명령 PC가 대상 PC인 클록 수다. 비워진(분기 예측 실패
  등으로 버려진) 명령은 POST에 도착하지 않으므로 세지 않는다. 단독 실행 명령은 시도마다 1,
  enable된 산술 trip은 1로 센다. 페치 오류와 무효 명령은 실행으로 세지 않는다.
- 최종 PC는 정지한 명령(HALT 또는 fault)의 PC다.
- `no_stall`은 READY를 즉시 제공하고 짧은 응답을 보낸다. `backpressure`는
  AR/AW/W에 서로 다른 주기(4/5/7)의 READY와 R/B 지연을 준다. VALID 정지 중
  주소·쓰기 데이터·strobe 유지, 단일 32바이트 AXI 전송 형식도 검사한다.
- 기대값 생성기 `verification/cpu_suite/reference.py`는 compiler/RTL을 호출하지 않는
  독립 산술 모델이다. runner는 이 모델을 호출하지 않고 저장된 명세만 판정한다.
  숫자가 크므로 64비트 값과 주소는 JSON 문자열로 기록한다.
- `generate_cases.py`는 저장된 케이스의 재생성 도구다. 기대값을 바꾸는 작업이므로
  일반 테스트 실행과 분리되어 있다. 정상 테스트는 기대값 파일을 수정하지 않는다.

결과는 기본 `build/test/cpu/`에 `results.json`, `junit.xml`, `report.html`로 저장한다.
개별 케이스에는 MMIXAL listing/MMO/log, profile별 초기 hex와 `actual.txt`가 남는다.
실제 상태의 X/Z, 불완전 덤프, 어셈블/적재 실패, timeout은 PASS가 될 수 없다.
RTL/testbench 해시, 어셈블러/소스 해시를 보고서에 남기며 과거 PASS를 재사용하지 않는다.

실행 시계 수를 기대값으로 고정하지 않는다. `limits.cycles`는 프로그램 종료까지의
상한이고, 그 뒤 정지 안정성 확인을 위해 10클럭을 더 관찰한다. wall timeout은 현재
batch 내 케이스별 `wall_seconds`의 최대값 × batch 크기다. 별도 프로세스 단위로
엄격히 적용하려면 `--batch-size 1`을 사용한다.

아직 이 runner에 없는 기능: seed 무작위 profile, AXI 오류 주입 profile, 실행 중
checkpoint/assertion, retire trace, `--trace`/실패 자동 재실행, integration/cache 전용
어셈블리 모음. 기존 `verification`의 모듈/캐시/메모리 프로그램 검사는 별도로 유지한다.
여기서 PASS는 현재 구현의 명령어 기능과 지정 경계의 통과이며 MMIX 전체 적합성이나
모든 64비트 입력의 완전 검증을 뜻하지 않는다. register stack과 실제 trip handler는
아직 없으며, enable된 산술 예외는 현재 CPU의 fault(원인 4)까지 확인한다.

## 1. 목표

명령어별 MMIX 어셈블리를 실제 생성 RTL에서 일괄 실행하고, 정상 결과뿐 아니라
경계값·특수 레지스터·조건부 쓰기·예외·메모리 부작용까지 검사한다.
테스트별 CPU 수정이나 특정 예제에 맞춘 RTL을 만들지 않는다.

각 케이스는 다음 세 요소로 구성한다.

1. `.mms`: 실행할 MMIX 어셈블리와 필요 시 코드/데이터 배치.
2. 같은 이름의 `.case.json`: 초기 상태·기대 결과·커버리지·종료 조건.
3. 공통 테스트벤치/runner: 이미지 적재, RTL 실행, 결과 수집과 판정.

기능 테스트와 지연/캐시 성능 테스트를 분리한다. 기능적으로 올바른 프로그램이
정확한 클록 수가 달라졌다는 이유로 실패해서는 안 된다.

## 2. 디렉터리

```text
test/cpu/
  README.md
  isa.json                    # 독립적으로 관리하는 지원 opcode/의미/필수 커버리지
  case.schema.json            # 케이스 메타데이터 형식
  profiles.json               # RAM 지연·역압·오류 주입 설정
  instructions/
    add/
      basic.mms
      basic.case.json
      signed_overflow.mms
      signed_overflow.case.json
      destination_alias.mms
      destination_alias.case.json
    addi/                     # 즉치형은 별도의 opcode로 관리
    addu/
    sub/
    neg/
    mul/
    mulu/
    div/
    divu/
    cmp/
    sl/
    csn/                      # 조건별 명령과 즉치형도 각각 집계
    ...
    ldo/
    stoi/
    halt/
  integration/
    load_add_store.mms
    load_add_store.case.json
    register_dependencies.mms
    register_dependencies.case.json
  cache/
    instruction_line.mms
    instruction_line.case.json
    data_same_line.mms
    data_same_line.case.json
    eight_resident_lines.mms
    eight_resident_lines.case.json
    conflict_replace.mms
    conflict_replace.case.json
    self_modifying_code.mms
    self_modifying_code.case.json
  faults/
    unsupported_opcode.mms
    unsupported_opcode.case.json
    ...
  generated/                  # seed 고정으로 생성한 경계 조합/난수 케이스

verification/cpu_suite/
  runner.py                   # 발견·선택·빌드·실행·집계
  assembler.py                # MMIXAL 호출과 tool/version 기록
  image.py                    # MMO 적재, 주소·초기 상태 정규화
  case.py                     # schema/manifest 검증
  rtl_adapter.py              # 논리적 CPU 상태 ↔ 생성 RTL 경로
  reference.py                # 독립적인 기대값 계산
  scoreboard.py               # 종료·상태·부작용·실행 커버리지 판정
  report.py                   # JSON/JUnit/HTML·기존 클록 뷰어 연결
  tb/
    cpu_suite_tb.sv           # 모든 케이스에 사용하는 고정 테스트벤치
    axi_ram_model.sv          # 바이트 단위 메모리와 독립 AW/W/B, AR/R 모델

build/test/cpu/
  <rtl-and-testbench-hash>/sim.vvp
  results.json
  junit.xml
  index.html
  instructions/add/signed_overflow/<profile>/
    program.mmo
    image.json
    initial-registers.hex
    expected.json
    actual.json
    retire.jsonl
    cycles.jsonl              # 요청하거나 실패를 재현할 때
    cycles.html
    wave.vcd
    assemble.log
    simulation.log
```

명령어 폴더마다 어셈블리를 둔다. register/immediate 형태는 별도 폴더로 두어
한쪽만 테스트했는데 두 opcode가 모두 검증된 것으로 집계하지 않는다.
생성물은 test/cpu에 섞지 않고 build 아래에 둔다.

## 3. 어셈블리 실행 경로

권장 경로:

```text
.mms + .case.json
  → 공식 MMIXAL로 .mmo 생성
  → MMO loader로 주소별 바이트/심벌/초기 상태 해석
  → 공통 RAM 모델과 초기화 어댑터에 공급
  → 동일한 sim.vvp를 케이스별 별도 프로세스로 실행
  → 실제 RTL 상태·명령 이벤트와 기대값 비교
```

MMIXAL을 사용하고 자체 축약 어셈블러를 처음부터 만들지 않는다. 프로그램을
손으로 opcode 배열로 다시 작성하는 현재 방식도 suite의 기본 경로에서 제거한다.
공식 MMIXware에는 어셈블러·시뮬레이터가 제공된다.
[MMIXware](https://cs.stanford.edu/~knuth/mmixware.html)

`.mmo`는 단순한 연속 바이트 파일이 아니다. LOC에 의한 주소 변경과 적재 레코드를
해석해야 한다. listing 텍스트를 긁어서 바이너리를 만들지 않는다. 필요하면
mmoimg를 연속 이미지 추출에 활용할 수 있지만, 높은 주소 사이의 빈 공간을
거대한 파일로 만드는 방식을 suite 전체의 기본값으로 삼지 않는다.
[MMO 이미지 도구](https://mmix.cs.hm.edu/tools/mmoimg/index.html)

loader에서 지원하지 않는 레코드는 명확한 오류로 중단한다. 무시하고 통과시키지 않는다.
MMO의 entry/초기 레지스터와 case의 명시적 설정이 충돌하면 오류로 처리한다.
현재 reset vector는 0이므로 초기 suite는 entry=0을 요구한다.

어셈블러의 의사 명령이 아직 미지원인 실제 명령으로 확장될 수 있으므로 실행한
opcode와 케이스의 target opcode를 확인한다. RAM의 데이터 바이트까지 코드라고
가정해 전체 이미지에 opcode whitelist를 적용하지 않는다.

## 4. 케이스 예시

`instructions/add/signed_overflow.mms`:

```text
        LOC 0
Main    ADD $3,$1,$2
        TRAP 0,Halt,0
```

`instructions/add/signed_overflow.case.json`의 제안 형식:

```json
{
  "schema": 1,
  "id": "add.signed_overflow.positive",
  "target": {"mnemonic": "ADD", "opcode": "0x20", "pc": "0x0", "executions": 1},
  "tags": ["arithmetic", "signed", "boundary", "rA"],
  "initial": {
    "entry": "0x0",
    "registers": {"$1": "0x7fffffffffffffff", "$2": "0x1", "$3": "0x1234"},
    "special": {"rA": "0x0"}
  },
  "expect": {
    "stop": "halt",
    "registers": {"$3": "0x8000000000000000"},
    "special": {"rA": "0x40"},
    "unchanged_registers_except": ["$3"],
    "unchanged_special_except": ["rA"],
    "memory_unchanged": true,
    "subject_effects": {"register_writes": 1, "memory_writes": 0}
  },
  "coverage": ["signed_positive_overflow", "rA.V_event", "register_form"],
  "profiles": ["no_stall", "backpressure", "random_seeded"],
  "limits": {"cycles": 2000, "wall_seconds": 30}
}
```

64비트 값은 JSON 숫자 대신 문자열로 표현한다.
객체 파일과 fixture 모두 초기값을 지정하지 않은 레지스터는 reset 값 0을 사용한다.
unchanged 비교 기준은 객체 파일과 fixture 적재를 마친 실제 초기 상태다. 기대값에 없는 레지스터를
전부 무시하지 않고 unchanged 규칙으로 의도하지 않은 변경도 검사한다.
초기화 명령을 프로그램에 포함했다면 그 명령이 변경하는 scratch 레지스터도
기대 결과나 변경 허용 목록에 포함한다.

위 형식은 설계 예시이며 아직 schema validator를 구현한 것은 아니다.
rA의 V event는 bit 6이므로 기대값은 0x40이다. 축약 저장 인덱스 0을
아키텍처 값 1로 잘못 비교하지 않는다.

## 5. 초기 상태와 관찰

### 프로그램으로 준비하는 경우

reset 상태에서 시작하고 `.mms`의 LDO/ADDI 등으로 입력을 준비한다.
LDO/ADD/STO처럼 실제 명령 간 연결과 메모리·캐시를 확인할 integration에 사용한다.
전체 64비트 상수는 데이터 영역 OCTA와 LDO로 준비할 수 있다.

### 시뮬레이션 fixture로 준비하는 경우

명령어 하나를 분리해서 검증하거나 현재 PUT으로 설정할 수 없는 rD/rM/rA enable
등이 필요한 경우에 사용한다. 이는 CPU 기능이 아니라 테스트 환경의 역할이다.

- reset을 먼저 완료한다.
- calibration/시작을 보류해 CPU가 첫 명령을 실행하지 못한 상태에서 실제 저장
  변수에 한 번 deposit한다. reset이 활성인 에지에서 넣어 다시 지워지게 하지 않는다.
- 그 뒤 초기값 주입을 중단하고 정상 clock·RAM·CPU 동작으로만 실행한다.
- 실행 중 지속 force, ALU 결과 가로채기, 종료 신호 강제 설정은 금지한다.
- 초기 상태와 그 주입 방법을 결과에 기록한다. fixture 테스트를 프로그램이
  GET/PUT을 실행해 초기화한 테스트로 집계하지 않는다.

현재는 생성 RTL의 내부 경로를 사용해야 하므로 rtl_adapter에 이를 한정한다.
어셈블리나 case 파일에는 생성된 `s_...` 이름을 넣지 않는다. 어댑터는 source map과
확인된 저장 소자 경로로 debug bindings를 만들고, 경로/폭 불일치는 실행 전 오류로 한다.
output wire에 값을 덮어쓰지 않고 실제 DFF 저장 변수에 주입한다.
향후 표준 trace/debug 관찰 인터페이스를 추가해도 케이스 형식은 유지한다.

범용 레지스터 슬롯 512개와 특수 레지스터의 값을 비교한다.
rA의 비트 재배치는 어댑터가 담당한다. 캐시 tag/data/valid는 아키텍처 레지스터와
분리해 캐시 테스트에서 관찰한다.

## 6. 판정 기준

HALT가 나왔다는 사실만으로 PASS를 주지 않는다.

1. 어셈블·이미지 적재·RTL 컴파일이 성공해야 한다.
2. 목표 opcode가 지정한 PC에서 실제 실행돼야 한다. 미지원 명령으로 fault가
   나거나 HALT만 실행된 케이스를 명령어 테스트 통과로 보지 않는다.
3. 기대한 종료 상태(halt 또는 지정 fault)와 원인이 맞아야 한다.
4. 최종 범용/특수 레지스터와 메모리 값이 맞아야 한다.
5. 쓰기 이벤트와 변경 금지 영역을 검사해야 한다. 같은 값을 다시 쓴 경우도
   final 값만으로는 발견할 수 없으므로 write-enable 이벤트를 관찰한다.
6. HALT/FAULT 후 추가 명령 실행·쓰기·AXI 요청이 없는지 확인한다.

명령어 경계의 PC/instruction, 일반/특수 레지스터 쓰기, store 완료, fault를 공통
이벤트 형식으로 기록한다. 실제 clock 수에 맞춘 기대 이벤트 목록을 강제하지 않는다.
연산용 execute_en만으로 retire를 판단하면 load/store/HALT를 빠뜨리므로 어댑터가
각 실행 종류의 완료 경계를 구별해야 한다.

기대값은 수기로 검토한 상수 또는 독립 reference.py의 정수 연산에서 만든다.
crosscompiler의 디코더·게이트 평가 결과를 정답으로 다시 사용하지 않는다.
공식 명령어 시뮬레이터와의 differential 검사는 이후 추가할 수 있으나, 현재의
flat 레지스터 파일·직접 HALT·fault 정지와 완전한 MMIX 환경 차이를 먼저 맞춰야 한다.

## 7. 경계값 커버리지

공통 입력 집합은 0, 1, 2, -1, -2, INT64_MIN, INT64_MAX, UINT64_MAX,
2의 거듭제곱과 그 ±1, MSB만 1, 0xAA/0x55 교대 패턴, 단일 비트다.
모든 조합을 무작정 곱하지 않고 명령별 필수 분기를 지정한다.

| 계열 | 반드시 포함할 경우 |
|---|---|
| ADD/SUB와 unsigned형 | 정상·carry/borrow·양/음 signed overflow·unsigned wrap·동일 입력 |
| NEG/NEGU | Y=0/1/127/128/255, Z=0/±1/min/max. Y가 레지스터 값이 아닌 8비트 즉치임을 확인 |
| 2/4/8/16ADDU | 배수 이동에서 버려지는 비트·이후 덧셈 carry·오버플로 이벤트 없음 |
| MUL/MULU | 0·±1·min×-1·하위 결과 부호·상위 64비트. MULU의 rH 갱신과 MUL의 rH 유지 |
| DIV | 네 부호 조합·정확한 나눗셈·나머지 있음·floor 보정·0 나눗셈·min/-1 |
| DIVU | rD=0·제수보다 작은 상위값·rD=제수-1/제수/제수+1·제수0·몫/나머지 대체 결과 |
| CMP/CMPU | 같음·최솟값/최댓값·부호가 다른 같은 비트 패턴·결과 -1/0/1 |
| SL/SLU/SR/SRU | 이동량 0/1/31/32/63/64/65/UINT64_MAX, 0과 음수, SL V·SR 부호 채움 |
| CS/ZS 8조건 | 각 조건 참/거짓, 0/음수/양수/홀짝, 목적지 초기값을 0이 아닌 값으로 설정 |
| 비트 연산 | 0/all-ones/교대/각 단일 비트, 피연산자 반전 위치, rM=0/all-ones/교대 MUX |
| 즉치형 전체 | Z=0/1/127/128/255, 0 확장, 같은 수를 담은 레지스터형과 결과 일치 |
| LDO/STO 및 unsigned형 | 4개 octa lane, 레지스터/즉치 주소, big-endian, 정렬 내림, 이웃 바이트 보존 |
| 모든 목적지 명령 | X≠Y≠Z, X=Y, X=Z, Y=Z, X=Y=Z, 레지스터 0/255. $0도 쓰기 가능한 일반 레지스터 |

DIV의 rR, DIVU의 rD, MUX의 rM, MULU의 rH, rA의 이벤트와 enable을 최종 결과와
함께 검사한다. 이전 특수 레지스터 값을 일부러 0이 아닌 값으로 두어 잘못된
클리어/불필요한 쓰기를 검출한다.

rA는 event=0/1과 enable=0/1을 교차한다. disabled 예외의 sticky event와 enabled
예외의 trip 정지를 구분한다. 현재 하드웨어는 실제 trip 처리기 진입을 구현하지
않았으므로 이 단계의 테스트 이름과 기대값도 'trip 요청 후 fault 정지'로 명시한다.

CS 거짓은 쓰기 0회와 기존 목적지 유지, ZS 거짓은 쓰기 1회와 값 0을 검사한다.
둘의 최종 값이 우연히 같은 fixture로만 테스트하지 않는다.

## 8. 메모리·캐시·AXI

메모리 모델은 바이트 주소와 명시적 mapped window를 사용한다. 높은 유효 주소의
경계 테스트를 위해 1GiB 배열을 할당하지 말고, 필요한 주소 창을 작은 배열에 매핑한다.
초기 이미지/fixture가 겹치면 같은 바이트 값인지 확인하고 충돌은 오류로 한다.
미초기화 바이트 정책(예: A5 poison)을 profile에 기록한다.

| 범위 | 필수 경우 |
|---|---|
| 데이터 주소 | octa 내 0~7의 정렬 내림, 32바이트 라인 내 0/8/16/24, 라인·index·tag 경계 |
| RAM 주소 폭 | 마지막 유효 octa와 범위 밖 주소. 범위 밖은 AXI 요청 없이 오류 |
| I-cache | 한 라인의 8명령, 다음 라인, 복수 라인 유지와 충돌, store 후 코드 재읽기 |
| D-cache | 같은 라인 재읽기, 여러 index의 동시 유지, 주소 +16KB의 tag 충돌과 교체 |
| store | hit 시 byte-mask 갱신, miss에서 no-write-allocate, RAM의 쓰기 완료와 캐시 일치 |
| 오류 | RRESP/BRESP·잘못된 ID·에러 라인 미채움·store 오류 뒤 무효화 |
| AXI | 독립 AW/W 역압, AR 역압, R/B 지연, VALID/payload 유지, 응답 전 명령 완료 금지 |

캐시 warm 동작은 같은 프로그램 안에서 동일 주소를 다시 접근해 만든다.
서로 다른 케이스 사이에 reset을 생략해 우연히 캐시가 남은 것을 warm 테스트로 쓰지 않는다.
분기 미지원 상태에서도 순차 명령을 여러 라인/충돌 주소에 배치하고 data 접근을
반복해 테스트할 수 있다. 실행할 코드의 공백 영역도 명시적 명령으로 채운다.

프로파일은 다음처럼 나눈다.

- no_stall: 기능 원인 분리를 위한 짧은 RAM 응답.
- backpressure: 서로 다른 주기의 AR/AW/W READY와 지연 응답.
- random_seeded: 고정 seed의 지연/역압, seed를 보고서에 기록.
- error_*: 명시한 트랜잭션에만 오류 주입. fault 케이스에만 적용.

일반 기능 케이스는 정확한 AXI 횟수나 clock 수를 고정하지 않는다.
cache/performance 케이스에서만 해당 정책의 miss/transaction 수를 별도로 기대한다.

## 9. 지원 목록과 커버리지

현재 대상은 ALU/상수 106개 opcode, LDO/STO 계열 8개 opcode, 분기 34개 opcode와
정확한 HALT 인코딩이다.
HALT 하나를 지원한다고 일반 TRAP 전체를 지원한다고 기록하지 않는다.
부동소수점과 가상주소 등 제외 범위는 catalog에 명시한다.

isa.json은 mnemonic, opcode, 현재 지원 상태, 특수 레지스터 부작용, 필수 경계
커버리지 항목을 가진다. 디코더에서 기대값을 자동 복사하지 않는 독립적인 목록이다.
디코더 검사와 대조해 목록 누락/차이를 오류로 검출한다.

커버리지는 단순 테스트 파일 수가 아니라 실제 실행과 성공한 assertion을 기준으로 집계한다.

- 모든 지원 opcode가 target으로 한 번 이상 실행됐는가.
- register/immediate와 각 조건의 true/false가 모두 검증됐는가.
- 명령별 필수 경계와 rA/rH/rR/rD/rM 항목이 검증됐는가.
- 정상/역압 profile과 캐시 cold/hit 조건이 필요한 곳에서 검증됐는가.

미지원 케이스는 건너뛸 수 있지만 PASS가 아니다. 지원 명령에 테스트가 없거나
필수 커버리지가 비어 있으면 `--require-supported-coverage` 실행은 실패해야 한다.
BLOCKED/SKIP/미작성도 별도로 표시해 '전체 초록'으로 숨기지 않는다.

## 10. runner와 결과

향후 확장 CLI 설계(현재 실행 가능한 CLI는 문서 맨 위 참조):

```powershell
python -m verification.cpu_suite --all
python -m verification.cpu_suite --instruction ADD
python -m verification.cpu_suite --case add.signed_overflow.positive --profile backpressure
python -m verification.cpu_suite --tag boundary --require-supported-coverage
python -m verification.cpu_suite --failed --trace
```

동일 RTL/testbench는 한 번 컴파일하고 케이스별 vvp 프로세스를 실행한다.
이미지·fixture·profile 경로는 실행 인자로 넘긴다. 캐시/레지스터 상태는 프로세스와
reset으로 격리한다. simulator 실행의 동시성은 runner 구현 후 메모리 사용을 보고 정한다.

RTL·testbench·adapter·assembler 버전/해시와 profile/seed를 결과에 기록한다.
명령어 기대값이나 데이터가 다른데 이전 PASS를 재사용하지 않는다.

상태는 PASS, FAIL, ASSEMBLY_ERROR, IMAGE_ERROR, SIM_ERROR, TIMEOUT, UNSUPPORTED,
BLOCKED로 구분한다. 예상 fault는 해당 원인까지 일치하면 PASS이고, 예기치 않은
fault나 process 비정상 종료는 별도 실패다. clock timeout과 wall timeout을 모두 둔다.

실패 보고에는 case/profile/seed, PC·opcode·원본 어셈블리 위치, 기대/실제 값,
첫 불일치 명령, 관련 메모리 주소를 넣는다. 실행 실패와 값 불일치를 혼동하지 않는다.
기본 실행은 최종 덤프와 retire 로그를 저장하고, `--trace` 또는 실패 재실행 때
전체 cycles.jsonl/VCD와 기존 슬라이더 HTML을 생성한다.
지연 seed를 고정해 실패 재현이 가능해야 한다.

## 11. 구현 순서

1. isa.json과 case schema를 확정하고 공식 MMIXAL/적재 경로를 준비한다.
2. 공통 AXI RAM·fixture·RTL adapter·종료 판정과 timeout을 구현한다.
3. HALT/ADDU/LDO/STO smoke로 harness 자체와 초기화 경로를 확인한다.
4. ADD/ADDI의 정상·overflow 케이스부터 `.mms` 단위 실행을 완성한다.
5. 현재 지원하는 모든 opcode의 최소 정상 사례를 채운다.
6. 계열별 경계값·alias·특수 레지스터·조건별 분기를 추가한다.
7. integration/cache/fault/profile 검사를 추가하고 커버리지 누락을 실패로 처리한다.
8. seed 기반 생성 케이스와 differential 검사를 추가한다.

각 단계는 최종 suite의 부분집합이다. runner를 특정 명령어·명령 수·고정 RAM 주소에
맞추거나, 임시 opcode 배열을 suite의 어셈블리 대신 사용하지 않는다.
기존 verification의 ALU/decoder/cache 단위 검사는 유지해 프로그램 수준 실패의
원인을 좁히는 데 활용한다. CPU 프로그램 검증만으로 단위 검사를 대체하지 않는다.

현재 구현 현황은 문서 맨 위를 따른다. 이 절의 나머지 항목은 향후 확장 순서다.

## 작은 정수·부분 저장·주소·특수 레지스터 확장

폭별 load의 0/부호 경계/최대 양수/전부 1, 부호·0 확장, 접근 폭 내 비정렬 주소,
라인 경계, 마지막 유효 주소, 64비트 주소 합 wraparound를 케이스로 추가했다.
부분 store는 주변 바이트 보존과 상위 비트 절단을 명세에 반영하며 STCO의 X 값은
0/1/127/128/255를 다룬다. 연속 load→부분 store→같은 라인 load 프로그램도 포함한다.

GETA는 앞/뒤 오프셋 경계와 레지스터 보존을 다룬다. GET/PUT/PUTI는 지원 19개 레지스터,
미지원 13개 시스템/스택 레지스터, 형식 오류, rA 비트 제한을 다룬다. PUT rD→DIVU→GET rR,
PUT rM→MUX→GET rD 연속 프로그램으로 기존 ALU 저장 상태와의 연결을 명세화했다.
무효 명령은 실행되지 않고 fault가 되므로 `target.executions=0`을 사용한다. 이는 expected fault인
케이스에만 허용하며, 최종 상태와 쓰기 횟수도 그대로 비교한다.

케이스 파일과 참조 모델·디코더 검사 기대값을 갱신했으나 이번 변경 뒤 테스트와
시뮬레이션은 실행하지 않았다. 위 4,864회 PASS는 분기 추가 전 RTL의 과거 결과다.