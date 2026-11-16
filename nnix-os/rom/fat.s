! FAT32 읽기 전용 최소 구현: 마운트, 클러스터 체인, 루트 디렉토리 검색.
! 섹터 크기는 512바이트만 지원한다. 디스크 필드는 리틀엔디언이라 바이트 단위로 읽는다.

	.include "rom.inc"

	.section .text.rom,"ax"
	.global	fat_mount
	.global	fat_next
	.global	fat_lba
	.global	fat_find

! $0 ← 0 성공 / 1 실패
! 0번 섹터의 첫 파티션 항목이 FAT32(0x0B/0x0C)면 그 파티션을, 아니면 0번 섹터를 볼륨으로 본다.
fat_mount	IS	@
	GET	$0,rJ
	PUSHJ	$1,rom_ram
	! $1 작업 영역, $2 섹터 버퍼, $3 볼륨 시작 LBA, $4 $5 임시, $6 호출 위치
	SET	$2,$1
	INCL	$2,RAM_SECBUF
	NEG	$3,0,1
	STOU	$3,$1,RAM_FAT_CACHED
	SET	$3,0
	SET	$7,0
	SET	$8,$2
	PUSHJ	$6,sd_read
	BNZ	$6,mount_fail
	PUSHJ	$6,check_sig
	BNZ	$6,mount_fail
	SET	$5,$2
	INCL	$5,0x1C2
	LDBU	$4,$5,0
	CMP	$5,$4,0x0B
	BZ	$5,mount_mbr
	CMP	$5,$4,0x0C
	BNZ	$5,mount_vbr
mount_mbr	IS	@
	SET	$7,$2
	INCL	$7,0x1C6
	PUSHJ	$6,le32
	SET	$3,$6
	SET	$7,$3
	SET	$8,$2
	PUSHJ	$6,sd_read
	BNZ	$6,mount_fail
	PUSHJ	$6,check_sig
	BNZ	$6,mount_fail
mount_vbr	IS	@
	! BPB: BytsPerSec(0x0B) = 512, SecPerClus(0x0D), RsvdSecCnt(0x0E), NumFATs(0x10),
	!      FATSz16(0x16) = 0, FATSz32(0x24), RootClus(0x2C)
	ADDU	$7,$2,0x0B
	PUSHJ	$6,le16
	SETL	$5,512
	CMP	$6,$6,$5
	BNZ	$6,mount_fail
	ADDU	$7,$2,0x16
	PUSHJ	$6,le16
	BNZ	$6,mount_fail
	LDBU	$4,$2,0x0D
	BZ	$4,mount_fail
	STOU	$4,$1,RAM_SPC
	ADDU	$7,$2,0x0E
	PUSHJ	$6,le16
	ADDU	$3,$3,$6
	STOU	$3,$1,RAM_FAT_LBA
	LDBU	$4,$2,0x10
	BZ	$4,mount_fail
	ADDU	$7,$2,0x24
	PUSHJ	$6,le32
	BZ	$6,mount_fail
	MULU	$5,$4,$6
	ADDU	$5,$3,$5
	STOU	$5,$1,RAM_DATA_LBA
	ADDU	$7,$2,0x2C
	PUSHJ	$6,le32
	STOU	$6,$1,RAM_ROOT_CLUS
	PUT	rJ,$0
	SET	$0,0
	POP	1,0
mount_fail	IS	@
	PUT	rJ,$0
	SET	$0,1
	POP	1,0

! 섹터 버퍼 끝 서명 0x55 0xAA 확인. $0 ← 0 일치 / 1 불일치
check_sig	IS	@
	GET	$0,rJ
	PUSHJ	$1,rom_ram
	PUT	rJ,$0
	INCL	$1,RAM_SECBUF+510
	LDBU	$2,$1,0
	LDBU	$3,$1,1
	SLU	$2,$2,8
	OR	$2,$2,$3
	SETL	$3,0x55AA
	CMP	$2,$2,$3
	ZSNZ	$0,$2,1
	POP	1,0

! $0 클러스터 → $0 ← 그 클러스터의 첫 섹터 LBA
fat_lba	IS	@
	GET	$1,rJ
	PUSHJ	$2,rom_ram
	PUT	rJ,$1
	LDOU	$3,$2,RAM_SPC
	SUBU	$0,$0,2
	MULU	$0,$0,$3
	LDOU	$3,$2,RAM_DATA_LBA
	ADDU	$0,$0,$3
	POP	1,0

! $0 클러스터 → $0 ← 다음 클러스터 (하위 28비트). 읽기 실패면 1(무효 클러스터).
! FAT 섹터 하나를 RAM_FATBUF에 캐시한다.
fat_next	IS	@
	GET	$1,rJ
	PUSHJ	$2,rom_ram
	! $2 작업 영역, $3 FAT 섹터 LBA, $4 임시, $5 호출 위치
	SRU	$3,$0,7
	LDOU	$4,$2,RAM_FAT_LBA
	ADDU	$3,$3,$4
	LDOU	$4,$2,RAM_FAT_CACHED
	CMP	$4,$4,$3
	BZ	$4,next_cached
	SET	$6,$3
	SET	$7,$2
	INCL	$7,RAM_FATBUF
	PUSHJ	$5,sd_read
	BNZ	$5,next_fail
	STOU	$3,$2,RAM_FAT_CACHED
