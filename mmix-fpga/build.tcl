# mmix-fpga Vivado 배치 빌드 스크립트
# 사용법:
#   vivado -mode batch -source build.tcl              (합성 + 구현 + 비트스트림)
#   vivado -mode batch -source build.tcl -tclargs upload (+ 보드 프로그래밍)

set SCRIPT_DIR [file dirname [file normalize [info script]]]

# 인자 파싱: argv[0]=보드명, argv[1]=upload 여부
if {[llength $argv] < 1 || [lindex $argv 0] eq ""} {
    error "보드명을 지정하세요. 예: -tclargs PA100T-EDU"
}
set BOARD [lindex $argv 0]
set do_upload 0
if {[llength $argv] > 1 && [lindex $argv 1] eq "upload"} {
    set do_upload 1
}

set BOARD_DIR "$SCRIPT_DIR/board/$BOARD"
if {![file exists "$BOARD_DIR/board.tcl"]} {
    error "board/$BOARD/board.tcl 을 찾을 수 없습니다"
}
source "$BOARD_DIR/board.tcl"

set PROJECT_DIR "$SCRIPT_DIR/vivado/mmix_fpga"
set PROJECT_NAME "mmix_fpga"
set SV_FILE "$SCRIPT_DIR/build/spade.sv"
# XDC_FILE은 board.tcl에서 정의됨
set BIT_FILE "$PROJECT_DIR/${PROJECT_NAME}.runs/impl_1/top.bit"

# 프로젝트 생성 또는 열기
if {![file exists "$PROJECT_DIR/${PROJECT_NAME}.xpr"]} {
    create_project $PROJECT_NAME $PROJECT_DIR -part $PART
}
open_project "$PROJECT_DIR/${PROJECT_NAME}.xpr"

# 소스 갱신: import_files는 파일을 프로젝트 .srcs/ 안으로 복사
if {[llength [get_files -quiet *.sv]] > 0} {
    remove_files -fileset sources_1 [get_files *.sv]
}
import_files -fileset sources_1 $SV_FILE

if {[llength [get_files -quiet *.xdc]] > 0} {
    remove_files -fileset constrs_1 [get_files *.xdc]
}
import_files -fileset constrs_1 $XDC_FILE

# 합성
launch_runs synth_1 -jobs 8
wait_on_run synth_1
if {[get_property PROGRESS [get_runs synth_1]] != "100%"} {
    error "합성 실패"
}

# 구현 + 비트스트림
launch_runs impl_1 -to_step write_bitstream -jobs 8
wait_on_run impl_1
if {[get_property PROGRESS [get_runs impl_1]] != "100%"} {
    error "구현 실패"
}

puts "Bitstream: $BIT_FILE"

# 보드 프로그래밍
if {$do_upload} {
    open_hw_manager
    connect_hw_server -url localhost:3121
    set targets [get_hw_targets]
    if {[llength $targets] == 0} {
        error "JTAG 타겟 없음. VirtualHere 연결 확인."
    }
    open_hw_target [lindex $targets 0]
    set_property PARAM.FREQUENCY 1000000 [lindex $targets 0]
    set_property PROGRAM.BITSTREAM $BIT_FILE [get_hw_devices $HW_DEVICE]
    program_hw_devices [get_hw_devices $HW_DEVICE]
    close_hw_manager
    puts "업로드 완료"
}
