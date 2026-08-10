# PureDOOM.h 생성 스크립트.
# m_swap.h 직후에 MMIX 엔디언 패치(src/patch_puredoom.h)를 삽입한다.
# doom/ 디렉토리에서 실행. 출력 경로는 첫 번째 인자로 지정 (기본: src/PureDOOM.h).


import glob
import re
import os
import sys


DOOM_SRC   = "PureDOOM/src/DOOM"
OUR_SRC    = "src"               # 우리 수정본 우선 탐색 경로
PATCH_FILE = "src/patch_puredoom.h"
DOOM_OUT   = sys.argv[1] if len(sys.argv) > 1 else "src/PureDOOM.h"

files = []
code_files = []
done_files = []
final:str = ""


def resolve(filename):
    """src/ 에 동명 파일이 있으면 그것을, 없으면 원본 경로를 반환."""
    override = os.path.join(OUR_SRC, os.path.basename(filename))
    if os.path.exists(override):
        print("Override: " + os.path.basename(filename))
        return override
    return filename


class File:
    def __init__(self, filename) -> None:
        filename = resolve(filename)
        with open(filename, "r") as file:
            self.content = file.read()

        # 이 파일이 의존하는 헤더 목록 추출
        self.headers = re.findall(r'#include \"(\w+\.h)\"', self.content)

        # #include를 주석 처리 (단일 헤더로 합치므로 중복 include 방지)
        # DOOM.c는 시스템 헤더(<...>)를 포함하므로 따옴표 include만 주석 처리,
        # 나머지 파일은 모든 #include를 주석 처리.
        if os.path.basename(filename) == "DOOM.c":
            self.content = re.sub(r'#include[ \t]+\"', '//#include "', self.content)
        else:
            self.content = re.sub(r'#include', '//#include', self.content)

        # 파일 상단의 저작권 블록 제거 (PureDOOM.h 상단에 통합 저작권 포함)
        # // 로 시작하지 않는 첫 번째 비빈 줄을 찾아 그 앞까지 잘라냄.
        # `:=` 문법: https://docs.python.org/3/whatsnew/3.8.html#assignment-expressions
        if (match := re.search(r'(^|\n)(?!//|\n)', self.content)) is not None:
            # 매치 위치 이전(저작권 블록)을 제거. 매치는 실제 코드 직전 문자를 가리킴.
            self.content = self.content[match.end():]

        # 파일 끝의 $Log:$ 블록 제거
        self.content = re.sub(r'\/\/-----------------------------------------------------------------------------\n\/\/\n\/\/ \$Log\:\$\n\/\/\n\/\/-----------------------------------------------------------------------------', '', self.content)

        self.name = os.path.basename(filename)

    def __repr__(self) -> str:
        return self.name + " -> " + str(self.headers)


# 헤더 파일 수집 (DOOM.h, d_french.h는 별도 처리)
headers = glob.glob(DOOM_SRC + "/*.h")
headers.sort() # 결정론적 순서 보장
for header in headers:
    if header.endswith("DOOM.h") or header.endswith("d_french.h"):
        continue # DOOM.h는 아래서 수동으로 먼저 추가, d_french.h는 스킵
    files.append(File(header))

# 구현 파일 수집
codes = glob.glob(DOOM_SRC + "/*.c")
codes.sort() # 결정론적 순서 보장
for code in codes:
    code_files.append(File(code))

# DOOM.h를 맨 앞에 배치 (공개 API 헤더)
with open(resolve(DOOM_SRC + "/DOOM.h"), "r") as header:
    final += header.read()
print("Concat: DOOM.h")
done_files.append("DOOM.h")

# 의존성이 모두 처리된 헤더부터 순서대로 concat
while len(files) > 0:
    # 아직 처리되지 않은 의존 헤더가 없는 파일을 찾음
    found = False
    for file in files:
        has_dependencies = False
        for header in file.headers:
            if header not in done_files:
                has_dependencies = True
                break
        if has_dependencies:
            continue # 의존성 미충족, 다음 파일 시도
        found = True
        print("Concat: " + file.name)
        final += file.content
        done_files.append(file.name)
        files.remove(file)
        # m_swap.h 직후에 MMIX 엔디언 패치 삽입
        if file.name == "m_swap.h":
            with open(PATCH_FILE, "r") as pf:
                final += "\n" + pf.read()
            print("Inserted: " + PATCH_FILE)
        break
    if not found:
        print("순환 의존성 발생: " + str(files))
        exit(1)

final += "\n#if defined(DOOM_IMPLEMENTATION)\n"

# 구현 파일들을 DOOM_IMPLEMENTATION 블록 안에 추가
for file in code_files:
    print("Concat: " + file.name)
    final += file.content

final += "\n#endif // DOOM_IMPLEMENTATION\n"

# 단일 헤더 출력
with open(DOOM_OUT, "w") as file:
    file.write(final)
print("Written: " + DOOM_OUT)
