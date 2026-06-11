cmake_minimum_required(VERSION 3.15)
project(mmix_doom C)

# 1. 컴파일할 소스코드 파일들을 지정합니다.
# (puredoom은 싱글 헤더 라이브러리이므로 main.c 안에서 #include 할 것이라 c파일로 등록 안 해도 됩니다)
set(DOOM_SOURCES
    src/main.c
)

# 2. 실행파일(타겟) 이름을 'doom.elf' (또는 doom)로 정의하고 소스를 연결합니다.
add_executable(doom ${DOOM_SOURCES})

# 3. 컴파일러가 puredoom 폴더 안의 'PureDOOM.h'를 찾을 수 있도록 헤더 경로를 추가합니다.
target_include_directories(doom PRIVATE puredoom)

# 4. MMIX 타겟에 필요한 특수 링커 스크립트나 옵션이 있다면 여기에 추가합니다.
# target_link_options(doom PRIVATE "-T" "${CMAKE_CURRENT_SOURCE_DIR}/linker.ld")
