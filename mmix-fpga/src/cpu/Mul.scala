package cpu

import chisel3._
import chisel3.util._

case class MulOp() extends Bundle {
  val flag = UInt(5.W)
  val x    = UInt(8.W)
  val y    = UInt(8.W)
  val z    = UInt(8.W)
}

class Mul extends Module {
  val io = IO(new Bundle {
    val op  = Flipped(Valid(MulOp()))
    val acc = Output(UInt(64.W))
  })

  io.acc := 0.U
}
