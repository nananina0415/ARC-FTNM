	.global _start

	! Pool Segment dummy GREG: 링커가 이 GREG을 g[254]에 할당하게 하여
	! DATA GREG($253)이 g[253]에 정착하도록 강제. G=253이 되어야
	! crt0의 SETH $254(스택포인터 설정)가 g[253](DATA GREG)을 건드리지 않음.
	GREG	#4000000000000000

	.text
_start	IS @
	! C 스택 포인터 초기화
	! Pool Segment(0x4000_0000_0000_0000) 에서 256KB 위를 스택 탑으로 사용
	SETH	$254,#4000
	INCML	$254,4

	! $255 = _execve_t* { cluster@0, argc@8, argv@16 }
	LDTS	$0,$255,8	! argc (int, signed 32-bit)
	LDO	$1,$255,16	! argv (char**)
	PUSHJ	$2,main

	! 종료
	SET	$255,$2
	TRAP	0,0,0
