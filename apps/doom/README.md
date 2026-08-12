# doom-mmix

ARC-FTNM FPGA 컴퓨터에서 DOOM을 실행하는 포팅.  
[PureDOOM](https://github.com/Daivuk/PureDOOM)(단일 헤더 라이브러리)을 기반으로, MMIX CPU / PA100T-EDU 보드에 맞게 HAL을 구현했다.

## 파일 구조

```
apps/doom/
├── src/
│   ├── main,c             — FPGA 빌드 진입점
│   ├── test_on_sim.c      — mmixware 시뮬레이터 빌드 진입점
│   ├── DOOM.c             — PureDOOM 오버라이드 (게임 루프 진입 변경)
│   └── patch_puredoom.h   — MMIX 빅엔디언 버그 패치 (m_swap.h 직후 삽입)
├── PureDOOM/              — PureDOOM 서브모듈 (GPL v2)
│                            원본인 Daivuk/PureDOOM에서 부동소수점 코드를 제거한 nananina0415/PureDOOM-NoFloat 사용
│                            추후 한글화 및 다국어 지원을 위한 해상도 업스케일링 예정
├── gen_puredoom_h.py      — PureDOOM.h 재생성 스크립트 (패치 삽입)
├── toolchain/mmix.cmake   — MMIX 크로스 컴파일 툴체인 설정
└── CMakeLists.txt
```

## 빌드

### FPGA 빌드 (nnix-os 경유 실행)

루트 CMake로 통합 빌드된다. nnix-os가 `doom.mmix`를 SD 카드에서 로드해 실행한다.

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

`gen_puredoom_h.py`는 PureDOOM 서브모듈 소스를 단일 헤더로 합치면서  
`m_swap.h` 직후에 `src/patch_puredoom.h`를 삽입한다.  
CMake 빌드 시 자동으로 실행되며, 출력은 빌드 디렉토리에 생성된다(서브모듈 무수정).

### PureDOOM 서브모듈 수정 사항

원본 [Daivuk/PureDOOM](https://github.com/Daivuk/PureDOOM)에서 포크한  
`ananina0415/PureDOOM-NoFloat` 서브모듈에는 아래 변경이 적용되어 있다:

- **부동소수점 제거**: MMIX 코어에 FPU가 없으므로 float 연산 제거
- **`-iwad` 지원**: `D_DoomMain()`에서 `-iwad <파일>` 인자를 처리.  
  파일이 존재하면 파일명으로 gamemode를 결정하고 `IdentifyVersion()`을 건너뜀.  
  파일이 없으면 `I_Error`로 즉시 중단. 인자가 없으면 기존 자동 탐색.

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

추후에는 PureDOM-NoFloat 측에서 지원할 예정

### HAL 콜백 (`src/main,c`)

| 콜백 | 구현 방식 |
|---|---|
| `getenv` | `HOME` → `"."` 고정 (doomrc 미존재 시 I_Error 방지) |
| `gettime` | TODO: FPGA 하드웨어 타이머 레지스터 읽기 |
| 파일 I/O | `DOOM_IMPLEMENT_FILE_IO`: FatFs + SD 카드 SPI (nnix-os syscall) |
| malloc | `DOOM_IMPLEMENT_MALLOC`: nnix-os 힙 할당기 |

### 오디오

- **음악 (OPL3):** `doom_tick_midi()` 반환값을 `SC_OPL_WRITE` syscall로 FPGA OPL3에 전달. 게임 루프(35Hz)에서 드레인.
- **효과음 (PCM):** `doom_get_sound_buffer()`로 믹서 버퍼를 받아 `SC_AUDIO_WRITE` syscall로 FPGA PCM DMA에 전달.

### 입력 (USB HID 조이스틱)

J11 IIC 헤더(IO_L16P_16 = D+, IO_L16N_16 = D-)를 USB 호스트로 사용.  
`m1nl/usb_hid_host` RTL이 FPGA에서 자율 동작하며 MMIO로 상태를 노출한다.

| 조이스틱 입력 | DOOM 키 |
|---|---|
| 좌/우 (일반) | ← → (회전) |
| 좌/우 (버튼4 홀드) | A / D (스트레이프) |
| 위/아래 | ↑ ↓ (전진/후진) |
| 버튼 A | Ctrl (발사) |
| 버튼 B | Space (사용) |
| 버튼 X | Shift (달리기) |
| 버튼 Select | Escape (메뉴) |

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
