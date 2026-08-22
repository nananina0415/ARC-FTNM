package cpu

import chisel3._
import chisel3.util._

// JMP는 XYZ 24비트, PUSHJ는 X+YZ, PUSHGO/GO/TRAP/TRIP은 X+Y+Z
// 파이프에서 flag에 따라 xyz를 분리해서 사용
case class JumpOp() extends Bundle {
  val flag = UInt(4.W)
  val xyz  = UInt(24.W)
}

class Jump extends Module {
  val io = IO(new Bundle {
    val op  = Flipped(Valid(JumpOp()))
    val acc = Output(UInt(64.W))
  })

  io.acc := 0.U
}
