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

    // 파이프별 valid 비트+초기화값 or
    // - Decoder가 이번 사이클 명령을 특정 파이프로 라우팅하지 않으면, 스케줄러가 그 파이프의
    //   op 입력에 기본값(초기화 상태 유지)을 넣어준다 — DontCare/이전 값이 그대로 흘러들어가지
    //   않게 스케줄러가 책임진다.
    // - 파이프마다 태스크 큐를 두고(지금은 파이프 내부 단계가 1단이라 파이프당 1비트), 명령을
    //   넣을 때 1을 큐에 푸시하고 매 클럭 한 비트씩 밀어낸다. 1이 큐에서 밀려나오면 그 파이프의
    //   결과가 준비된 것으로 보고, 커미터가 그 파이프의 출력을 꺼내 저장한다.
    // 즉 "결과가 유효한가"는 파이프 자신이 아니라 이 큐의 타이밍으로 스케줄러가 추적하므로,
    // 각 파이프(AddSub/Shift/Bitwise/Comp 등)의 IO에는 valid 비트가 필요 없다.
}