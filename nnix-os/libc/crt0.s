        .global _start
        .extern main
        .extern __bss_start
        .extern __bss_end
        .extern __stack_top

_start:
        // 스택 포인터 초기화 (링커 스크립트 정의 심볼)
        GETA    $254, __stack_top

        // BSS 구역 0으로 클리어 (8바이트 단위)
        GETA    $0, __bss_start
        GETA    $1, __bss_end
        CMPU    $2, $0, $1
        BNN     $2, 2f          // __bss_start >= __bss_end 이면 스킵
        SET     $3, 0
1:
        STOU    $3, $0, 0
        ADD     $0, $0, 8
        CMPU    $2, $0, $1
        BN      $2, 1b
2:
        // main(0, NULL) 호출
        SET     $0, 0
        SET     $1, 0
        PUSHJ   $2, main

        // main 반환 후 정지
        SET     $255, $2
        TRAP    0, 0, 0
