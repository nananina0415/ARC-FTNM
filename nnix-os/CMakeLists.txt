cmake_minimum_required(VERSION 3.15)
project(nnix_os C ASM)

add_custom_target(notice_nnix_os ALL
    COMMAND python3
        "${CMAKE_SOURCE_DIR}/tools/gen_license_notice.py"
        "${CMAKE_CURRENT_SOURCE_DIR}/notice-map.json"
        "${CMAKE_CURRENT_BINARY_DIR}/NOTICE"
    COMMENT "Generating NOTICE for nnix-os"
)

add_custom_command(
    OUTPUT  ${CMAKE_CURRENT_BINARY_DIR}/unifont.h
    COMMAND python3
        "${CMAKE_CURRENT_SOURCE_DIR}/tools/gen_font.py"
        "${CMAKE_CURRENT_BINARY_DIR}/unifont.h"
        WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/res/unifont-17.0.05.bdf
            ${CMAKE_CURRENT_SOURCE_DIR}/tools/gen_font.py
    COMMENT "Generating unifont.h"
)
add_custom_target(unifont_h DEPENDS ${CMAKE_CURRENT_BINARY_DIR}/unifont.h)

set(FATFS_DIR   ${CMAKE_CURRENT_SOURCE_DIR}/third_party/fatfs)
set(DRIVER_DIR  ${CMAKE_CURRENT_SOURCE_DIR}/../drivers/PA100T-EDU)
set(FPGA_DIR    ${CMAKE_CURRENT_SOURCE_DIR}/../drivers/fpga)

set(OS_SOURCES
    src/crt1.s
    src/main.c
    src/trap.s
    src/trap.c
    src/fs.c
    src/mem.c
    src/proc.c
    src/diskio.c                  # third_party/fatfs/diskio.c 대신 사용

    # FatFs (diskio.c 제외)
    ${FATFS_DIR}/ff.c
    ${FATFS_DIR}/ffunicode.c
    ${FATFS_DIR}/ffsystem.c

    # 보드 드라이버
    ${DRIVER_DIR}/memmap.c
    ${DRIVER_DIR}/video.c
    ${DRIVER_DIR}/usb.c
    ${DRIVER_DIR}/sd_card.c

    # FPGA 공통 드라이버
    ${FPGA_DIR}/memmap.c
    ${FPGA_DIR}/audio.c
)

add_executable(nnix_os ${OS_SOURCES})
add_dependencies(nnix_os unifont_h)

target_include_directories(nnix_os PRIVATE
    src
    ${CMAKE_CURRENT_BINARY_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
    ${CMAKE_CURRENT_SOURCE_DIR}/../drivers
)

target_compile_options(nnix_os PRIVATE
    -ffreestanding -nostdlib -fomit-frame-pointer -fno-jump-tables
)
target_link_options(nnix_os PRIVATE
    -nostdlib
    -T ${CMAKE_CURRENT_SOURCE_DIR}/link.ld
)
