! mmix-sim 테스트 진입점. 리셋 또는 proc_execve가 ROM에 들어가는 상태를 흉내 낸다.
! 어셈블할 때 --defsym으로 SIM_EXE, SIM_LINK, SIM_CRASH를 준다(SIM_EXE = 0이면 리셋).
! OS처럼 rG = 128로 두어 $253~$255를 전역으로 넘긴다.

	.section .text.simentry,"ax"
	.global	Main

Main	IS	@
	PUT	rG,128
	SETL	$255,SIM_EXE
	SETL	$254,SIM_LINK
	SETL	$253,SIM_CRASH
	JMP	rom_entry
