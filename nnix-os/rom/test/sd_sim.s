! mmix-sim 테스트용 SD 드라이버. sd.s 대신 링크한다.
! 호스트 파일 disk.img를 디스크로 보고, mmix-sim의 TRAP Fopen/Fseek/Fread로 섹터를 읽는다.
! 인터페이스는 sd.s와 같다.

	.include "rom.inc"

Fopen	IS	1
Fread	IS	3
Fseek	IS	9
BinaryRead	IS	2
DISK	IS	3

	.section .text.rom,"ax"
	.global	sd_init
	.global	sd_read

! $0 ← 0 성공 / 1 실패
sd_init	IS	@
	GET	$0,rJ
	PUSHJ	$1,rom_ram
	PUT	rJ,$0
	GETA	$2,disk_name
	STOU	$2,$1,RAM_SIMIO
	SET	$2,BinaryRead
	STOU	$2,$1,RAM_SIMIO+8
	ADDU	$255,$1,RAM_SIMIO
	TRAP	0,Fopen,DISK
	ZSNZ	$0,$255,1
	POP	1,0

! $0 섹터 번호, $1 버퍼 주소 → $0 ← 0 성공 / 1 실패
sd_read	IS	@
	GET	$2,rJ
	PUSHJ	$3,rom_ram
	PUT	rJ,$2
	SLU	$255,$0,9
	TRAP	0,Fseek,DISK
	BNZ	$255,sim_read_fail
	STOU	$1,$3,RAM_SIMIO
	SETL	$4,512
	STOU	$4,$3,RAM_SIMIO+8
	ADDU	$255,$3,RAM_SIMIO
	TRAP	0,Fread,DISK
	ZSNZ	$0,$255,1
	POP	1,0
sim_read_fail	IS	@
	SET	$0,1
	POP	1,0

disk_name	IS	@
	.asciz	"disk.img"
	.p2align 2
