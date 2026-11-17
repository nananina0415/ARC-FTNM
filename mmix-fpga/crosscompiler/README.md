# Structural pseudo → Verilog crosscompiler

프로젝트의 [PEG 문법](../pseudo/grammar.peg)과
[언어 명세](../pseudo/language-semantics.md)에 따라 회로 의사코드를 Verilog-2001로
변환하는 Python 프로그램이다. MMIX 명령어를 해석하거나 특정 프로그램의 결과를
생성하지 않는다. 입력 회로의 모듈·포트·소자·배선을 변환한다.

Python 3.10 이상과 표준 라이브러리만 사용한다. 외부 패키지 설치는 필요 없다.
아래 명령으로 현재 CPU의 변환을 실행했으며 build/rtl에 Verilog 55개와 메타데이터를 생성했다.
이는 변환 성공이며 생성 RTL의 합성·기능·타이밍 검증을 뜻하지 않는다.

## 사용법

저장소 루트에서 실행한다.

```powershell
python -m crosscompiler pseudo --top cpu::Cpu --out build/rtl
```

파일을 쓰지 않고 파싱·회로 구체화·정적 검사를 수행하려면:

```powershell
python -m crosscompiler pseudo --top cpu::Cpu --check
```

모듈 계층을 보존하면서 Verilog 파일만 하나로 합치려면:

```powershell
python -m crosscompiler pseudo --top cpu::Cpu --out build/rtl --single-file
```

`--grammar path/to/grammar.peg`로 PEG 명세 경로를 지정할 수 있다.
기본값은 저장소의 pseudo/grammar.peg다. 입력 폴더와 출력 폴더는 서로 포함하지
않아야 한다. top은 소스 루트 기준의 회로 이름이며, 특정 CPU 이름을 기본값으로
하드코딩하지 않았다. 제네릭 top은 구체 인스턴스를 가진 wrapper를 통해 사용한다.

## 처리 구조

| 파일 | 역할 |
|---|---|
| peg.py | PEG 정의를 읽는 packrat 파서 엔진, CST와 소스 위치 |
| frontend.py | CST를 선언·타입·표현식·배선 AST로 변환 |
| model.py | AST, 위치, 사용자 오류 자료형 |
| typesys.py | 폴더 이름 공간, alias, 상수, 타입과 번들 해석 |
| elaborator.py | 제네릭 특수화, 배열/번들 전개, 회로 연결과 정적 검사 |
| ir.py | 모듈/인스턴스/핀/게이트와 union-find net 자료구조 |
| builtins.py | DFF/Mux/Decoder/TriState/Adder/Multiplier/Divider/SyncRam 계약과 RTL |
| verilog.py | 모듈별 Verilog 출력, 펼친 포트, 파일 목록과 소스 맵 |
| compiler.py | 소스 수집부터 결과 생성까지의 API |
| output.py | 생성 파일 소유권, staging, 이전 생성물 교체와 I/O 실패 복원 |
| __main__.py | CLI와 오류 보고 |

PEG 엔진은 명세를 읽으므로 문장 구분을 줄 단위 치환으로 처리하지 않는다.
폭·포트 방향·버스 합류의 적법성은 파싱 후 별도로 검사한다.

## 지원 기능

- comb/seq와 wire 블록 결합, pub 포트와 mut 순차 인스턴스.
- 폴더 이름 공간, 전역·지역 alias, 정수 상수 연산과 순환 alias 진단.
- 단일/복합/반전 번들, In/Out/InOut, 배열, 제네릭 정수·타입 인자.
- 목록/범위 인덱싱, 배열 필드 투영, LSB 우선 packed 목록.
- 직접 연결, 복제 연결, 삼상 버스 합류, 공유 net을 형성하는 양방향 연결.
- bitwise/reduction 게이트, 명시적 열린 입력과 미사용 출력.
- 폭·방향·범위·중복 목적지·미연결 입력·미사용 출력 검사.
- 일반 다중 구동과 삼상 구동 구분, 조합 루프 및 재귀 회로 진단.
- 표준 소자 RTL 생성과 Verilog 모듈 계층 보존.

