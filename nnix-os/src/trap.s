	.global trap_handler

	.text
trap_handler	IS @
	! 커널 스택으로 전환
	SETH	$254,#4000
	INCMH	$254,#8000	! 0x4000_8000_0000_0000 — 커널 스택 별도 영역

	! rYY=syscall 번호, rZZ=옵션, rBB=사용자 $255(인자)
	GET	$0,rYY
	GET	$1,rZZ
	GET	$2,rBB

	! trap_dispatch(sc, opt, r255)
	PUSHJ	$3,trap_dispatch

	! 반환값 → rBB; RESUME 1 이 rBB를 $255로 복원
	PUT	rBB,$3
	RESUME	1
