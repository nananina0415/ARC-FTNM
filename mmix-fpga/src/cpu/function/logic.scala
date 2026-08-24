package cpu

import chisel3._
import chisel3.util._

/** OR/AND/XOR 중 하나를 고르고, b/결과에 각각 반전을 걸 수 있는 범용 논리 연산기.
 *
 * MMIX의 8가지 비트 연산(OR/AND/XOR/NOR/NAND/NXOR/ORN/ANDN)을
 * op 선택 + notB(b 반전) + notRes(결과 반전) 조합만으로 전부 표현한다.
 */
class LogicUnit extends Module {
  val io = IO(new Bundle {
    val op     = Input(UInt(2.W))   // 0=OR, 1=AND, 2=XOR
    val a      = Input(UInt(64.W))
    val b      = Input(UInt(64.W))
    val notB   = Input(Bool())
    val notRes = Input(Bool())
    val res    = Output(UInt(64.W))
  })

  val bVal = Mux(io.notB, ~io.b, io.b)
  val raw = MuxLookup(io.op, io.a | bVal)(Seq(
    0.U -> (io.a | bVal),
    1.U -> (io.a & bVal),
    2.U -> (io.a ^ bVal)
  ))

  io.res := Mux(io.notRes, ~raw, raw)
}
