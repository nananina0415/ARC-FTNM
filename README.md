# ARC-FTNM: A RISC Computer For The New Millennium

> FPGA로 구현하는 MMIX 컴퓨터

ARC-FTNM은 MMIX를 구현하지만, 스펙을 온전히 따르지는 않습니다.

예를들어, 부동소수점 연산기 부재, 커널 주소 공간(음수) 구분 없이 앱에 하드웨어 전권 위임 등이 그러합니다.

추후에는 mmix 사양과 더 일치해지겠지만 그럼에도 의도적인 차이가 있을 수 있습니다.

따라서 MMIXware에서 동작하는 앱이 ARC-FTNM에서 동작하지 않을 수 있습니다.

## 디렉터리 구조

```
ARC-FTNM/
├── tools/
│   └── gen_license_notice.py      — 빌드 단위별 NOTICE 파일 자동 생성
├── apps/
│   └── doom/                      — DOOM 포팅 (GPL v2)
│       ├── src/
│       │   ├── main,c             — FPGA 빌드 진입점 (입력·오디오·비디오 루프)
│       │   ├── patch_puredoom.h   — MMIX 빅엔디언 버그 패치
│       │   ├── test_on_sim.c      — 시뮬레이터 빌드 진입점
│       │   └── test_stdio.c       — libc stdio 검증 테스트
│       ├── freedoom-0.13.0/       — Freedoom WAD 데이터 (BSD 3-Clause)
│       ├── toolchain/mmix.cmake   — MMIX 크로스 컴파일 툴체인 설정
│       ├── gen_puredoom_h.py      — PureDOOM.h 패치 생성 스크립트
│       ├── check-fp.sh            — 부동소수점 명령어 검출 스크립트
│       ├── notice-map.json        — 라이선스 고지 매핑
│       ├── link.ld                — RAW 바이너리 링커 스크립트
│       └── PureDOOM/              — 외부 라이브러리 (GPL v2)
├── drivers/
│   ├── fpga/                      — FPGA 공통 드라이버 (보드 독립)
│   │   ├── audio.c                — OPL3·오디오 믹서 MMIO
│   │   └── memmap.c               — FPGA MMIO 맵 테이블
│   └── PA100T-EDU/                — PA100T-EDU 보드 전용 드라이버
│       ├── memmap.c               — 보드 MMIO 맵 테이블
│       ├── video.c                — HDMI·LCD 프레임버퍼 출력
│       └── sd_card.h              — SD 카드 SPI 인터페이스
├── mmix-fpga/                     — FPGA RTL (Verilog/VHDL)
│   ├── cpu/                       — MMIX CPU 코어
│   ├── mem/                       — 메모리 컨트롤러 (DDR3)
│   ├── video/                     — HDMI·LCD 비디오 출력
│   ├── audio/                     — OPL3 인터페이스·PCM 믹서
│   ├── timer/                     — 하드웨어 타이머
│   ├── rom/                       — BIOS+부트로더
│   └── third_party/
│       ├── gtaylormb_opl3_fpga/   — OPL3 FM 합성기 RTL (LGPL v3)
│       ├── hdl-util_hdmi/         — HDMI 인코더 RTL (MIT / Apache-2.0)
│       └── m1nl_usb_hid_host/     — USB HID 호스트 RTL, FS+LS (Apache-2.0)
└── nnix-os/
    ├── link.ld                    — 로우 바이너리 링커 스크립트
    ├── notice-map.json            — 라이선스 고지 매핑
    ├── res/
    │   └── unifont-17.0.05.bdf    — GNU Unifont (SIL OFL v1.1)
    ├── tools/
    │   └── gen_font.py            — unifont.h 빌드 타임 생성 스크립트
    ├── libc/                      — 앱이 링크하는 C 런타임 API
    │   ├── crt0.s                 — 공통 스타트업 (BSS 초기화·스택 설정·main 호출)
    │   ├── stdio.h/c
    │   ├── stdlib.h/c
    │   ├── string.h/c
    │   ├── unistd.h/c
    │   ├── _print.h/c             — 포맷 이터레이터 (printf 내부)
    │   └── sys/time.h
    ├── src/
    │   ├── crt1.s                 — OS 스타트업 (rT 설정 후 _start 호출)
    │   ├── trap.s/c               — 트랩 핸들러 및 디스패처
    │   ├── device/                — 장치 인터페이스 헤더
    │   │   ├── types.h            — MMIO 맵 구조체·장치 열거형
    │   │   ├── audio.h            — PCM 오디오 믹서
    │   │   ├── opl.h              — OPL3 FM 합성기
    │   │   ├── hdmi.h / lcd.h     — 비디오 출력
    │   │   ├── usb.h              — USB HID (joystick_t)
    │   │   ├── timer.h            — 하드웨어 타이머
    │   │   ├── uart.h             — UART 시리얼
    │   │   ├── spi.h / i2c.h      — SPI·I2C 버스
    │   │   ├── gpio.h             — 범용 GPIO
    │   │   ├── eth.h              — 이더넷
    │   │   ├── qspi.h             — QSPI 플래시
    │   │   ├── seg7.h             — 7세그먼트 디스플레이
    │   │   └── bios_rom.h         — BIOS ROM 접근
    │   ├── fs.h/c                 — 파일시스템, 파일 핸들 관리
    │   ├── mem.h/c                — 메모리 관리
    │   ├── proc.h/c               — 앱 실행 ($255=exe클러스터, $254=링크클러스터)
    │   └── main.c                 — 부팅 메뉴 TUI
    └── third_party/
        └── fatfs/                 — FatFs (1-clause BSD)
```

