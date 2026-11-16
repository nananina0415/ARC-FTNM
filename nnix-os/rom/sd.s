! MicroSD (SPI 모드) 초기화와 섹터 읽기. drivers/PA100T-EDU/sd_card.c와 같은 절차다.
!
! PA100T-EDU SPI 마스터 MMIO (블록 17, 주소 2^48 + 17*4096)
!   0x00 DATA    쓰기: TX 바이트 전송 시작, 읽기: 직전 RX 바이트
!   0x08 STATUS  bit0: 전송 중
!   0x10 CS      1 = 어서트(low), 0 = 디어서트(high)
!   0x18 CLKDIV  SPI 클럭 = 시스템 클럭 / (2*(CLKDIV+1))

	.include "rom.inc"

SPI_DATA	IS	0x00
SPI_STATUS	IS	0x08
SPI_CS	IS	0x10
SPI_CLKDIV	IS	0x18
CLKDIV_INIT	IS	74
CLKDIV_FAST	IS	1

	.section .text.rom,"ax"
	.global	sd_init
	.global	sd_read

! $0 ← SPI 레지스터 시작 주소
spi_regs	IS	@
	SETH	$0,1
	INCML	$0,1
	INCL	$0,0x1000
	POP	1,0

! $0 TX 바이트 → $0 ← RX 바이트
spi_xfer	IS	@
	GET	$1,rJ
	PUSHJ	$2,spi_regs
	PUT	rJ,$1
	AND	$0,$0,0xFF
	STOU	$0,$2,SPI_DATA
spi_busy	IS	@
	LDOU	$3,$2,SPI_STATUS
	AND	$3,$3,1
	BNZ	$3,spi_busy
	LDOU	$0,$2,SPI_DATA
	AND	$0,$0,0xFF
	POP	1,0

! CS 어서트
cs_on	IS	@
	GET	$0,rJ
	PUSHJ	$1,spi_regs
	PUT	rJ,$0
	SET	$2,1
	STOU	$2,$1,SPI_CS
	POP	0,0

! CS 디어서트 후 여분 클럭 1바이트
cs_off	IS	@
	GET	$0,rJ
	PUSHJ	$1,spi_regs
	SET	$2,0
	STOU	$2,$1,SPI_CS
	SET	$2,0xFF
	PUSHJ	$1,spi_xfer
	PUT	rJ,$0
	POP	0,0

! $0 명령 번호, $1 인자(32비트), $2 CRC → $0 ← R1 응답
! R1은 bit7이 0이 될 때까지 최대 8바이트 기다린다.
sd_cmd	IS	@
	GET	$3,rJ
	! $4 남은 대기 횟수, $5 호출 위치
	OR	$6,$0,0x40
	PUSHJ	$5,spi_xfer
	SRU	$6,$1,24
	PUSHJ	$5,spi_xfer
	SRU	$6,$1,16
	PUSHJ	$5,spi_xfer
	SRU	$6,$1,8
	PUSHJ	$5,spi_xfer
	SET	$6,$1
	PUSHJ	$5,spi_xfer
	OR	$6,$2,1
	PUSHJ	$5,spi_xfer
	SET	$4,8
cmd_poll	IS	@
	SET	$6,0xFF
	PUSHJ	$5,spi_xfer
	AND	$6,$5,0x80
	BZ	$6,cmd_done
	SUB	$4,$4,1
	BP	$4,cmd_poll
cmd_done	IS	@
	SET	$0,$5
	PUT	rJ,$3
	POP	1,0

! $0 ← 0 성공 / 1 실패
sd_init	IS	@
	GET	$0,rJ
	! $1 SPI 레지스터, $2 반복 횟수, $3 응답, $4 ACMD41 HCS 인자(0이 아니면 SD v2),
	! $5 호출 위치
	PUSHJ	$5,spi_regs
	SET	$1,$5
	SET	$2,CLKDIV_INIT
	STOU	$2,$1,SPI_CLKDIV
	SET	$2,0
	STOU	$2,$1,SPI_CS
	! CS 디어서트 상태로 74클럭 이상
	SET	$2,10
init_dummy	IS	@
	SET	$6,0xFF
	PUSHJ	$5,spi_xfer
	SUB	$2,$2,1
	BP	$2,init_dummy
	! CMD0: SPI 모드 진입. R1 = 1(idle)이어야 한다.
	PUSHJ	$5,cs_on
	SET	$6,0
	SET	$7,0
	SET	$8,0x95
	PUSHJ	$5,sd_cmd
	SET	$3,$5
	PUSHJ	$5,cs_off
	CMP	$3,$3,1
	BNZ	$3,init_fail
	! CMD8: R1 = 1이고 R7 에코가 0xAA면 SD v2
	SET	$4,0
	PUSHJ	$5,cs_on
	SET	$6,8
	SETL	$7,0x1AA
	SET	$8,0x87
	PUSHJ	$5,sd_cmd
	CMP	$5,$5,1
	BNZ	$5,init_cmd8_end
	SET	$6,0xFF
	PUSHJ	$5,spi_xfer
	SET	$6,0xFF
	PUSHJ	$5,spi_xfer
	SET	$6,0xFF
	PUSHJ	$5,spi_xfer
	SET	$6,0xFF
	PUSHJ	$5,spi_xfer
	CMP	$5,$5,0xAA
	BNZ	$5,init_cmd8_end
	SETML	$4,0x4000
