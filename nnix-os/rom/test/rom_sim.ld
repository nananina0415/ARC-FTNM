/* mmix-sim용 ROM 테스트 이미지(mmo). ROM은 rom.ld와 같은 주소(2^48)에 두고
 * 테스트 진입점(Main)은 그 뒤에 둔다. MEM_SIZE는 시나리오마다 --defsym으로 준다. */
ENTRY(Main)

SECTIONS {
    . = 0x1000000000000;
    .text : {
        *(.text.rom.entry)
        *(.text.rom .text.rom.*)
        *(.text.simentry)
    }
}