## 🤝 Contributing & Licensing Policy ⚖️

아크팬텀(ARC-FTNM)은 오픈 소스 기여를 통해 완성됩니다. 

MMIX CPU 구현부터 주변장치, 시스템 소프트웨어, UI 디자인, 앱 포팅, 문서화, 테스팅, 아이디어 제시까지 모든 분야의 기여를 환영합니다.

- **어떻게 시작하나요?** [전체 기여 가이드라인 읽어보기](./CONTRIBUTING.md)

- **개발 환경 설정:** 각 리포지토리의 `README.md`를 참고해 주세요.

### **라이선스**
  - ARC-FTNM의 모든 코드는 생태계의 유연성과 보안을 위해 기본적으로 **듀얼 라이선스(Dual License)** 정책을 채택하고 있습니다. 여러분의 모든 기여는 Apache 2.0 및 MIT 듀얼 라이선스 하에 관리됩니다.
  - 별도의 명시가 없는 한, 이 저장소의 모든 리포지토리에 대한 기여는 위 두 라이선스 조건에 동의한 것으로 간주됩니다.

#### **법적 합의 (중요)**: 아크팬텀 프로젝트에 기여함으로써, 귀하는 다음 사항에 동의하게 됩니다:
  1. 귀하의 기여물은 프로젝트가 채택한 **Apache License 2.0** 및 **MIT License** 하에 듀얼 라이선스로 공개됩니다.
  2. 귀하는 본인의 기여물에 대해 Apache License 2.0에 명시된 **특허 허여(Patent Grant)** 조항을 준수하며, 사용자들에게 관련 특허 권한을 부여하는 것에 동의합니다.

  이러한 조항은 프로젝트의 무결성을 유지하고 모든 사용자와 기여자를 법적으로 보호하기 위함입니다.

  **라이선스 예외 컴포넌트**

  아래 경로의 코드는 해당 컴포넌트 고유의 라이선스를 따르며, 기본 듀얼 라이선스가 적용되지 않습니다.

  ```
  ARC-FTNM/
  ├── apps/
  │   └── doom/                         — GPL v2  (PureDOOM과 결합으로 전염)
  │       ├── PureDOOM/                 — GPL v2  (외부 라이브러리)
  │       └── freedoom-0.13.0/          — BSD 3-Clause  (외부 리소스)
  ├── mmix-fpga/
  │   └── third_party/
  │       ├── gtaylormb_opl3_fpga/      — LGPL v3  (외부 라이브러리)
  │       ├── hdl-util_hdmi/            — MIT / Apache-2.0  (외부 라이브러리)
  │       └── m1nl_usb_hid_host/        — Apache-2.0  (외부 라이브러리)
  └── nnix-os/
      ├── res/
      │   └── unifont-17.0.05.bdf       — SIL OFL v1.1  (외부 리소스)
      └── third_party/
          └── fatfs/                    — 1-clause BSD  (외부 라이브러리)
  ```

  각 디렉토리 내 `LICENSE` 파일을 참조하세요.

  **고지 (LGPL v3 — gtaylormb/opl3_fpga)**

  이 프로젝트의 FPGA 비트스트림은 [gtaylormb/opl3_fpga](https://github.com/gtaylormb/opl3_fpga)를 포함합니다.
  해당 라이브러리는 GNU Lesser General Public License v3 (LGPL-3.0)의 적용을 받습니다.
  라이선스 전문은 `mmix-fpga/third_party/gtaylormb_opl3_fpga/COPYING.LESSER`를 참조하세요.
  LGPL-3.0에 따라 소스 코드 접근 및 수정된 라이브러리로의 재합성이 가능해야 합니다.
  해당 라이브러리는 git 서브모듈(`mmix-fpga/third_party/gtaylormb_opl3_fpga/`)로 참조되며,
  원본 소스는 https://github.com/gtaylormb/opl3_fpga 에서 제공됩니다.


