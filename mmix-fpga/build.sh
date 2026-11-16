#!/usr/bin/env bash
# 사용법: build.sh [보드명]
#   build.sh                  — 보드가 하나면 자동 선택
#   build.sh PA100T-EDU       — 보드 지정 (앞부분 일치도 허용)
#
# 1. crosscompiler로 의사코드(pseudo/)에서 CPU Verilog(src/cpu/)를 생성한다.
# 2. Vivado로 CPU를 out-of-context 합성하고 사용량·타이밍 보고서를 build/synth/에 낸다.
# 보드 최상위 모듈이 생기면 구현·비트스트림·업로드 단계를 붙인다.
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# board/ 하위 디렉토리 목록 수집
BOARDS=()
for d in "$SCRIPT_DIR/board/"/*/; do
    [ -f "$d/board.tcl" ] && BOARDS+=("$(basename "$d")")
done

if [ ${#BOARDS[@]} -eq 0 ]; then
    echo "[error] board/ 에 보드가 없습니다"
    exit 1
fi

ARG1="${1:-}"

if [ -z "$ARG1" ]; then
    if [ ${#BOARDS[@]} -eq 1 ]; then
        BOARD="${BOARDS[0]}"
        echo "[build] 보드 자동 선택: $BOARD"
    else
        echo "[error] 보드명을 지정하세요. 사용 가능: ${BOARDS[*]}"
        exit 1
    fi
else
    # 정확히 일치하는 보드 먼저, 없으면 앞부분 일치
    BOARD=""
    for b in "${BOARDS[@]}"; do
        [ "$b" = "$ARG1" ] && BOARD="$b" && break
    done
    if [ -z "$BOARD" ]; then
        MATCHED=()
        for b in "${BOARDS[@]}"; do
            [[ "$b" == "$ARG1"* ]] && MATCHED+=("$b")
        done
        if [ ${#MATCHED[@]} -eq 1 ]; then
            BOARD="${MATCHED[0]}"
        elif [ ${#MATCHED[@]} -gt 1 ]; then
            echo "[error] '$ARG1' 에 일치하는 보드가 여러 개입니다: ${MATCHED[*]}"
            exit 1
        else
            echo "[error] '$ARG1' 에 일치하는 보드가 없습니다. 사용 가능: ${BOARDS[*]}"
            exit 1
        fi
    fi
fi

# Vivado 자동 탐지: PATH → /opt/Xilinx → /tools/Xilinx 순서
if command -v vivado > /dev/null 2>&1; then
    VIVADO=$(command -v vivado)
else
    VIVADO=$(find /opt/Xilinx /tools/Xilinx -path "*/bin/vivado" -type f 2>/dev/null \
             | sort -V | tail -1)
    if [ -z "$VIVADO" ]; then
        echo "[error] Vivado를 찾을 수 없습니다"
        exit 1
    fi
fi

# 1. 의사코드 → Verilog
echo "[build] crosscompiler..."
cd "$SCRIPT_DIR"
python3 -m crosscompiler pseudo --top cpu::Cpu --out src/cpu

# 2. Vivado: CPU out-of-context 합성
echo "[build] vivado..."
mkdir -p "$SCRIPT_DIR/build"
"$VIVADO" -mode batch \
    -log     "$SCRIPT_DIR/build/vivado.log" \
    -journal "$SCRIPT_DIR/build/vivado.jou" \
    -source  "$SCRIPT_DIR/build.tcl" \
    -tclargs "$BOARD"

echo "[build] done. 보고서: $SCRIPT_DIR/build/synth/"
