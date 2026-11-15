OUTPUT_FORMAT(binary)

SECTIONS {
    . = 0x0;
    .text : {
        *(.text.crt1)
        *(.text.crt0)
        *(.text .text.*)
    }
    .rodata : { *(.rodata .rodata.*) }
    .data   : { *(.data .data.*) }
    /* 파일 끝 8바이트: BSS 끝(_fix_end). BSS는 파일에 들어가지 않으므로 로더가 이 값으로 배치를 계산한다. */
    .fixinfo : { . = ALIGN(8); QUAD(_fix_end) }
    .bss    : {
        _bss_start = .;
        *(.bss .bss.* COMMON)
        . = ALIGN(8);
        _fix_end = .;
    }
}
