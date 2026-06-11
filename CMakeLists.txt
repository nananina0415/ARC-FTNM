cmake_minimum_required(VERSION 3.15)
project(ARC_FTNM_WORKSPACE C)

# 1. 크로스 컴파일러 강제 지정 (환경 변수에 mmix-gcc가 등록되어 있어야 합니다)
set(CMAKE_C_COMPILER mmix-gcc)

# 2. 베어메탈/임베디드 환경을 위한 빌드 타겟 명시 (표준 libc 링크 방지 등)
# 퓨어둠은 libc 독립적이지만 mmix-gcc가 기본적으로 집어넣으려는 라이브러리를 차단합니다.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffreestanding -nostdlib")

# 3. 하위 앱 폴더들을 빌드 대상에 포함시킵니다.
add_subdirectory(apps/doom)
