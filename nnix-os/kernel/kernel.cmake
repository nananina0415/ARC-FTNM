# 커널 정적 라이브러리. OS와 앱이 include()한 뒤 target_link_libraries(... nnix_kernel)로 링크한다.
# FPGA 빌드 전용. 시뮬레이터 빌드는 이 라이브러리 대신 mmixware TRAP을 쓴다.

if(NOT TARGET nnix_kernel)

set(NNIX_KERNEL_DIR ${CMAKE_CURRENT_LIST_DIR})
set(NNIX_OS_DIR     ${NNIX_KERNEL_DIR}/..)
set(NNIX_DRIVERS    ${NNIX_OS_DIR}/../drivers)
set(NNIX_FATFS      ${NNIX_OS_DIR}/third_party/fatfs)

add_library(nnix_kernel STATIC
    ${NNIX_KERNEL_DIR}/sys.c
    ${NNIX_KERNEL_DIR}/fs.c
    ${NNIX_KERNEL_DIR}/mem.c
    ${NNIX_KERNEL_DIR}/proc.c
    ${NNIX_KERNEL_DIR}/diskio.c          # third_party/fatfs/diskio.c 대신 사용

    # FatFs (diskio.c 제외)
    ${NNIX_FATFS}/ff.c
    ${NNIX_FATFS}/ffunicode.c
    ${NNIX_FATFS}/ffsystem.c

    # 보드 드라이버
    ${NNIX_DRIVERS}/PA100T-EDU/memmap.c
    ${NNIX_DRIVERS}/PA100T-EDU/video.c
    ${NNIX_DRIVERS}/PA100T-EDU/usb.c
    ${NNIX_DRIVERS}/PA100T-EDU/sd_card.c
    ${NNIX_DRIVERS}/PA100T-EDU/timer.c

    # FPGA 공통 드라이버
    ${NNIX_DRIVERS}/fpga/memmap.c
    ${NNIX_DRIVERS}/fpga/audio.c
)

target_include_directories(nnix_kernel
    PUBLIC
        ${NNIX_KERNEL_DIR}
        ${NNIX_OS_DIR}/third_party
    PRIVATE
        ${NNIX_OS_DIR}/libc
        ${NNIX_DRIVERS}
)

target_compile_options(nnix_kernel PRIVATE
    -ffreestanding -nostdlib -fomit-frame-pointer -fno-jump-tables
)

endif()
