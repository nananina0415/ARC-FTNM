! ROM 테스트용 실행파일. nnix-os/link.ld로 RAW 링크해서 디스크 이미지에 넣는다.
! ROM이 넘긴 상태를 한 줄로 출력하고 멈춘다(16진수, 공백 구분).
!   ID $0 $1 $2 $3 rL rS rO rG _fix_end 데이터합
! 어셈블할 때 --defsym PAYLOAD_ID=...를 주고, 데이터 블록은 data.bin(.incbin)이다.

	.global	_start

	.section .text.crt0,"ax"
_start	IS	@
	! UNSAVE 직후 값을 다른 레지스터를 쓰기 전에 읽는다.
	! $4에 쓰면 그 명령에서 이미 L이 5가 되므로 rL은 전역 $255로 받는다.
	GET	$255,rL
	SET	$4,$255
	GET	$5,rS
	GET	$6,rO
	GET	$7,rG
	! 데이터 블록 바이트 합
	GETA	$8,data_block
	GETA	$9,data_end
	SET	$10,0
sum_loop	IS	@
	CMPU	$11,$8,$9
	BNN	$11,sum_done
	LDBU	$11,$8,0
	ADDU	$10,$10,$11
	ADDU	$8,$8,1
	JMP	sum_loop
sum_done	IS	@
	GETA	$20,line
	SET	$22,$20
	SET	$23,PAYLOAD_ID
	PUSHJ	$21,hex
	SET	$22,$21
	SET	$23,$0
	PUSHJ	$21,hex
	SET	$22,$21
	SET	$23,$1
	PUSHJ	$21,hex
	SET	$22,$21
	SET	$23,$2
	PUSHJ	$21,hex
	SET	$22,$21
	SET	$23,$3
	PUSHJ	$21,hex
	SET	$22,$21
	SET	$23,$4
	PUSHJ	$21,hex
	SET	$22,$21
	SET	$23,$5
	PUSHJ	$21,hex
	SET	$22,$21
	SET	$23,$6
	PUSHJ	$21,hex
	SET	$22,$21
	SET	$23,$7
	PUSHJ	$21,hex
	SET	$22,$21
	GETA	$23,_fix_end
	PUSHJ	$21,hex
	SET	$22,$21
	SET	$23,$10
	PUSHJ	$21,hex
	SET	$11,10
	STBU	$11,$21,0
	SET	$11,0
	STBU	$11,$21,1
	SET	$255,$20
	TRAP	0,7,1
	SET	$255,0
	TRAP	0,0,0

! $0 쓸 위치, $1 값 → 16자리 16진수와 공백을 쓰고 $0 ← 다음 위치
hex	IS	@
	SET	$2,60
hex_loop	IS	@
	SRU	$3,$1,$2
	AND	$3,$3,15
	CMP	$4,$3,10
	BN	$4,hex_digit
	ADD	$3,$3,39
hex_digit	IS	@
	ADD	$3,$3,48
	STBU	$3,$0,0
	ADD	$0,$0,1
	SUB	$2,$2,4
	BNN	$2,hex_loop
	SET	$3,32
	STBU	$3,$0,0
	ADD	$0,$0,1
	POP	1,0

	.section .data
data_block	IS	@
	.incbin	"data.bin"
data_end	IS	@

	.section .bss
	.p2align 3
line	IS	@
	.skip	256
	.skip	4000
