package cpu

import chisel3._
import chisel3.util._

case class SpecialOp() extends Bundle {
  val flag = UInt(4.W)
  val xyz  = UInt(24.W)
}

class Special extends Module {
  val io = IO(new Bundle {
    val op  = Flipped(Valid(SpecialOp()))
    val acc = Output(UInt(64.W))
  })

  io.acc := 0.U
}
