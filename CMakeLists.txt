cmake_minimum_required(VERSION 3.15)
project(arc_ftnm C ASM)

# FPGA 빌드: nnix-os와 앱을 함께 빌드한다. 둘 다 nnix-os/kernel의 커널 정적 라이브러리를 링크한다.
#   cmake -B build -DCMAKE_TOOLCHAIN_FILE=apps/doom/toolchain/mmix.cmake
#   cmake --build build
# 시뮬레이터 빌드(MMIX_SIM=ON)는 앱 폴더에서 따로 빌드한다. apps/doom/README.md 참고.
option(MMIX_SIM "mmixware 시뮬레이터용 빌드" OFF)

if(NOT MMIX_SIM)
    add_subdirectory(nnix-os)
endif()
add_subdirectory(apps/doom)
