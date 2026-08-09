        .global _os_start
        .extern _start
        .extern trap_handler

// OS 전용 진입점. 트랩 핸들러 주소를 rT에 설정한 뒤 공통 _start로 점프.
// 앱은 이 파일을 링크하지 않으므로 rT에 접근하는 코드가 앱에 존재하지 않음.
_os_start:
        GETA    $0, trap_handler
        PUT     rT, $0
        GET     $0, rJ          // 리턴 주소 보존 불필요 — GO로 점프
        GETA    $0, _start
        GO      $1, $0, 0
