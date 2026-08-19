################################
# **사용하는 보드에 맞게 변경할 것** #
################################

# 보드 종속 설정 — PA100T-EDU (xc7a100tfgg484-2)
set PART      "xc7a100tfgg484-2"
set HW_DEVICE "xc7a100t_0"
set XDC_FILE  [file join [file dirname [file normalize [info script]]] pins.xdc]
