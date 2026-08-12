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
    .bss    : {
        _bss_start = .;
        *(.bss .bss.* COMMON)
        . = ALIGN(8);
        _heap_start = .;
    }
}
