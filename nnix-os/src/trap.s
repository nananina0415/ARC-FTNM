	.global trap_handler

	.text
trap_handler	IS @
	! rYY,rZZ는 $Y,$Z의 레지스터 내용이라 즉치로 넣은 syscall번호/옵션이 아니다 —
	! rXX(이 TRAP 명령 인코딩 그대로: opcode:8,X:8,Y:8,Z:8)에서 Y,Z 바이트를 직접 뽑는다.
	! rBB=사용자 $255(인자)
	GET	$4,rXX
	SETL	$5,0xFF
	AND	$1,$4,$5	! opt = Z바이트 = rXX & 0xFF
	SRU	$0,$4,8
	AND	$0,$0,$5	! sc  = Y바이트 = (rXX>>8) & 0xFF
	GET	$2,rBB

	! trap_dispatch(sc, opt, r255)
	PUSHJ	$3,trap_dispatch

	! rJ 복원 — TRAP이 $255에 백업해둔 트랩 전 rJ를 되돌린다
	PUT	rJ,$255

	! 반환값 → rBB; RESUME 1 이 rBB를 $255로 복원
	PUT	rBB,$3

	! rK 복원값 준비 — 전체 인터럽트 활성화(전부 1). RESUME 1이 이 $255를 rK로 옮긴다.
	SETL	$255,0
	SUBU	$255,$255,1

	RESUME	1
