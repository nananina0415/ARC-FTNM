	.global _os_start

	.text
_os_start	IS @
	! rT = trap_handler 설정 후 공통 _start로 점프
	GETA	$0,trap_handler
	PUT	rT,$0
	GETA	$0,_start
	GO	$1,$0,0
