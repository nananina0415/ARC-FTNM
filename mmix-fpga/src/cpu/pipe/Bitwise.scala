package cpu

import chisel3._
import chisel3.util._

case class BitwiseOp() extends Bundle {
  val flag = UInt(4.W)
  val x    = UInt(8.W)
  val y    = UInt(8.W)
  val z    = UInt(8.W)
}

class Bitwise extends Module {
  val io = IO(new Bundle {
    val op  = Flipped(Valid(BitwiseOp()))
    val acc = Output(UInt(64.W))
  })

  io.acc := 0.U
}
