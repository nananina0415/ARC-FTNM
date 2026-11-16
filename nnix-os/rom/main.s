! BIOS ROM 진입점. ROM 시작 주소(MMIO 블록 0, 2^48)에 놓인다.
!
! 두 경로로 들어온다.
!   리셋: $255 < 2. 루트 디렉토리의 NNIX.OS를 올린다. CPU 리셋 시 $255가 0이라고 본다.
!   execve: proc_execve가 GO로 ROM 시작 주소에 들어온다.
!     $255 = 실행파일 시작 클러스터, $254 = 링크파일 클러스터, $253 = 연속 크래시 횟수
!     FAT32 클러스터 번호는 2부터라서 $255 값으로 두 경로를 구분한다.
! 두 경로 모두 SD 초기화와 FAT32 마운트를 거쳐 loader.s의 load_and_run으로 간다.
! 실패하면 $255에 오류 코드(rom.inc ERR_*)를 넣고 TRAP 0,0,0으로 멈춘다.

	.include "rom.inc"

	.section .text.rom.entry,"ax"
	.global	rom_entry
	.global	rom_ram
	.global	rom_mem_size

rom_entry	IS	@
	! 전달값을 로컬로 옮기고 나머지 로컬을 버린다(PUT rL은 줄이기만 한다).
	SET	$0,$255
	SET	$1,$254
	SET	$2,$253
	PUT	rL,3
	! $0 실행파일 클러스터, $1 링크파일 클러스터, $2 크래시 횟수, $3 호출 위치
	PUSHJ	$3,sd_init
	BNZ	$3,fail_sd
	PUSHJ	$3,fat_mount
	BNZ	$3,fail_mount
	CMPU	$3,$0,2
	BNN	$3,run
	PUSHJ	$3,boot_find_os
	BZ	$3,fail_no_os
	SET	$0,$3
	SET	$1,0
	SET	$2,0
run	IS	@
	SET	$4,$0
	SET	$5,$1
	SET	$6,$2
	PUSHJ	$3,load_and_run
	! load_and_run은 성공하면 돌아오지 않고, 실패하면 오류 코드를 돌려준다.
	SET	$255,$3
	JMP	halt
fail_sd	IS	@
	SET	$255,ERR_SD
	JMP	halt
fail_mount	IS	@
	SET	$255,ERR_MOUNT
	JMP	halt
fail_no_os	IS	@
	SET	$255,ERR_NO_OS
halt	IS	@
	TRAP	0,0,0
	JMP	halt

! $0 ← ROM 작업 영역 시작 주소 (mem_size - ROM_RAM_SIZE)
rom_ram	IS	@
	GETA	$0,rom_mem_size
	LDOU	$0,$0,0
	SETL	$1,ROM_RAM_SIZE
	SUBU	$0,$0,$1
	POP	1,0

! RAM 크기. MEM_SIZE는 보드 드라이버의 board.ld가 정한다(rom.ld가 INCLUDE).
	.p2align 3
rom_mem_size	IS	@
	.8byte	MEM_SIZE
