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

# 커널(파일 시스템, 드라이버, 메모리, 앱 실행)은 앱과 같은 정적 라이브러리를 쓴다.
include(${CMAKE_CURRENT_SOURCE_DIR}/kernel/kernel.cmake)

set(OS_SOURCES
    src/crt1.s
    libc/crt0.s
    libc/args.c
    libc/_print.c
    libc/stdio.c
    libc/stdlib.c
    libc/string.c
    libc/unistd.c
    src/main.c
    # src/trap.s.bak, src/trap.c.bak: 상주 OS의 트랩 핸들러. 빌드하지 않는다.
)

add_executable(nnix_os ${OS_SOURCES})
add_dependencies(nnix_os unifont_h)
target_link_libraries(nnix_os PRIVATE nnix_kernel)

target_include_directories(nnix_os PRIVATE
    src
    libc
    ${CMAKE_CURRENT_BINARY_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../drivers
)

target_compile_options(nnix_os PRIVATE
    -ffreestanding -nostdlib -fomit-frame-pointer -fno-jump-tables
)
target_link_options(nnix_os PRIVATE
    -nostdlib
    -T ${CMAKE_CURRENT_SOURCE_DIR}/link.ld
)
