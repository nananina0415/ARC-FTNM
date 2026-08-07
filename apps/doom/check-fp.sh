#!/usr/bin/env bash
# MMIX 바이너리에서 부동소수점 명령어 검출
# 사용법: ./check-fp.sh <binary_or_object_file> [mmix-objdump-path]
set -euo pipefail

TARGET="${1:-}"
OBJDUMP="${2:-mmix-knuth-mmixware-objdump}"

if [ -z "$TARGET" ]; then
    echo "사용법: $0 <파일> [objdump경로]"
    echo ""
    echo "MMIX FP 명령어 opcode 범위:"
    echo "  산술: FADD(04) FSUB(06) FMUL(10) FDIV(14) FSQRT(15) FREM(16) FINT(17)"
    echo "  비교: FCMP(01) FUN(02) FEQL(03) FCMPE(11) FUNE(12) FEQLE(13)"
    echo "  변환: FIX(05) FIXU(07) FLOT(08-0B) SFLOT(0C-0F)"
    exit 1
fi

# FP 관련 니모닉 목록
FP_MNEMONICS="FADD|FSUB|FMUL|FDIV|FSQRT|FREM|FINT|FCMP|FUN|FEQL|FCMPE|FUNE|FEQLE|FIX|FIXU|FLOT|FLOTU|SFLOT|SFLOTU"

echo "=== MMIX FP 명령어 검사: $TARGET ==="
echo ""

# objdump 방식 (ELF 또는 mmo, mmix-objdump 필요)
if command -v "$OBJDUMP" &>/dev/null; then
    echo "[objdump 방식] $OBJDUMP 사용"
    DISASM=$("$OBJDUMP" -d "$TARGET" 2>/dev/null)
    HITS=$(echo "$DISASM" | grep -iE "^\s+[0-9a-f]+:.*($FP_MNEMONICS)" || true)
    if [ -z "$HITS" ]; then
        echo "결과: FP 명령어 없음. 정수 연산만 사용 확인."
    else
        echo "경고: FP 명령어 발견!"
        echo "$HITS"
        FP_COUNT=$(echo "$HITS" | wc -l)
        echo ""
        echo "총 $FP_COUNT 개 FP 명령어"
    fi
else
    # raw 바이트 방식 (objdump 없을 때 fallback)
    # MMIX 명령어는 4바이트 big-endian, 최상위 바이트가 opcode
    echo "[raw 방식] $OBJDUMP 없음 — 바이트 스캔으로 대체"
    echo "(주의: 코드/데이터 구분 불가. 오탐 가능)"
    echo ""

    python3 - "$TARGET" <<'PYEOF'
import sys, struct

FP_OPCODES = {
    0x01: "FCMP",  0x02: "FUN",   0x03: "FEQL",
    0x04: "FADD",  0x05: "FIX",   0x06: "FSUB",  0x07: "FIXU",
    0x08: "FLOT",  0x09: "FLOTI", 0x0A: "FLOTU", 0x0B: "FLOTUI",
    0x0C: "SFLOT", 0x0D: "SFLOTI",0x0E: "SFLOTU",0x0F: "SFLOTUI",
    0x10: "FMUL",  0x11: "FCMPE", 0x12: "FUNE",  0x13: "FEQLE",
    0x14: "FDIV",  0x15: "FSQRT", 0x16: "FREM",  0x17: "FINT",
}

with open(sys.argv[1], "rb") as f:
    data = f.read()

hits = []
for i in range(0, len(data) - 3, 4):
    opcode = data[i]
    if opcode in FP_OPCODES:
        x, y, z = data[i+1], data[i+2], data[i+3]
        hits.append((i, opcode, FP_OPCODES[opcode], x, y, z))

if not hits:
    print("결과: FP opcode 없음 (raw 스캔 기준)")
else:
    print(f"경고: FP opcode {len(hits)}개 발견 (코드/데이터 미구분)")
    print(f"{'오프셋':>10}  {'opcode':>6}  {'명령어':<10}  X   Y   Z")
    for offset, op, name, x, y, z in hits:
        print(f"0x{offset:08x}  0x{op:02x}     {name:<10}  ${x}  ${y}  ${z}")
PYEOF
fi

echo ""
echo "=== 검사 완료 ==="
