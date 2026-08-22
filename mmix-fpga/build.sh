#!/usr/bin/env bash
# 사용법: build.sh [보드명] [upload]
#   build.sh                  — 보드가 하나면 자동 선택
#   build.sh PA100T-EDU       — 보드 지정 (앞부분 일치도 허용)
#   build.sh PA100T-EDU upload
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
ARG2="${2:-}"

# ARG1이 비어있거나 "upload"이면 보드 인자 없는 것으로 처리
if [ -z "$ARG1" ] || [ "$ARG1" = "upload" ]; then
    DO_UPLOAD="$ARG1"
    if [ ${#BOARDS[@]} -eq 1 ]; then
        BOARD="${BOARDS[0]}"
        echo "[build] 보드 자동 선택: $BOARD"
    else
        echo "[error] 보드명을 지정하세요. 사용 가능: ${BOARDS[*]}"
        exit 1
    fi
else
    DO_UPLOAD="$ARG2"
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
    VIVADO=$(find /opt/Xilinx /tools/Xilinx -name "vivado" -type f 2>/dev/null \
             | sort -V | tail -1)
    if [ -z "$VIVADO" ]; then
        echo "[error] Vivado를 찾을 수 없습니다"
        exit 1
    fi
fi

# 1. Chisel → SystemVerilog
echo "[build] sbt run..."
cd "$SCRIPT_DIR"
sbt run

# upload 전 JTAG 연결 확인: 로컬 USB 먼저, 없으면 VirtualHere
if [ "$DO_UPLOAD" = "upload" ]; then
    if lsusb 2>/dev/null | grep -qiE "digilent|0403:601[04]"; then
        : # 로컬 USB에서 Digilent 또는 FTDI JTAG 발견
    elif pgrep -xE "vhclient|vhuit64|vhuit32" > /dev/null 2>&1; then
        : # VirtualHere 클라이언트로 연결
    elif pgrep -a "vhci" > /dev/null 2>&1; then
        : # USB/IP vhci 커널 활성
    else
        echo "[error] JTAG 장치를 찾을 수 없습니다 (로컬 USB 또는 VirtualHere 필요)"
        exit 1
    fi
fi

# 2. Vivado: 합성 + 구현 + 비트스트림 (upload 인자 있으면 보드 업로드까지)
echo "[build] vivado..."
"$VIVADO" -mode batch \
    -log     "$SCRIPT_DIR/vivado/vivado.log" \
    -journal "$SCRIPT_DIR/vivado/vivado.jou" \
    -source  "$SCRIPT_DIR/build.tcl" \
    -tclargs "$BOARD" "$DO_UPLOAD"

echo "[build] done."
