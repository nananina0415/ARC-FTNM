/* BIOS ROM 이미지. MMIO 블록 0~15(64KB BRAM, 주소 2^48)에 그대로 올라간다.
 * rom_entry가 ROM 첫 주소에 와야 proc_execve의 GO와 리셋이 같은 곳으로 들어온다. */
OUTPUT_FORMAT(binary)
ENTRY(rom_entry)

/* 보드 상수(MEM_SIZE 등). 링크 시 -L drivers/<보드>로 보드를 고른다. */
INCLUDE board.ld

SECTIONS {
    . = 0x1000000000000;
    .text : {
        *(.text.rom.entry)
        *(.text.rom .text.rom.*)
    }
    ASSERT(SIZEOF(.text) <= 0x10000, "ROM은 64KB를 넘을 수 없다")
    /DISCARD/ : { *(.data .bss .comment) }
}
