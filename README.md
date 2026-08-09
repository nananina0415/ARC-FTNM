# ARC-FTNM: A RISC Computer For The New Millennium

> FPGA로 구현하는 MMIX 컴퓨터

ARC-FTNM은 MMIX를 구현하지만, 스펙을 온전히 따르지는 않습니다.
예를들어, 부동소수점 연산기 부재, 커널 주소 공간(음수) 구분 없이 앱에 하드웨어 전권 위임 등이 그러합니다.
추후에는 mmix 사양과 더 일치해지겠지만 그럼에도 의도적인 차이가 있을 수 있습니다.
따라서 MMIXware에서 동작하는 앱이 ARC-FTNM에서 동작하지 않을 수 있습니다.

## 디렉터리 구조

```
ARC-FTNM/
├── apps/
│   └── doom/              — DOOM 포팅
│       └── PureDOOM/      — 외부 라이브러리 (GPL v2)
├── drivers/
│   └── PA100T-EDU/        — 보드별 드라이버 및 MMIO 맵
├── mmix-fpga/
│   └── bios/              — BRAM에 올라가는 BIOS
├── nnix-os/
│   ├── libc/              — 앱이 링크하는 C 런타임 API
│   │   ├── crt0.s         — 공통 스타트업 (스택·BSS·main 호출)
│   │   ├── stdio.h/c      — 파일·프린트 API (TRAP 호출 래퍼)
│   │   ├── stdlib.h/c
│   │   ├── string.h/c
│   │   ├── _print.h/c     — 포맷 이터레이터 (printf 내부)
│   │   └── sys/
│   ├── src/               — OS 전용 구현
│   │   ├── crt1.s         — OS 스타트업 (rT 설정)
│   │   ├── syscall.h      — 시스템콜 번호 정의
│   │   ├── trap.s/c       — 트랩 핸들러 및 디스패처
│   │   ├── device.h       — MMIO 맵 구조체 및 장치 열거형
│   │   ├── fs.h/c         — 파일 핸들 관리
│   │   ├── mem.h/c        — 메모리 관리
│   │   └── main.c
│   └── third_party/
│       └── fatfs/         — FatFs (1-clause BSD)
└── mmix-gcc/              — MMIX 크로스 컴파일러 툴체인
```

## 🤝 Contributing & Licensing Policy ⚖️

아크팬텀(ARC-FTNM)은 오픈 소스 기여를 통해 완성됩니다. 

MMIX CPU 구현부터 주변장치, 시스템 소프트웨어, UI 디자인, 앱 포팅, 문서화, 테스팅, 아이디어 제시까지 모든 분야의 기여를 환영합니다.

- **어떻게 시작하나요?** [전체 기여 가이드라인 읽어보기](./CONTRIBUTING.md)

- **개발 환경 설정:** 각 리포지토리의 `README.md`를 참고해 주세요.

- **라이선스:**
  - ARC-FTNM의 모든 코드는 생태계의 유연성과 보안을 위해 기본적으로 **듀얼 라이선스(Dual License)** 정책을 채택하고 있습니다. 여러분의 모든 기여는 Apache 2.0 및 MIT 듀얼 라이선스 하에 관리됩니다.
  - 별도의 명시가 없는 한, 이 저장소의 모든 리포지토리에 대한 기여는 위 두 라이선스 조건에 동의한 것으로 간주됩니다.

  **라이선스 예외 컴포넌트**

  아래 경로의 코드는 해당 컴포넌트 고유의 라이선스를 따르며, 기본 듀얼 라이선스가 적용되지 않습니다.

  ```
  ARC-FTNM/
  ├── apps/
  │   └── doom/
  │       └── PureDOOM/          — GPL v2  (외부 라이브러리)
  └── nnix-os/
      └── third_party/
          └── fatfs/             — 1-clause BSD  (외부 라이브러리)
  ```

  각 디렉토리 내 `LICENSE` 파일을 참조하세요.

- **법적 합의 (중요)**: 아크팬텀 프로젝트에 기여함으로써, 귀하는 다음 사항에 동의하게 됩니다:
  1. 귀하의 기여물은 프로젝트가 채택한 **Apache License 2.0** 및 **MIT License** 하에 듀얼 라이선스로 공개됩니다.
  2. 귀하는 본인의 기여물에 대해 Apache License 2.0에 명시된 **특허 허여(Patent Grant)** 조항을 준수하며, 사용자들에게 관련 특허 권한을 부여하는 것에 동의합니다.

  이러한 조항은 프로젝트의 무결성을 유지하고 모든 사용자와 기여자를 법적으로 보호하기 위함입니다.
