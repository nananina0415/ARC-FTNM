        .global trap_handler
        .extern trap_dispatch
        .extern _kstack_top

// MMIX 트랩 발생 시 CPU가 rT 주소(여기)로 점프.
// 진입 시 특수 레지스터 상태:
//   rBB = 트랩 직전 $255 (libc가 넘긴 인자 포인터)
//   rYY = TRAP Y바이트 (시스템콜 번호)
//   rZZ = TRAP Z바이트 (옵션)
//   rWW = 트랩 다음 명령어 주소 (복귀 주소)
trap_handler:
        // 커널 스택으로 전환
        GETA    $254, _kstack_top

        // 인자 추출
        GET     $0, rYY         // syscall 번호
        GET     $1, rZZ         // 옵션
        GET     $2, rBB         // 사용자 $255 (인자)

        // C 디스패처 호출: long trap_dispatch(long sc, long opt, long arg)
        PUSHJ   $3, trap_dispatch

        // 반환값 → rBB (RESUME 1이 rBB를 $255로 복원)
        PUT     rBB, $3

        RESUME  1
