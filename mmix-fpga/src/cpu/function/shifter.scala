package cpu

import chisel3._
import chisel3.util._

/** 64비트 논리 왼쪽 시프터.
 *
 * shamt가 64 이상이면 결과는 0. MMIX SL/SLU/nADDU 계열에서 사용.
 * shamt는 부호없는 정수로 처리되며 최대 255까지 받는다.
 */
class Shifter64Left extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(64.W))
    val b   = Input(UInt(8.W))   // 0~255, 64 이상은 결과 0
    val res = Output(UInt(64.W))
  })

  io.res := Mux(io.b >= 64.U, 0.U(64.W), (io.a << io.b)(63, 0))
}
