package cpu

import chisel3._
import chisel3.util._

case class CompOp() extends Bundle {
  val flag = UInt(6.W)
  val x    = UInt(8.W)
  val y    = UInt(8.W)
  val z    = UInt(8.W)
}

class Comp extends Module {
  val io = IO(new Bundle {
    val op  = Flipped(Valid(CompOp()))
    val acc = Output(UInt(64.W))
  })

  io.acc := 0.U
}
