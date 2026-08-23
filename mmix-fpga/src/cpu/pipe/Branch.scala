package cpu

import chisel3._
import chisel3.util._

case class BranchOp() extends Bundle {
  val flag = UInt(5.W)
  val x    = UInt(8.W)   // 테스트할 레지스터 번호
  val yz   = UInt(16.W)  // 16비트 상대주소 오프셋
}

class Branch extends Module {
  val io = IO(new Bundle {
    val op  = Flipped(Valid(BranchOp()))
    val acc = Output(UInt(64.W))
  })

  io.acc := 0.U
}
