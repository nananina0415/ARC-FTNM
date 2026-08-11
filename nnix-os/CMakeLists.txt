cmake_minimum_required(VERSION 3.15)
project(nnix_os C ASM)

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

target_include_directories(nnix_os PRIVATE
    src
    ${FATFS_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../drivers
)

target_compile_options(nnix_os PRIVATE
    -ffreestanding -nostdlib -fomit-frame-pointer -fno-jump-tables
)
target_link_options(nnix_os PRIVATE
    -nostdlib
    -T ${CMAKE_CURRENT_SOURCE_DIR}/fpga.ld
)