init_cmd8_end	IS	@
	PUSHJ	$5,cs_off
	! ACMD41(CMD55 + CMD41): R1 = 0이 될 때까지 최대 10000번
	SETL	$2,10000
init_acmd41	IS	@
	PUSHJ	$5,cs_on
	SET	$6,55
	SET	$7,0
	SET	$8,0x65
	PUSHJ	$5,sd_cmd
	PUSHJ	$5,cs_off
	PUSHJ	$5,cs_on
	SET	$6,41
	SET	$7,$4
	SET	$8,0xFF
	PUSHJ	$5,sd_cmd
	SET	$3,$5
	PUSHJ	$5,cs_off
	BZ	$3,init_ready
	SUB	$2,$2,1
	BP	$2,init_acmd41
	JMP	init_fail
init_ready	IS	@
	! CMD58(SD v2만): OCR 첫 바이트의 CCS(0x40)가 1이면 SDHC(섹터 주소)
	SET	$3,0
	BZ	$4,init_sdsc
	PUSHJ	$5,cs_on
	SET	$6,58
	SET	$7,0
	SET	$8,0xFD
	PUSHJ	$5,sd_cmd
	BNZ	$5,init_cmd58_end
	SET	$6,0xFF
	PUSHJ	$5,spi_xfer
	AND	$5,$5,0x40
	ZSNZ	$3,$5,1
	SET	$6,0xFF
	PUSHJ	$5,spi_xfer
	SET	$6,0xFF
	PUSHJ	$5,spi_xfer
	SET	$6,0xFF
	PUSHJ	$5,spi_xfer
init_cmd58_end	IS	@
	PUSHJ	$5,cs_off
	BNZ	$3,init_fast
init_sdsc	IS	@
	! SDSC: CMD16으로 블록 크기를 512바이트로 고정
	PUSHJ	$5,cs_on
	SET	$6,16
	SETL	$7,512
	SET	$8,0xFF
	PUSHJ	$5,sd_cmd
	SET	$2,$5
	PUSHJ	$5,cs_off
	BNZ	$2,init_fail
init_fast	IS	@
	PUSHJ	$5,rom_ram
	STOU	$3,$5,RAM_SDHC
	SET	$2,CLKDIV_FAST
	STOU	$2,$1,SPI_CLKDIV
	PUT	rJ,$0
	SET	$0,0
	POP	1,0
init_fail	IS	@
	PUT	rJ,$0
	SET	$0,1
	POP	1,0

! $0 섹터 번호, $1 버퍼 주소(512바이트) → $0 ← 0 성공 / 1 실패
sd_read	IS	@
	GET	$2,rJ
	! $3 임시, $4 명령 주소, $5 반복 횟수/바이트 위치, $6 호출 위치
	PUSHJ	$6,rom_ram
	LDOU	$3,$6,RAM_SDHC
	SET	$4,$0
	BNZ	$3,read_addr
	SLU	$4,$0,9
read_addr	IS	@
	PUSHJ	$6,cs_on
	SET	$7,17
	SET	$8,$4
	SET	$9,0xFF
	PUSHJ	$6,sd_cmd
	BNZ	$6,read_fail
	! 데이터 토큰 0xFE를 최대 200000바이트 기다린다.
	SETML	$5,3
	INCL	$5,0x0D40
read_token	IS	@
	SET	$7,0xFF
	PUSHJ	$6,spi_xfer
	CMP	$6,$6,0xFE
	BZ	$6,read_data
	SUB	$5,$5,1
	BP	$5,read_token
	JMP	read_fail
read_data	IS	@
	SET	$5,0
read_byte	IS	@
	SET	$7,0xFF
	PUSHJ	$6,spi_xfer
	STBU	$6,$1,$5
	ADD	$5,$5,1
	SETL	$3,512
	CMP	$3,$5,$3
	BN	$3,read_byte
	! CRC 2바이트는 버린다.
	SET	$7,0xFF
	PUSHJ	$6,spi_xfer
	SET	$7,0xFF
	PUSHJ	$6,spi_xfer
	PUSHJ	$6,cs_off
	PUT	rJ,$2
	SET	$0,0
	POP	1,0
read_fail	IS	@
	PUSHJ	$6,cs_off
	PUT	rJ,$2
	SET	$0,1
	POP	1,0
