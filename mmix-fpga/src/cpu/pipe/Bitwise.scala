/*
 * OR $X,$Y,$Z|Z — 비트 OR
 *   $X ← $Y ∨ $Z (또는 $Y ∨ Z). 즉치형에서 Z=0이면 $Y를 $X로 복사하는 SET의 축약형으로도 쓰인다.
 *
 * ORN $X,$Y,$Z|Z — 비트 OR-NOT
 *   $X ← $Y ∨ ¬$Z (또는 $Y ∨ ¬Z).
 *
 * NOR $X,$Y,$Z|Z — 비트 NOR
 *   $X ← ¬($Y ∨ $Z) (또는 ¬($Y ∨ Z)).
 *
 * XOR $X,$Y,$Z|Z — 비트 배타적 OR
 *   $X ← $Y ⊕ $Z (또는 $Y ⊕ Z). 대응 비트가 서로 다르면 1.
 *
 * AND $X,$Y,$Z|Z — 비트 AND
 *   $X ← $Y ∧ $Z (또는 $Y ∧ Z).
 *
 * ANDN $X,$Y,$Z|Z — 비트 AND-NOT (논리적 차집합)
 *   $X ← $Y ∧ ¬$Z (또는 $Y ∧ ¬Z). 비트를 집합으로 볼 때 $Y \ $Z에 해당.
 *
 * NAND $X,$Y,$Z|Z — 비트 NAND
 *   $X ← ¬($Y ∧ $Z) (또는 ¬($Y ∧ Z)).
 *
 * NXOR $X,$Y,$Z|Z — 비트 배타적 OR의 부정 (동치, equivalence)
 *   $X ← ¬($Y ⊕ $Z) (또는 ¬($Y ⊕ Z)). 대응 비트가 서로 같으면 1.
*/

package cpu

import chisel3._
import chisel3.util._

case class BitwiseOp() extends Bundle {
  val flag = UInt(4.W)
  val x    = UInt(8.W)
  val y    = UInt(8.W)
  val z    = UInt(8.W)
}

case class BitwiseResult() extends Bundle {
  val dest = UInt(8.W)
  val res  = UInt(64.W)
}

class Bitwise extends Module {
  val io = IO(new Bundle {
    val op     = Input(BitwiseOp())
    val pause  = Input(Bool())           // 외부 일시정지: 1이면 컴포넌트 전체 홀드

    val regY   = RegReadPort()  // Y 레지스터 읽기
    val regZ   = RegReadPort()  // Z 레지스터 읽기 (즉치 시 미사용)

    val result = Output(BitwiseResult())
  })

  io.regY.addr := io.op.y
  io.regZ.addr := io.op.z

  val isImm = io.op.flag(0)

  val CompoReg = CompoRegFactory(pause = io.pause)

  val y_buf = CompoReg(gen = UInt(64.W), write = true.B, d = io.regY.data)
  val z_buf = CompoReg(
    gen   = UInt(64.W),
    write = true.B,
    d     = Mux(isImm, io.op.z.pad(64), io.regZ.data)
  )

  val logic = Module(new LogicUnit())
  logic.io.a := y_buf.q
  logic.io.b := z_buf.q

  val notZ   = WireDefault(false.B)
  val notRes = WireDefault(false.B)
  val opSel  = WireDefault(0.U(2.W))  // 0=OR, 1=AND, 2=XOR

  // op 플래그 파싱 — flag[3:1]이 (OR/AND/XOR, notZ/notRes)를 함께 결정, flag[0]은 즉치 여부
  switch(io.op.flag(3, 1)) {
    is("b100".U) { opSel := 0.U }                       // OR
    is("b101".U) { opSel := 0.U; notZ   := true.B }      // ORN
    is("b110".U) { opSel := 1.U }                        // AND
    is("b111".U) { opSel := 1.U; notZ   := true.B }      // ANDN
    is("b000".U) { opSel := 0.U; notRes := true.B }      // NOR
    is("b001".U) { opSel := 1.U; notRes := true.B }      // NAND
    is("b010".U) { opSel := 2.U }                        // XOR
    is("b011".U) { opSel := 2.U; notRes := true.B }      // NXOR
  }

  logic.io.op     := opSel
  logic.io.notB   := notZ
  logic.io.notRes := notRes

  io.result.dest := io.op.x
  io.result.res  := logic.io.res
}
