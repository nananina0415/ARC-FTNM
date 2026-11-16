! BIOS ROM 공용 상수. ROM의 각 .s 파일이 .include 한다.

! 앱에 넘기는 rG. 링커가 앱에 배치하는 전역 레지스터가 $128부터라고 본다(Doom 기준).
! 전역 레지스터 초기값은 RAW 이미지에 없으므로 0으로 넘긴다.
APP_G	IS	128

! UNSAVE 컨텍스트 크기(바이트): 로컬 4 + hole 1 + 전역 (256-APP_G) + 특수 12 + rG/rA 1
APP_LOCALS	IS	4
CTX_OCTAS	IS	APP_LOCALS+1+(256-APP_G)+12+1
CTX_SIZE	IS	CTX_OCTAS*8

! ROM 작업 영역: RAM 최상단 ROM_RAM_SIZE 바이트.
! 앱 로드가 끝날 때까지만 쓰고, 이후엔 앱이 이 영역을 덮어쓴다.
! LDO/STO의 즉시 오프셋은 8비트라서 변수는 0x00~0xFF에 둔다.
ROM_RAM_SIZE	IS	0x800
RAM_SDHC	IS	0x00
RAM_SPC	IS	0x08
RAM_FAT_LBA	IS	0x10
RAM_DATA_LBA	IS	0x18
RAM_ROOT_CLUS	IS	0x20
RAM_FAT_CACHED	IS	0x28
RAM_FILE_SIZE	IS	0x30
RAM_SIMIO	IS	0x40
RAM_SECBUF	IS	0x200
RAM_FATBUF	IS	0x400

! FAT32 클러스터 번호의 유효 범위: 2 이상, EOC(0x0FFFFFF8) 미만
FAT_EOC	IS	0x0FFFFFF8

! 실패 시 $255에 넣고 멈추는 오류 코드
ERR_SD	IS	1
ERR_MOUNT	IS	2
ERR_NO_OS	IS	3
ERR_NO_EXE	IS	4
ERR_READ	IS	5
ERR_IMAGE	IS	6
ERR_MEM	IS	7