next_cached	IS	@
	AND	$4,$0,127
	SLU	$4,$4,2
	SET	$6,$2
	INCL	$6,RAM_FATBUF
	ADDU	$6,$6,$4
	PUSHJ	$5,le32
	ANDNML	$5,0xF000
	SET	$0,$5
	PUT	rJ,$1
	POP	1,0
next_fail	IS	@
	SET	$0,1
	PUT	rJ,$1
	POP	1,0

! 루트 디렉토리에서 파일 항목을 찾는다.
!   $0 = 0: $1이 가리키는 11바이트 8.3 이름과 같은 항목
!   $0 = 1: 시작 클러스터가 $1인 항목
! $0 ← 시작 클러스터 (없거나 읽기 실패면 0). 찾으면 파일 크기를 RAM_FILE_SIZE에 쓴다.
! 삭제된 항목(0xE5), LFN 항목(속성 0x0F), 볼륨 레이블·디렉토리(속성 0x18)는 건너뛴다.
fat_find	IS	@
	GET	$2,rJ
	PUSHJ	$3,rom_ram
	! $3 작업 영역, $4 디렉토리 클러스터, $5 클러스터 안 섹터 번호, $6 항목 주소,
	! $7 항목 시작 클러스터, $8 임시, $9 섹터 안 남은 항목 수, $10 호출 위치
	LDOU	$4,$3,RAM_ROOT_CLUS
find_cluster	IS	@
	CMPU	$8,$4,2
	BN	$8,find_none
	SETML	$8,FAT_EOC>>16
	INCL	$8,FAT_EOC&0xFFFF
	CMPU	$8,$4,$8
	BNN	$8,find_none
	SET	$5,0
find_sector	IS	@
	LDOU	$8,$3,RAM_SPC
	CMPU	$8,$5,$8
	BNN	$8,find_next_cluster
	SET	$11,$4
	PUSHJ	$10,fat_lba
	ADDU	$11,$10,$5
	SET	$12,$3
	INCL	$12,RAM_SECBUF
	PUSHJ	$10,sd_read
	BNZ	$10,find_none
	SET	$6,$3
	INCL	$6,RAM_SECBUF
	SET	$9,16
find_entry	IS	@
	LDBU	$8,$6,0
	BZ	$8,find_none
	CMP	$8,$8,0xE5
	BZ	$8,find_skip
	LDBU	$8,$6,11
	CMP	$11,$8,0x0F
	BZ	$11,find_skip
	AND	$11,$8,0x18
	BNZ	$11,find_skip
	ADDU	$11,$6,0x14
	PUSHJ	$10,le16
	SLU	$7,$10,16
	ADDU	$11,$6,0x1A
	PUSHJ	$10,le16
	OR	$7,$7,$10
	BZ	$0,find_by_name
	CMPU	$8,$7,$1
	BNZ	$8,find_skip
	JMP	find_found
find_by_name	IS	@
	SET	$8,0
find_cmp	IS	@
	LDBU	$11,$6,$8
	LDBU	$12,$1,$8
	CMP	$11,$11,$12
	BNZ	$11,find_skip
	ADD	$8,$8,1
	CMP	$11,$8,11
	BN	$11,find_cmp
find_found	IS	@
	ADDU	$11,$6,0x1C
	PUSHJ	$10,le32
	STOU	$10,$3,RAM_FILE_SIZE
	SET	$0,$7
	PUT	rJ,$2
	POP	1,0
find_skip	IS	@
	ADDU	$6,$6,32
	SUB	$9,$9,1
	BP	$9,find_entry
	ADD	$5,$5,1
	JMP	find_sector
find_next_cluster	IS	@
	SET	$11,$4
	PUSHJ	$10,fat_next
	SET	$4,$10
	JMP	find_cluster
find_none	IS	@
	SET	$0,0
	PUT	rJ,$2
	POP	1,0

! $0 주소의 리틀엔디언 16비트 → $0
le16	IS	@
	LDBU	$1,$0,0
	LDBU	$2,$0,1
	SLU	$2,$2,8
	OR	$0,$1,$2
	POP	1,0

! $0 주소의 리틀엔디언 32비트 → $0
le32	IS	@
	LDBU	$1,$0,0
	LDBU	$2,$0,1
	SLU	$2,$2,8
	OR	$1,$1,$2
	LDBU	$2,$0,2
	SLU	$2,$2,16
	OR	$1,$1,$2
	LDBU	$2,$0,3
	SLU	$2,$2,24
	OR	$0,$1,$2
	POP	1,0
