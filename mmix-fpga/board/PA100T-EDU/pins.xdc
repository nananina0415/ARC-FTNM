# PA100T-EDU (xc7a100t FGG484) 핀 제약
# 출처: 튜토리얼 XDC + 회로도 분석

# ─── 빌드 설정 ────────────────────────────────────────────────────────────────
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property BITSTREAM.GENERAL.COMPRESS true [current_design]
set_property BITSTREAM.CONFIG.CONFIGRATE 50 [current_design]
set_property BITSTREAM.CONFIG.SPI_BUSWIDTH 4 [current_design]
set_property BITSTREAM.CONFIG.SPI_FALL_EDGE Yes [current_design]

# ─── 시스템 클럭 / 리셋 ──────────────────────────────────────────────────────
# N측(R3)은 Vivado가 패키지에서 자동 추론
set_property -dict {PACKAGE_PIN R4  IOSTANDARD DIFF_SSTL15} [get_ports sys_clk_p]
set_property -dict {PACKAGE_PIN R14 IOSTANDARD LVCMOS33}    [get_ports sys_rstn]
create_clock -period 5.000 -name sys_clk [get_ports sys_clk_p]

# ─── HDMI (IT6613 인코더, TMDS 단방향 출력) ──────────────────────────────────
# N측 핀은 Vivado가 패키지 데이터에서 자동 추론
set_property -dict {PACKAGE_PIN V17  IOSTANDARD TMDS_33} [get_ports hdmi_d2_p]
set_property -dict {PACKAGE_PIN AA19 IOSTANDARD TMDS_33} [get_ports hdmi_d1_p]
set_property -dict {PACKAGE_PIN V18  IOSTANDARD TMDS_33} [get_ports hdmi_d0_p]
set_property -dict {PACKAGE_PIN Y18  IOSTANDARD TMDS_33} [get_ports hdmi_clk_p]

# ─── SD 카드 (SDIO 6핀 / SPI 4핀 하위 호환) ─────────────────────────────────
# SPI 모드: sd_clk=CLK, sd_data3=NCS, sd_cmd=MOSI, sd_data0=MISO
# SDIO 모드: 위 4핀 + sd_data1, sd_data2 추가
set_property -dict {PACKAGE_PIN AA20 IOSTANDARD LVCMOS33} [get_ports sd_clk]
set_property -dict {PACKAGE_PIN AA21 IOSTANDARD LVCMOS33} [get_ports sd_data3]
set_property -dict {PACKAGE_PIN AB21 IOSTANDARD LVCMOS33} [get_ports sd_cmd]
set_property -dict {PACKAGE_PIN AB18 IOSTANDARD LVCMOS33} [get_ports sd_data0]
set_property -dict {PACKAGE_PIN AA18 IOSTANDARD LVCMOS33} [get_ports sd_data1]
set_property -dict {PACKAGE_PIN AB22 IOSTANDARD LVCMOS33} [get_ports sd_data2]

# ─── USB 호스트 (m1nl_usb_hid_host, J11 IIC 헤더 전용) ──────────────────────
# J11 핀 배정: SCL(N13)→D+, SDA(N14)→D−
# 커넥터에 27Ω 직렬 저항 + 3.6V 제너 권장 (USB 전기적 보호)
set_property -dict {PACKAGE_PIN N13 IOSTANDARD LVCMOS33} [get_ports usb_dp]
set_property -dict {PACKAGE_PIN N14 IOSTANDARD LVCMOS33} [get_ports usb_dm]

# ─── UART (CH340E USB-UART 브리지, 디버그 콘솔) ──────────────────────────────
set_property -dict {PACKAGE_PIN P15 IOSTANDARD LVCMOS33} [get_ports uart_tx]
set_property -dict {PACKAGE_PIN P14 IOSTANDARD LVCMOS33} [get_ports uart_rx]

# ─── DDR3 (MIG IP 자동 생성 XDC가 담당) ─────────────────────────────────────
# mig_a.prj 로부터 Vivado MIG Wizard가 제약 생성. 중복 지정하지 않음.

# ─── 7세그먼트 LED (SEG1) ────────────────────────────────────────────────────
# seg1_led 활성 고, seg1_sel 활성 고 1-hot
set_property -dict {PACKAGE_PIN F13 IOSTANDARD LVCMOS33} [get_ports {seg1_led[0]}]
set_property -dict {PACKAGE_PIN F14 IOSTANDARD LVCMOS33} [get_ports {seg1_led[1]}]
set_property -dict {PACKAGE_PIN F16 IOSTANDARD LVCMOS33} [get_ports {seg1_led[2]}]
set_property -dict {PACKAGE_PIN E17 IOSTANDARD LVCMOS33} [get_ports {seg1_led[3]}]
set_property -dict {PACKAGE_PIN C14 IOSTANDARD LVCMOS33} [get_ports {seg1_led[4]}]
set_property -dict {PACKAGE_PIN C15 IOSTANDARD LVCMOS33} [get_ports {seg1_led[5]}]
set_property -dict {PACKAGE_PIN E13 IOSTANDARD LVCMOS33} [get_ports {seg1_led[6]}]
set_property -dict {PACKAGE_PIN E14 IOSTANDARD LVCMOS33} [get_ports {seg1_led[7]}]
set_property -dict {PACKAGE_PIN E16 IOSTANDARD LVCMOS33} [get_ports {seg1_sel[0]}]
set_property -dict {PACKAGE_PIN D16 IOSTANDARD LVCMOS33} [get_ports {seg1_sel[1]}]
set_property -dict {PACKAGE_PIN D14 IOSTANDARD LVCMOS33} [get_ports {seg1_sel[2]}]
set_property -dict {PACKAGE_PIN D15 IOSTANDARD LVCMOS33} [get_ports {seg1_sel[3]}]

# ─── 디버그: 버튼 / LED ───────────────────────────────────────────────────────
set_property -dict {PACKAGE_PIN W21 IOSTANDARD LVCMOS33} [get_ports {key[0]}]
set_property -dict {PACKAGE_PIN Y21 IOSTANDARD LVCMOS33} [get_ports {key[1]}]
set_property -dict {PACKAGE_PIN U21 IOSTANDARD LVCMOS33} [get_ports {key[2]}]

set_property -dict {PACKAGE_PIN J21 IOSTANDARD LVCMOS33} [get_ports {led[0]}]
set_property -dict {PACKAGE_PIN J19 IOSTANDARD LVCMOS33} [get_ports {led[1]}]
set_property -dict {PACKAGE_PIN H19 IOSTANDARD LVCMOS33} [get_ports {led[2]}]
set_property -dict {PACKAGE_PIN K18 IOSTANDARD LVCMOS33} [get_ports {led[3]}]
set_property -dict {PACKAGE_PIN K19 IOSTANDARD LVCMOS33} [get_ports {led[4]}]
set_property -dict {PACKAGE_PIN L19 IOSTANDARD LVCMOS33} [get_ports {led[5]}]
