/* ELF로 링크한 뒤 tools/mkexe.py가 nnix 실행파일(헤더 + 0번지 이미지 + 레지스터 영역)로 변환한다.
 * 전역 레지스터 초기값은 링커가 .MMIX.reg_contents에 두며 mkexe.py가 레지스터 영역으로 옮긴다. */
OUTPUT_FORMAT("elf64-mmix")

SECTIONS {
    . = 0x0;
    .text : {
        *(.text.crt1)
        *(.text.crt0)
        *(.text .text.*)
    }
    .rodata : { *(.rodata .rodata.*) }
    .data   : { *(.data .data.*) }
    /* crt0이 octa 단위로 BSS를 지우므로 시작을 8바이트 정렬한다. _fix_end는 실행파일 헤더에도 기록된다. */
    .bss    : {
        . = ALIGN(8);
        _bss_start = .;
        *(.bss .bss.* COMMON)
        . = ALIGN(8);
        _fix_end = .;
    }
}
