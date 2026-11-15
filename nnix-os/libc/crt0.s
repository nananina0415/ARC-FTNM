	.global _start
	.global _link_cluster
	.global _mem_size
	.global _stack_base
	.global _crash_count

	.section .text.crt0
_start	IS @
	! 로더 전달값 (UNSAVE로 설정)
	!   $0 = 실행파일 클러스터, $1 = 링크파일 클러스터, $2 = RAM 크기(mem_size)
	!   $3 = 연속 크래시 횟수
	!   rS = 두 스택의 기준점. rS-1부터 아래로 프로그램 스택, rS부터 위로 레지스터 스택
	! rS는 spill할 때마다 올라가므로 아무 함수도 부르기 전에 읽는다.
	! 주석은 BIOS가 $253=RAM크기, $254=링크파일클러스터, $255=실행파일클러스터로 넘기던 규약이다.
	!	SET	$10,$254
	!	SET	$11,$253
	SET	$10,$1
	SET	$11,$2
	GET	$12,rS
	SET	$13,$3

	! BSS 초기화 (_bss_start ~ _fix_end)
	SET	$0,0
	GETA	$1,_bss_start
	GETA	$2,_fix_end
_bss_loop	IS @
	CMP	$3,$1,$2
	BNN	$3,_bss_done
	STOU	$0,$1,0
	ADD	$1,$1,8
	JMP	_bss_loop
_bss_done	IS @

	! 프로그램 스택 포인터 초기값 = rS. 첫 프레임은 [rS-N, rS)에 놓인다.
	!	SET	$254,$11
	SET	$254,$12

	! 로더 전달값을 C에서 참조할 수 있도록 저장. 커널 mem이 힙 범위 계산에 쓴다.
	GETA	$0,_link_cluster
	STOU	$10,$0,0
	GETA	$0,_mem_size
	STOU	$11,$0,0
	GETA	$0,_stack_base
	STOU	$12,$0,0
	GETA	$0,_crash_count
	STOU	$13,$0,0

	! 링크파일에서 argc/argv 파싱 (_crt_argc/_crt_argv에 결과 저장)
	PUSHJ	$0,_args_init

	! main(argc, argv) 호출. PUSHJ $2의 인자는 호출 번호 뒤인 $3,$4에 놓는다.
	GETA	$5,_crt_argc
	LDOU	$3,$5,0
	GETA	$5,_crt_argv
	LDOU	$4,$5,0
	PUSHJ	$2,main

	! main 반환값으로 exit. 시뮬레이터는 Halt, FPGA는 nnix.os를 다시 실행한다.
	! $255는 시뮬레이터 Halt가 종료 코드로 읽는다.
	! 주석은 TRAP 0,0,0으로 CPU를 멈추던 코드다.
	!	TRAP	0,0,0
	SET	$255,$2
	SET	$4,$2
	PUSHJ	$3,_crt_exit

	.section .bss
	.align 8
_link_cluster:
	.8byte 0
_mem_size:
	.8byte 0
_stack_base:
	.8byte 0
_crash_count:
	.8byte 0
