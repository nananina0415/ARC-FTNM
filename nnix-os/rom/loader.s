! 실행파일을 RAM 0번지에 올리고 UNSAVE로 레지스터 상태를 만든 뒤 0번지로 넘어간다.
! 배치 공식은 nnix-os/kernel/mem.h와 같다.
!   fix_end     = 파일 마지막 8바이트 (BSS 끝, 링커가 기록, 빅엔디언)
!   gap         = (mem_size - fix_end) mod 128
!   unfix_start = fix_end + gap
!   unfix_size  = mem_size - unfix_start
!   rS          = unfix_start + unfix_size*3/4 = mem_size - unfix_size/4

	.include "rom.inc"

	.section .text.rom,"ax"
	.global	load_and_run

! $0 실행파일 시작 클러스터, $1 링크파일 클러스터, $2 연속 크래시 횟수
! 성공하면 돌아오지 않는다. 실패하면 $0 ← 오류 코드.
load_and_run	IS	@
	GET	$3,rJ
	PUSHJ	$4,rom_ram
	! $4 작업 영역, $5 파일 크기, $6 적재 주소, $7 fix_end, $8 클러스터,
	! $9 클러스터 안 섹터 번호, $10 임시, $11 호출 위치
	! 파일 크기: 링크파일과 같은 방식으로 루트 디렉토리에서 시작 클러스터가 같은 항목을 찾는다.
	SET	$12,1
	SET	$13,$0
	PUSHJ	$11,fat_find
	BZ	$11,load_no_exe
	LDOU	$5,$4,RAM_FILE_SIZE
	! 크기 검사: 8 이상, 8의 배수(마지막 8바이트가 정렬된 fix_end), 작업 영역 아래에 들어갈 것
	CMPU	$10,$5,8
	BN	$10,load_bad_image
	AND	$10,$5,7
	BNZ	$10,load_bad_image
	SET	$10,$5
	INCL	$10,511
	ANDNL	$10,511
	CMPU	$10,$10,$4
	BP	$10,load_no_mem
	! 클러스터 체인을 따라 0번지부터 섹터 단위로 읽는다.
	SET	$6,0
	SET	$8,$0
load_cluster	IS	@
	CMPU	$10,$6,$5
	BNN	$10,load_done
	CMPU	$10,$8,2
	BN	$10,load_read_fail
	SETML	$10,FAT_EOC>>16
	INCL	$10,FAT_EOC&0xFFFF
	CMPU	$10,$8,$10
	BNN	$10,load_read_fail
	SET	$9,0
load_sector	IS	@
	CMPU	$10,$6,$5
	BNN	$10,load_done
	LDOU	$10,$4,RAM_SPC
	CMPU	$10,$9,$10
	BNN	$10,load_next
	SET	$12,$8
	PUSHJ	$11,fat_lba
	ADDU	$12,$11,$9
	SET	$13,$6
	PUSHJ	$11,sd_read
	BNZ	$11,load_read_fail
	INCL	$6,512
	ADD	$9,$9,1
	JMP	load_sector
load_next	IS	@
	SET	$12,$8
	PUSHJ	$11,fat_next
	SET	$8,$11
	JMP	load_cluster
load_done	IS	@
	! fix_end 검사: 파일 끝 이상, 8바이트 정렬, mem_size 미만
	SUBU	$10,$5,8
	LDOU	$7,$10,0
	CMPU	$10,$7,$5
	BN	$10,load_bad_image
	AND	$10,$7,7
	BNZ	$10,load_bad_image
	! mem_size = 작업 영역 시작 + ROM_RAM_SIZE
	SETL	$9,ROM_RAM_SIZE
	ADDU	$9,$4,$9
	CMPU	$10,$7,$9
	BNN	$10,load_no_mem
	! $9 mem_size, $6 unfix_start, $10 unfix_size/4, $8 rS
	SUBU	$10,$9,$7
	AND	$10,$10,127
	ADDU	$6,$7,$10
	SUBU	$10,$9,$6
	SRU	$10,$10,2
	SUBU	$8,$9,$10
	! UNSAVE 컨텍스트가 rS ~ mem_size 안에 들어가야 한다.
	SETL	$11,CTX_SIZE
	CMPU	$11,$10,$11
	BN	$11,load_no_mem
	! UNSAVE 컨텍스트 (rS부터 위로)
	!   로컬 $0 실행파일 클러스터, $1 링크파일 클러스터, $2 mem_size, $3 크래시 횟수
	!   hole: 로컬 개수 4
	!   전역 $APP_G..$255 = 0
	!   rB rD rE rH rJ rM rR rP rW rX rY rZ = 0
	!   (rG<<56)|rA, rA = 0
	! UNSAVE 뒤에는 rO = rS = 컨텍스트 시작 주소, rL = 4가 된다.
	STOU	$0,$8,0
	STOU	$1,$8,8
	STOU	$9,$8,16
	STOU	$2,$8,24
	SET	$10,APP_LOCALS
	STOU	$10,$8,32
	ADDU	$10,$8,40
	SETL	$11,(256-APP_G)+12
	SET	$12,0
load_ctx_zero	IS	@
	STOU	$12,$10,0
	ADDU	$10,$10,8
	SUB	$11,$11,1
	BP	$11,load_ctx_zero
	SETH	$12,APP_G<<8
	STOU	$12,$10,0
	UNSAVE	$10
	! 전역 $255는 UNSAVE로 0이 되었으므로 0번지로 간다.
	GO	$255,$255,0
load_no_exe	IS	@
	SET	$0,ERR_NO_EXE
	JMP	load_ret
load_read_fail	IS	@
	SET	$0,ERR_READ
	JMP	load_ret
load_bad_image	IS	@
	SET	$0,ERR_IMAGE
	JMP	load_ret
load_no_mem	IS	@
	SET	$0,ERR_MEM
load_ret	IS	@
	PUT	rJ,$3
	POP	1,0
