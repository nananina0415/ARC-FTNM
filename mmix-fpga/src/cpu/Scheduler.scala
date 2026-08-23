package cpu

import chisel3._
import chisel3.util._

class Scheduler extends Module {
    val io = IO(new Bundle {
        
    })
    // 추후 비순차 실행을 위한 링 버퍼. 스케줄러가 버퍼에 파이프번호를 푸시 후 그 파이프 버퍼로 명령을 넣는 식
    // val reorder_buf = [Uint(4);NUM_PIPES]  // NUM_PIPES는 패키지 상수, 4는 파이프 수 = ceil(log_2(NUMPIPES))
    // val scheduled = new Counter(ceil(log_2(NUMPIPES)))
    // val first_job = new Counter(ceil(log_2(NUMPIPES)))

}