# mmix-fpga Vivado 배치 빌드 스크립트
# 사용법: vivado -mode batch -source build.tcl -tclargs <보드명>
#
# 현재 단계: CPU 코어(Cpu, AXI 마스터)를 out-of-context로 합성해 사용량과 타이밍을 보고한다.
# CPU·MIG·클럭·주변장치를 묶는 보드 최상위 모듈이 생기면 구현과 비트스트림 단계를 붙인다.
#
# 입력: src/cpu/sources.f (crosscompiler가 의사코드에서 생성한 Verilog 목록)
# 출력: build/synth/ (보고서와 체크포인트)

set SCRIPT_DIR [file dirname [file normalize [info script]]]

if {[llength $argv] < 1 || [lindex $argv 0] eq ""} {
    error "보드명을 지정하세요. 예: -tclargs PA100T-EDU"
}
set BOARD [lindex $argv 0]
set BOARD_DIR "$SCRIPT_DIR/board/$BOARD"
if {![file exists "$BOARD_DIR/board.tcl"]} {
    error "board/$BOARD/board.tcl 을 찾을 수 없습니다"
}
source "$BOARD_DIR/board.tcl"

set RTL_DIR "$SCRIPT_DIR/src/cpu"
set OUT_DIR "$SCRIPT_DIR/build/synth"
file mkdir $OUT_DIR

# CPU 최상위 모듈과 클럭 포트 (crosscompiler가 만든 이름)
set CPU_TOP   "s_3_cpu_3_Cpu"
set CPU_CLOCK "s_4_port_5_clock"
# CPU는 MIG ui_clk로 동작한다. PA-EDU MIG 설정(PHY 4:1, 400MHz DDR3)에서 ui_clk는 100MHz다.
set CLOCK_PERIOD_NS 10.0

set fp [open "$RTL_DIR/sources.f" r]
set count 0
foreach line [split [read $fp] "\n"] {
    set path [string trim $line]
    if {$path eq ""} { continue }
    read_verilog [file join $RTL_DIR $path]
    incr count
}
close $fp
puts "Verilog 파일 $count 개를 읽었다"

# 합성 단계부터 클럭을 알도록 제약을 XDC로 읽는다.
set xdc "$OUT_DIR/cpu_clock.xdc"
set fp [open $xdc w]
puts $fp "create_clock -name ui_clk -period $CLOCK_PERIOD_NS \[get_ports $CPU_CLOCK\]"
close $fp
read_xdc -mode out_of_context $xdc

synth_design -top $CPU_TOP -part $PART -mode out_of_context

report_utilization                -file "$OUT_DIR/utilization.rpt"
report_utilization -hierarchical  -file "$OUT_DIR/utilization_hier.rpt"
report_timing_summary             -file "$OUT_DIR/timing_summary.rpt"
write_checkpoint -force           "$OUT_DIR/cpu_synth.dcp"

puts "보고서: $OUT_DIR"
