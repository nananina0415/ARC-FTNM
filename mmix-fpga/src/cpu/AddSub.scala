package cpu

import chisel3._
import chisel3.util._


case class AddSubOp() extends Bundle {
  val flag  = UInt(5.W)
  val x = UInt(8.W)
  val y = UInt(8.W)
  val z = UInt(8.W)
}


class AddSub extends Bundle {
  val op = AddSubOp()
  val acc = UInt(64.W)
}