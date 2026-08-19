#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

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

# swim PATH 로드
source ~/.cargo/env

# 1. Spade → SystemVerilog
echo "[build] swim build..."
cd "$SCRIPT_DIR"
swim build

# upload 전 JTAG 연결 확인: 로컬 USB 먼저, 없으면 VirtualHere
if [ "${1:-}" = "upload" ]; then
    if lsusb 2>/dev/null | grep -qi "digilent"; then
        : # 로컬 USB에서 발견
    elif pgrep -x vhclient > /dev/null 2>&1; then
        : # VirtualHere로 연결
    else
        echo "[error] JTAG 장치를 찾을 수 없습니다 (로컬 USB 또는 VirtualHere 필요)"
        exit 1
    fi
fi

# 2. Vivado: 합성 + 구현 + 비트스트림 (upload 인자 있으면 보드 업로드까지)
echo "[build] vivado..."
"$VIVADO" -mode batch -source "$SCRIPT_DIR/build.tcl" -tclargs "$@" \
    -log "$SCRIPT_DIR/vivado/vivado.log" \
    -journal "$SCRIPT_DIR/vivado/vivado.jou"

echo "[build] done."
