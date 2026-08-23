package cpu

import chisel3._
import chisel3.util._

// SETH/INCH/ORH/ANDNH 계열 — $X,YZ 형식 (16비트 즉치값)
case class ConstOp() extends Bundle {
  val flag = UInt(4.W)
  val x    = UInt(8.W)
  val yz   = UInt(16.W)
}

class Const extends Module {
  val io = IO(new Bundle {
    val op  = Flipped(Valid(ConstOp()))
    val acc = Output(UInt(64.W))
  })

  io.acc := 0.U
}
