! nnix 실행파일(tools/mkexe.py 형식)을 적재하고 UNSAVE로 레지스터 상태를 만든 뒤 0번지로 넘어간다.
!   [헤더 512B][이미지: 0번지부터][레지스터 영역: rS부터, UNSAVE 컨텍스트]
! 배치 공식은 nnix-os/kernel/mem.h와 같다.
!   fix_end     = 헤더의 BSS 포함 길이
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
	! $4 작업 영역, $5 파일 크기, $6 파일 안 위치(섹터 단위), $7 임시, $8 클러스터,
	! $9 클러스터 안 섹터 번호, $10 섹터 LBA, $11 fix_end, $12 이미지 길이, $13 레지스터 영역 길이,
	! $14 mem_size, $15 rS, $16 헤더 크기(512), $17 $18 $19 임시, $20 호출 위치
	SETL	$14,ROM_RAM_SIZE
	ADDU	$14,$4,$14
	SETL	$16,EXE_HEADER_SIZE
	! 파일 크기: 링크파일과 같은 방식으로 루트 디렉토리에서 시작 클러스터가 같은 항목을 찾는다.
	SET	$21,1
	SET	$22,$0
	PUSHJ	$20,fat_find
	BZ	$20,load_no_exe
	LDOU	$5,$4,RAM_FILE_SIZE
	CMPU	$7,$5,$16
	BN	$7,load_bad_image
	! 클러스터 체인을 따라 섹터 단위로 읽는다. 첫 섹터는 헤더다.
	SET	$6,0
	SET	$8,$0
load_cluster	IS	@
	CMPU	$7,$6,$5
	BNN	$7,load_done
	CMPU	$7,$8,2
	BN	$7,load_read_fail
	SETML	$7,FAT_EOC>>16
	INCL	$7,FAT_EOC&0xFFFF
	CMPU	$7,$8,$7
	BNN	$7,load_read_fail
	SET	$9,0
load_sector	IS	@
	CMPU	$7,$6,$5
	BNN	$7,load_done
	LDOU	$7,$4,RAM_SPC
	CMPU	$7,$9,$7
	BNN	$7,load_next
	SET	$21,$8
	PUSHJ	$20,fat_lba
	ADDU	$10,$20,$9
	BZ	$6,load_header
	SUBU	$7,$6,$16
	CMPU	$7,$7,$12
	BN	$7,load_image
	JMP	load_regs
load_advance	IS	@
	ADDU	$6,$6,$16
	ADD	$9,$9,1
	JMP	load_sector
load_next	IS	@
	SET	$21,$8
	PUSHJ	$20,fat_next
	SET	$8,$20
	JMP	load_cluster

! 이미지 섹터: 메모리 (파일 위치 - 512)에 바로 읽는다.
load_image	IS	@
	SET	$21,$10
	SUBU	$22,$6,$16
	PUSHJ	$20,sd_read
	BNZ	$20,load_read_fail
	JMP	load_advance

! 레지스터 영역 섹터: 버퍼에 읽어 rS + (영역 안 위치)로 남은 길이만큼 복사한다.
load_regs	IS	@
	SET	$21,$10
	SET	$22,$4
	INCL	$22,RAM_SECBUF
	PUSHJ	$20,sd_read
	BNZ	$20,load_read_fail
	SUBU	$7,$5,$6
	CMPU	$17,$7,$16
	CSP	$7,$17,$16
	SUBU	$18,$6,$16
	SUBU	$18,$18,$12
	ADDU	$18,$15,$18
	SET	$19,$4
	INCL	$19,RAM_SECBUF
load_regs_copy	IS	@
	BZ	$7,load_advance
	LDOU	$17,$19,0
	STOU	$17,$18,0
	ADDU	$19,$19,8
	ADDU	$18,$18,8
	SUBU	$7,$7,8
	JMP	load_regs_copy

