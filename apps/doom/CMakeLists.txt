cmake_minimum_required(VERSION 3.15)
project(mmix_doom C ASM)

option(MMIX_SIM "mmixware 시뮬레이터용 빌드" OFF)

set(LIBC_DIR   ${CMAKE_CURRENT_SOURCE_DIR}/../../nnix-os/libc)
set(SRC_DIR    ${CMAKE_CURRENT_SOURCE_DIR}/../../nnix-os/src)
set(DRIVER_DIR      ${CMAKE_CURRENT_SOURCE_DIR}/../../drivers/PA100T-EDU)
set(FPGA_DRIVER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../drivers/fpga)

# 커스텀 libc 소스 목록
set(LIBC_SOURCES
    ${LIBC_DIR}/stdio.c
    ${LIBC_DIR}/_print.c
    ${LIBC_DIR}/stdlib.c
    ${LIBC_DIR}/string.c
    ${LIBC_DIR}/crt0.s
    ${SRC_DIR}/mem.c
)

# 시뮬레이터 빌드는 test_on_sim.c, FPGA 빌드는 main.c 사용
# 두 빌드 모두 보드 드라이버 video.c 포함 (MMIX_SIM 분기로 PPM↔MMIO 분리)
if(MMIX_SIM)
    set(DOOM_SOURCES src/test_on_sim.c ${DRIVER_DIR}/video.c)
else()
    set(DOOM_SOURCES
        src/main.c
        ${DRIVER_DIR}/video.c
        ${DRIVER_DIR}/memmap.c
        ${DRIVER_DIR}/usb.c
        ${FPGA_DRIVER_DIR}/memmap.c
        ${FPGA_DRIVER_DIR}/audio.c
    )
endif()

add_executable(doom ${DOOM_SOURCES} ${LIBC_SOURCES})

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
    ${SRC_DIR}
)

target_compile_definitions(doom PRIVATE
    $<$<BOOL:${MMIX_SIM}>:MMIX_SIM>
    __BIG_ENDIAN__          # MMIX는 빅엔디언; patch_puredoom.h의 #ifdef 가드에 사용
)

# 표준 라이브러리 없이 빌드 (커스텀 libc 사용)
target_compile_options(doom PRIVATE -ffreestanding -nostdlib -fomit-frame-pointer -fno-jump-tables)
target_link_options(doom PRIVATE -nostdlib)

if(NOT MMIX_SIM)
    # FPGA 빌드: 링커 스크립트로 메모리 레이아웃 지정
    target_link_options(doom PRIVATE
        -T ${CMAKE_CURRENT_SOURCE_DIR}/fpga.ld
    )
endif()
