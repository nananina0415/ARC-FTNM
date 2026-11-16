! 리셋 경로: 루트 디렉토리에서 OS 실행파일을 찾는다.

	.include "rom.inc"

	.section .text.rom,"ax"
	.global	boot_find_os

! $0 ← NNIX.OS 시작 클러스터 (없으면 0)
boot_find_os	IS	@
	GET	$0,rJ
	SET	$2,0
	GETA	$3,os_name
	PUSHJ	$1,fat_find
	PUT	rJ,$0
	SET	$0,$1
	POP	1,0

! FAT 8.3 디렉토리 항목 이름 (sys.c의 "nnix.os")
os_name	IS	@
	.ascii	"NNIX    OS "
	.p2align 2