! 헤더 섹터: 값을 검사하고 메모리 배치(rS)를 계산한다.
load_header	IS	@
	SET	$21,$10
	SET	$22,$4
	INCL	$22,RAM_SECBUF
	PUSHJ	$20,sd_read
	BNZ	$20,load_read_fail
	SET	$19,$4
	INCL	$19,RAM_SECBUF
	! 매직 "NNIXEXE\0"
	LDOU	$7,$19,EXE_MAGIC
	SETH	$17,0x4E4E
	INCMH	$17,0x4958
	INCML	$17,0x4558
	INCL	$17,0x4500
	CMPU	$7,$7,$17
	BNZ	$7,load_bad_image
	LDOU	$7,$19,EXE_HEADER
	CMPU	$7,$7,$16
	BNZ	$7,load_bad_image
	LDOU	$11,$19,EXE_FIX_END
	LDOU	$12,$19,EXE_IMAGE
	LDOU	$13,$19,EXE_REGS
	! 파일 크기 = 헤더 + 이미지 + 레지스터 영역
	ADDU	$7,$16,$12
	ADDU	$7,$7,$13
	CMPU	$7,$7,$5
	BNZ	$7,load_bad_image
	! 이미지 길이는 512의 배수, fix_end는 8바이트 정렬이고 이미지 끝(패딩 전)보다 작지 않다.
	AND	$7,$12,511
	BNZ	$7,load_bad_image
	AND	$7,$11,7
	BNZ	$7,load_bad_image
	ADDU	$7,$11,511
	CMPU	$7,$7,$12
	BN	$7,load_bad_image
	! 레지스터 영역 길이: 8의 배수, G가 32~255인 범위
	AND	$7,$13,7
	BNZ	$7,load_bad_image
	CMPU	$7,$13,REGS_MIN
	BN	$7,load_bad_image
	SETL	$17,REGS_MAX
	CMPU	$7,$13,$17
	BP	$7,load_bad_image
	! 메모리 배치 (nnix-os/kernel/mem.h)
	CMPU	$7,$11,$14
	BNN	$7,load_no_mem
	SUBU	$7,$14,$11
	AND	$7,$7,127
	ADDU	$17,$11,$7
	SUBU	$7,$14,$17
	SRU	$7,$7,2
	SUBU	$15,$14,$7
	! 이미지는 rS 아래, 레지스터 영역은 ROM 작업 영역 아래에 들어가야 한다(적재 중 버퍼를 덮지 않도록).
	CMPU	$7,$12,$15
	BP	$7,load_no_mem
	ADDU	$7,$15,$13
	CMPU	$7,$7,$4
	BP	$7,load_no_mem
	JMP	load_advance

load_done	IS	@
	! 레지스터 영역 마지막 octa = (G<<56)|rA. 길이가 (274-G)*8과 맞는지 확인한다.
	ADDU	$19,$15,$13
	SUBU	$19,$19,8
	LDOU	$7,$19,0
	SRU	$7,$7,56
	SETL	$17,REGS_BASE_OCTAS
	SUBU	$17,$17,$7
	SLU	$17,$17,3
	CMPU	$17,$17,$13
	BNZ	$17,load_bad_image
	! 로컬 $0~$3과 hole을 채우고, 진입 GO에 쓰는 전역 $255를 0으로 둔다.
	!   $0 실행파일 클러스터, $1 링크파일 클러스터, $2 mem_size, $3 크래시 횟수, hole = 4
	!   $255는 컨텍스트 끝에서 특수 12개와 rG/rA 앞 칸 (끝 - 14 octa)
	STOU	$0,$15,0
	STOU	$1,$15,8
	STOU	$14,$15,16
	STOU	$2,$15,24
	SET	$7,APP_LOCALS
	STOU	$7,$15,32
	SET	$7,0
	SUBU	$17,$19,13*8
	STOU	$7,$17,0
	! UNSAVE 뒤에는 rO = rS = 컨텍스트 시작 주소, rL = 4, rG와 전역은 실행파일 값이 된다.
	UNSAVE	$19
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
