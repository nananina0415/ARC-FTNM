package cpu

import chisel3._
import chisel3.util._

case class ShiftOp() extends Bundle {
  val flag = UInt(5.W)
  val x    = UInt(8.W)
  val y    = UInt(8.W)
  val z    = UInt(8.W)
}

class Shift extends Module {
  val io = IO(new Bundle {
    val op  = Flipped(Valid(ShiftOp()))
    val acc = Output(UInt(64.W))
  })

  io.acc := 0.U
}
