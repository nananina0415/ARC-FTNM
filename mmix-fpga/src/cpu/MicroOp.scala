package cpu

import chisel3._
import chisel3.util._

object PipeType extends ChiselEnum {
  val MUL, DIV, ADDSUB, SHIFT, COMP, MEM, BRANCH, CONST, JUMP, SPECIAL, BITWISE = Value
}

case class MicroOp() extends Bundle {
  val pipe  = PipeType()
  val flag  = UInt(6.W)
  val param = UInt(24.W)
}

case class Op() extends Bundle {
  val op    = UInt(8.W)
  val param = UInt(24.W)
}
