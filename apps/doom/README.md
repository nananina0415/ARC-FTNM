# doom-mmix

ARC-FTNM FPGA 컴퓨터에서 DOOM을 실행하는 포팅.  
[PureDOOM](https://github.com/Daivuk/PureDOOM)(단일 헤더 라이브러리)을 기반으로, MMIX CPU / PA100T-EDU 보드에 맞게 HAL을 구현했다.

## 파일 구조

```
apps/doom/
├── src/
│   ├── main.c             — FPGA 빌드 진입점 (HAL 콜백 구현)
│   ├── test_on_sim.c      — mmixware 시뮬레이터 빌드 진입점
│   ├── test_stdio.c       — libc stdio 동작 검증용 독립 테스트
│   ├── DOOM.c             — PureDOOM 오버라이드 (게임 루프 진입 변경)
│   └── patch_puredoom.h   — MMIX 빅엔디언 버그 패치 (m_swap.h 직후 삽입)
├── PureDOOM/              — PureDOOM 포크 (git subtree, GPL v2)
│                            원본인 Daivuk/PureDOOM에서 부동소수점 코드를 제거한 nananina0415/PureDOOM-NoFloat 사용
│                            추후 한글화 및 다국어 지원을 위한 해상도 업스케일링 예정
├── PureDOOM_kr.md         — PureDOOM 리드미 한국어 번역
├── freedoom-0.13.0/       — Freedoom WAD 패키지 (BSD 3-Clause)
│   ├── freedoom1.wad      — Phase 1 (Doom 1 호환, 36레벨)
│   └── freedoom2.wad      — Phase 2 (Doom 2 호환, 32레벨)
├── check-fp.sh            — MMIX 바이너리에서 부동소수점 명령어 검출
├── gen_puredoom_h.py      — PureDOOM.h 재생성 스크립트 (패치 삽입)
├── notice-map.json        — 라이선스 고지 매핑
├── LICENSE                — GPL v2
├── toolchain/mmix.cmake   — MMIX 크로스 컴파일 툴체인 설정
└── CMakeLists.txt
```

## 빌드

### FPGA 빌드 (nnix-os 경유 실행)

루트 CMake로 nnix-os와 함께 통합 빌드된다. 빌드 결과를 SD 카드 루트 디렉토리에 `이름.app`으로 두거나,
`.link.app`(아래 WAD 절)에서 실행파일로 지정하면 nnix-os 메뉴에서 실행할 수 있다.
BIOS ROM이 루트 디렉토리에서 시작 클러스터로 파일 크기를 찾으므로 실행파일과 `.link.app`은 루트에 둔다.
FPGA 빌드는 커널 라이브러리(`nnix-os/kernel`)를 정적 링크해 파일·장치 함수를 직접 호출한다.

```sh
# 루트 디렉토리에서
cmake -B build -DCMAKE_TOOLCHAIN_FILE=apps/doom/toolchain/mmix.cmake
cmake --build build
```

출력: `build/apps/doom/doom` (MMIX 바이너리)

### 시뮬레이터 빌드

호스트 GCC로 빌드해 mmixware 시뮬레이터 또는 네이티브 실행 환경에서 테스트한다.  
50 프레임 렌더 후 `frame.ppm`을 출력하고 종료한다.

```sh
cmake -B apps/doom/build-sim -S apps/doom -DMMIX_SIM=ON
cmake --build apps/doom/build-sim
./apps/doom/build-sim/doom
```

### PureDOOM.h 재생성

`gen_puredoom_h.py`는 PureDOOM 포크 소스(`PureDOOM/src/DOOM`)를 단일 헤더로 합치면서  
`m_swap.h` 직후에 `src/patch_puredoom.h`를 삽입한다. `src/`에 같은 이름의 파일이 있으면 그 파일을 대신 쓴다.  
CMake 빌드 시 자동으로 실행되며, 출력은 빌드 디렉토리에 생성된다(커밋된 `PureDOOM/PureDOOM.h`는 건드리지 않음).

### PureDOOM 포크 수정 사항

원본 [Daivuk/PureDOOM](https://github.com/Daivuk/PureDOOM)에서 포크한  
`nananina0415/PureDOOM-NoFloat`에는 아래 변경이 적용되어 있다.  
`apps/doom/PureDOOM`은 이 포크를 git subtree로 가져온 것이다. 포크 수정은 이 저장소에서 커밋한 뒤 subtree push로 포크에 반영하며,
`src/DOOM`과 단일 헤더 `PureDOOM.h`를 함께 고친다.

- **부동소수점 제거**: MMIX 코어에 FPU가 없으므로 float 연산 제거
- **`-iwad` 지원**: `D_DoomMain()`에서 `-iwad <파일>` 인자를 처리.  
  파일이 존재하면 파일명으로 gamemode를 결정하고 `IdentifyVersion()`을 건너뜀.  
  파일이 없으면 `I_Error`로 즉시 중단. 인자가 없으면 기존 자동 탐색.
- **오류 메시지 경로 제거**: `Z_ChangeTag` 오류 메시지가 `__FILE__`(빌드 절대 경로) 대신 줄 번호만 출력.
- **선택 기능 매크로**: 정의하지 않으면 원본 동작. 자세한 내용은 [포크 README](PureDOOM/README.md).
  이 앱은 `CMakeLists.txt`에서 세 개를 모두 정의한다.

  | 매크로 | 동작 |
  |---|---|
  | `DOOM_AUTOMAP_PAUSE` | 자동 지도가 열려 있는 동안 게임 진행 멈춤 (싱글플레이) |
  | `DOOM_AUTOMAP_NO_FOLLOW` | 자동 지도 따라가기 없음, 직접 이동만 |
  | `DOOM_WEAPON_CYCLE` | 이전/다음 무기 전환 키 (`[` / `]`), 탄약 없는 무기는 건너뜀 |

## MMIX 포팅 핵심

### 빅엔디언 패치 (`patch_puredoom.h`)

PureDOOM의 `m_swap.h`는 빅엔디언 환경에서 `LONG(x)`를 아래처럼 정의한다.

```c
#define LONG(x) (long)SwapLONG((unsigned long)(x))
```

MMIX에서 `unsigned long`은 64비트이므로 음수 `int` 값이 `0xFFFFFFFF…` 로 sign-extend되고,  
`SwapLONG`이 상위 32비트의 `0xFF`를 결과에 혼합시켜 WAD 파싱이 깨진다.

패치는 `LONG`/`SHORT`를 `undef` 후, `unsigned int`로 마스킹한 올바른 버전으로 재정의한다.

```c
#undef LONG
#undef SHORT
static inline int   doom_le32(unsigned int x)  { /* 바이트 스왑 */ }
static inline short doom_le16(unsigned short x) { /* 바이트 스왑 */ }
#define LONG(x)  doom_le32((unsigned int)(x))
#define SHORT(x) doom_le16((unsigned short)(x))
```

### 출력 해상도

DOOM 내부 렌더: 320×200  
HDMI DMA 출력: 640×480 (`DOOM_OUT_WIDTH` / `DOOM_OUT_HEIGHT`)

스케일업은 `src/DOOM.c`의 `doom_get_framebuffer()`에서 처리한다.

추후에는 PureDOOM-NoFloat 측에서 지원할 예정

### HAL 콜백 (`src/main.c`)

| 콜백 | 구현 방식 |
|---|---|
| `getenv` | `HOME` → `"."` 고정 (doomrc 미존재 시 I_Error 방지) |
| `gettime` | 미구현 (0 반환). 커널의 `sys_gettime()` → `timer_get()` 연결 예정 |
| 파일 I/O | `DOOM_IMPLEMENT_FILE_IO`: libc → 커널 `sys_f*()` → FatFs + SD 카드 SPI |
| malloc | `DOOM_IMPLEMENT_MALLOC`: 커널 힙 할당기 (`kernel/mem.c`) |

### 오디오

- **음악 (OPL3):** `doom_tick_midi()` 반환값을 커널 `sys_opl_write()`로 FPGA OPL3에 전달. 게임 루프(35Hz)에서 드레인.
- **효과음 (PCM):** `doom_get_sound_buffer()`로 믹서 버퍼를 받아 커널 `sys_audio_write()`로 FPGA PCM DMA에 전달.

### 입력 (USB HID 조이스틱)

커널의 `usb_joystick()`(`nnix-os/kernel/device/usb.h`)로 HID 조이스틱 상태(스틱 축, 버튼 비트)를 읽는다.

아래 내용은 이 앱이 가정하는 조이스틱 구성과 매핑이다.
조이스틱은 스틱 하나와 버튼 10개로, 작은 버튼 2개 `p` `m`, 큰 버튼 4개씩 두 줄(윗줄 `w` `x` `y` `z`, 아랫줄 `a` `b` `c` `d`)이다.
버튼 비트는 `usb.h`의 `JOY_BTN_*`이다.

| 비트 | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|---|
| 버튼 | a | b | c | d | w | x | y | z | p | m |

`src/main.c`는 화면 상태(게임 / 자동 지도 / 메뉴)에 따라 버튼을 다른 키로 보낸다.

| 입력 | 게임 | 자동 지도 (게임 멈춤) | 메뉴 |
|---|---|---|---|
| 스틱 ↑ ↓ | ↑ ↓ (전진/후진) | ↑ ↓ (지도 이동) | ↑ ↓ (항목 이동) |
| 스틱 ← → | A / D (옆걸음). `x`를 누르고 있으면 ← → (회전) | ← → (지도 이동) | ← → (슬라이더) |
| `c` / `d` | ← / → (좌·우회전) | `c`: `0` (전체 지도) | |
| `a` | Ctrl (발사) | `=` (확대) | `y` 다음 Enter (질문엔 예, 메뉴엔 선택) |
| `b` | Shift (달리기) | `-` (축소) | Backspace 다음 Space (메뉴엔 뒤로, 질문엔 아니오) |
| `w` | Space (상호작용) | `g` (격자) | |
| `x` | 회전 모드 | `m` (위치 표시) | |
| `y` / `z` | `[` / `]` (이전/다음 무기) | `y`: `c` (표시 지우기) | |
| `m` | Tab (지도 열기) | Tab (지도 닫기) | |
| `p` | Esc (메뉴) | Esc (메뉴) | Esc (메뉴 닫기, 질문엔 아니오) |

- 키 이벤트는 버튼 상태가 바뀔 때만 보낸다.
- 화면 상태가 바뀌는 순간 눌려 있던 입력은 뗄 때까지 무시한다. 예를 들어 발사 버튼을 누른 채 메뉴를 열어도 메뉴 항목이 선택되지 않는다.
- 패널을 읽지 못하면 눌려 있던 키를 모두 뗀다.
- 단축키(F1~F11), 일시정지, 숫자키 무기 선택은 매핑하지 않는다. 빠른 저장·불러오기와 감마 외의 단축키 기능은 메뉴에서 쓸 수 있다.

## WAD 파일

### IWAD 선택

nnix-os가 doom을 실행할 때 *.link.app 텍스트파일을 통해 `-iwad` 인자로 WAD 파일을 지정할 수 있다.

```
# DOOM.link.app 
doom.engine -iwad freedoom1.wad
```

SD 카드에 여러 *.link.app를 두어 엔진을 공유하면서 사용할 버전을 선택할 수 있다.

### 지원 WAD

| 파일명 | gamemode | 내용 |
|---|---|---|
| `doom1.wad` | shareware | DOOM 1 쉐어웨어 (에피소드 1) |
| `doom.wad` | registered | DOOM 1 등록판 (에피소드 1~3) |
| `doomu.wad` | retail | Ultimate DOOM (에피소드 1~4) |
| `doom2.wad` | commercial | DOOM 2 |
| `plutonia.wad` | commercial | Final DOOM: Plutonia |
| `tnt.wad` | commercial | Final DOOM: TNT Evilution |
| `freedoom1.wad` | retail | Freedoom Phase 1 (BSD, 자유 배포) |
| `freedoom2.wad` | commercial | Freedoom Phase 2 (BSD, 자유 배포) |

## 라이선스

PureDOOM이 GPL v2이기에, 이 디렉토리의 모든 소스 코드는 **GPL v2**를 따른다.  
자세한 내용은 `LICENSE`를 참조.
