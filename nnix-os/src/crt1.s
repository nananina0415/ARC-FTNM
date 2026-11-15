	.global _os_start

	.section .text.crt1
_os_start	IS @
	! OS는 앱과 같이 커널 라이브러리를 정적 링크해 직접 호출하므로 트랩 핸들러가 없다.
	! 주석은 rT에 트랩 핸들러를 설정하던 코드다.
	!	GETA	$0,trap_handler
	!	PUT	rT,$0
	GETA	$0,_start
	GO	$1,$0,0
