	.global _start
	.global _link_cluster
	.global _ram_size

	.section .text.crt0
_start	IS @
	! BIOS 전달값: $253=RAM크기, $254=링크파일클러스터, $255=실행파일클러스터
	! $254는 스택 포인터로 곧 덮어쓰므로 먼저 보존
	SET	$10,$254
	SET	$11,$253

	! BSS 초기화
	SET	$0,0
	GETA	$1,_bss_start
	GETA	$2,_heap_start
_bss_loop	IS @
	CMP	$3,$1,$2
	BNN	$3,_bss_done
	STOU	$0,$1,0
	ADD	$1,$1,8
	JMP	_bss_loop
_bss_done	IS @

	! 스택 포인터 초기값 = RAM 크기 (스택은 아래로 자라므로 첫 push 전 SP = 마지막 유효 주소+1)
	SET	$254,$11

	! 힙/스택 경계를 C에서 참조할 수 있도록 저장
	GETA	$0,_link_cluster
	STOU	$10,$0,0
	GETA	$0,_ram_size
	STOU	$11,$0,0

	! main 호출 (인자 파싱은 추후 _args_init에서)
	SET	$0,0
	SET	$1,0
	PUSHJ	$2,main

	SET	$255,$2
	TRAP	0,0,0

	.section .bss
	.align 8
_link_cluster:
	.8byte 0
_ram_size:
	.8byte 0