구체 파라미터 조합당 모듈 하나를 생성하며 같은 조합은 재사용한다.
문법에서 지원하는 일반 구조를 구체화하는 것이며 CPU별 예외 규칙은 없다.
조합 소자를 자동으로 다중 사이클 IP로 바꾸거나 reset/파이프라인을 삽입하지 않는다.

## 출력

- 원본 이름 공간 폴더별 `.v` 모듈. 내장 소자는 builtin/ 아래에 생성.
- sources.f: Verilog 파일 목록. 경로는 출력 폴더 기준.
- top.txt: 생성한 최상위 모듈 이름.
- source-map.json: 모듈·포트·인스턴스·게이트·net과 원본 파일 위치의 대응.
- .crosscompiler-manifest.json: 버전·소스/문법 해시·옵션·생성 파일 목록.

식별자는 길이를 포함해 인코딩하여 폴더/필드 이름을 단순히 합칠 때의 충돌을 피한다.
제네릭 인자는 안정적인 해시를 이름에 포함한다. 생성 시각과 사용자 절대 경로는
생성물에 넣지 않는다. 소스 오류가 있으면 출력 파일을 반영하지 않는다.

같은 net에 연결된 핀은 같은 Verilog net을 사용한다. 여러 공개 InOut 핀이 같은
net에 연결돼 있으면 Verilog의 tran primitive로 연결하며, 양방향 assign 두 개를
만들지 않는다. 대상 합성기의 내부 삼상/tran 지원은 별도 확인 대상이다.

이전 manifest가 소유한 파일만 교체/제거한다. manifest에 없는 기존 파일은
덮어쓰지 않으며, 입력 트리나 MIG IP 파일을 수정하지 않는다. 파일 반영은 staging과
백업을 거친다. 프로세스 강제 종료/시스템 전원 장애까지의 트랜잭션 보장은 아니다.

## 현재 의사코드와의 관계

언어 명세를 엄격히 적용하므로 현재 의사코드가 곧바로 모두 통과한다고 가정하지 않는다.
코드를 읽으며 확인한 소스 정리 사항은 다음과 같다.

- AXI 선언은 pseudo/axi/axi.pseudo로 옮겨 global.pseudo의 `::axi::*` 참조와 맞췄다.
- 엄격한 미사용 출력 검사에 맞춰 Decoder 출력과 사용하지 않는 직접 출력에 OPENED를 명시했다.
- 삼상 구동과 일반 구동이 같은 버스에 있으면 경고를 내며, 소스에서 enable과
  충돌 가능성을 정리해야 한다. 컴파일러가 임의로 우선순위를 삽입하지 않는다.

최초 변환에서 발견한 입력 핀 net 읽기 제한 오류를 수정했다. 연결된 입력 핀의
net도 읽을 수 있으며, 구동 주체의 적법성과 실제 구동기의 존재는 별도로 검사한다.
비활성 GET/PUT 데이터 버스는 OPENED(Z)로 두며 Z 구동은 일반 구동 충돌로 세지 않는다.

## 제한과 확인 상태

- 사용자 함수·런타임 산술 표현식은 지원하지 않는다. 명세의 게이트와 내장 소자를 사용한다.
- mixed-direction 복합 번들 전체 연결은 지원하지 않고 leaf 포트별 연결을 요구한다.
- 모듈 구체화 깊이는 128로 제한해 무한한 제네릭 재귀를 진단한다.
- 정적 검사는 논리 합성이나 기능/타이밍 검증을 대신하지 않는다.
- 사용자 요청에 따라 변환 명령을 실행했고 오류·경고 없이 완료했다.
- 이후 사용자의 검증 요청에 따라 생성 Verilog의 구문·elaboration, ALU/디코더/CPU
  시뮬레이션을 수행했다. 결과와 한계는 [검증 보고서](../docs/rtl-verification.md)에 기록했다.
  Vivado 합성·타이밍·보드 동작 확인은 수행하지 않았다.
