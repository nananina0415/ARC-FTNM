	.global _os_start

	.section .text.crt1
_os_start	IS @
	! OS는 앱과 같이 커널 라이브러리를 정적 링크해 직접 호출하므로 트랩 핸들러가 없다.
	! 주석은 rT에 트랩 핸들러를 설정하던 코드다.
	!	GETA	$0,trap_handler
	!	PUT	rT,$0
	! 로더가 UNSAVE로 넘긴 $0~$3을 crt0이 읽어야 하므로 레지스터를 쓰지 않고 넘어간다.
	JMP	_start
