cmake_minimum_required(VERSION 3.15)
project(mmix_doom C ASM)

option(MMIX_SIM "mmixware 시뮬레이터용 빌드" OFF)

add_custom_target(notice_doom ALL
    COMMAND python3
        "${CMAKE_SOURCE_DIR}/tools/gen_license_notice.py"
        "${CMAKE_CURRENT_SOURCE_DIR}/notice-map.json"
        "${CMAKE_CURRENT_BINARY_DIR}/NOTICE"
    COMMENT "Generating NOTICE for doom"
)

set(LIBC_DIR   ${CMAKE_CURRENT_SOURCE_DIR}/../../nnix-os/libc)
set(KERNEL_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../nnix-os/kernel)
set(DRIVER_DIR      ${CMAKE_CURRENT_SOURCE_DIR}/../../drivers/PA100T-EDU)

# 커스텀 libc 소스 목록. args.c는 crt0.s가 호출하는 _args_init을 정의한다.
set(LIBC_SOURCES
    ${LIBC_DIR}/stdio.c
    ${LIBC_DIR}/_print.c
    ${LIBC_DIR}/stdlib.c
    ${LIBC_DIR}/string.c
    ${LIBC_DIR}/args.c
    ${LIBC_DIR}/crt0.s
)

# 시뮬레이터 빌드는 test_on_sim.c, FPGA 빌드는 main.c 사용
# FPGA 빌드는 커널 라이브러리(파일 시스템, 드라이버, 메모리)를 정적 링크한다.
# 시뮬레이터 빌드는 mmixware TRAP을 쓰므로 커널 대신 video.c(PPM 출력)와 mem.c만 넣는다.
if(MMIX_SIM)
    set(DOOM_SOURCES src/test_on_sim.c ${DRIVER_DIR}/video.c ${KERNEL_DIR}/mem.c)
else()
    set(DOOM_SOURCES src/main.c)
    include(${KERNEL_DIR}/kernel.cmake)
endif()

add_executable(doom ${DOOM_SOURCES} ${LIBC_SOURCES})
if(NOT MMIX_SIM)
    target_link_libraries(doom PRIVATE nnix_kernel)
endif()

# gen_puredoom_h.py로 PureDOOM.h 재생성: m_swap.h 직후에 src/patch_puredoom.h 삽입
# (PureDOOM 서브모듈을 수정하지 않고 MMIX 64비트 엔디언 버그를 우회)
# 출력은 빌드 디렉토리에 생성 — 서브모듈 안에 쓰지 않음
set(PUREDOOM_H_OUT ${CMAKE_CURRENT_BINARY_DIR}/PureDOOM.h)
add_custom_target(puredoom_header
    COMMAND python3 ${CMAKE_CURRENT_SOURCE_DIR}/gen_puredoom_h.py ${PUREDOOM_H_OUT}
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
    BYPRODUCTS ${PUREDOOM_H_OUT}
    COMMENT "PureDOOM.h 재생성 (MMIX 엔디언 패치 포함)"
)
add_dependencies(doom puredoom_header)

target_include_directories(doom PRIVATE
    ${CMAKE_CURRENT_BINARY_DIR}  # 생성된 PureDOOM.h 우선 탐색
    PureDOOM                     # 원본 서브모듈 (나머지 헤더들)
    ${LIBC_DIR}
    ${KERNEL_DIR}
)

target_compile_definitions(doom PRIVATE
    $<$<BOOL:${MMIX_SIM}>:MMIX_SIM>
    __BIG_ENDIAN__          # MMIX는 빅엔디언; patch_puredoom.h의 #ifdef 가드에 사용
    # PureDOOM 선택 기능 (PureDOOM/src/DOOM/doom_config.h 설명). 정의하지 않으면 원본 동작.
    DOOM_AUTOMAP_NO_FOLLOW  # 자동 지도 따라가기를 쓰지 않고 직접 이동만
    DOOM_WEAPON_CYCLE       # 이전/다음 무기 전환 키 ([ / ])
    DOOM_AUTOMAP_PAUSE      # 자동 지도가 열린 동안 게임 진행 멈춤
)

# 표준 라이브러리 없이 빌드 (커스텀 libc 사용)
target_compile_options(doom PRIVATE -ffreestanding -nostdlib -fomit-frame-pointer -fno-jump-tables)
target_link_options(doom PRIVATE -nostdlib)

if(NOT MMIX_SIM)
    # FPGA 빌드: 링커 스크립트로 메모리 레이아웃 지정
    target_link_options(doom PRIVATE
        -T ${CMAKE_CURRENT_SOURCE_DIR}/link.ld
    )
endif()
