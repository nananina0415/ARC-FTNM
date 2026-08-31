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

/** 인출 단계가 연산 단계로 넘기는 값 — 레지스터버스에서 실제로 받아온 Y/Z. */
case class BitwiseFetchResult() extends Bundle {
  val flag = UInt(4.W)
  val x    = UInt(8.W)
  val y    = UInt(64.W)
  val z    = UInt(64.W)
}

/** 인출 단계 — 레지스터버스에 Y/Z를 요청해서 받아온다. */
class BitwiseFetch(flag: UInt, x: UInt, y: UInt, z: UInt, bus: RegBus, regPort: RegReadPort) {
  private val isImm = flag(0)

  regPort.x.addr := 0.U
  regPort.x.set  := false.B
  regPort.y.addr := y
  regPort.y.set  := true.B
  regPort.z.addr := z
  regPort.z.set  := !isImm

  val res = Wire(BitwiseFetchResult())
  res.flag := flag
  res.x    := x
  res.y    := bus.y.data
  res.z    := Mux(isImm, z.pad(64), bus.z.data)
}

/** 연산 단계 — 인출이 끝난 값으로 실제 비트 연산을 수행한다. */
class BitwiseExec(f: BitwiseFetchResult) {
  private val logic = Module(new LogicUnit())
  logic.io.a := f.y
  logic.io.b := f.z

  private val notZ   = WireDefault(false.B)
  private val notRes = WireDefault(false.B)
  private val opSel  = WireDefault(0.U(2.W))  // 0=OR, 1=AND, 2=XOR

  // op 플래그 파싱 — flag[3:1]이 (OR/AND/XOR, notZ/notRes)를 함께 결정, flag[0]은 즉치 여부
  switch(f.flag(3, 1)) {
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

  val res = Wire(BitwiseResult())
  res.dest := f.x
  res.res  := logic.io.res
}

class Bitwise(regReadPortFactory: RegReadPortFactory) extends Module {
  val io = IO(new Bundle {
    val op     = Input(BitwiseOp())
    val pause  = Input(Bool())           // 외부 일시정지: 1이면 컴포넌트 전체 홀드

    val reg    = new RegBus

    val result = Output(BitwiseResult())
  })

  val pauseBox = new PauseBox(2)
  pauseBox.reasons(0) := io.pause

  val regPort = regReadPortFactory(io.reg, pauseBox.pause)
  pauseBox.reasons(1) := !regPort.ack

  val CompoReg = CompoRegFactory(pauseBox.pause)

  val fetch = new BitwiseFetch(io.op.flag, io.op.x, io.op.y, io.op.z, io.reg, regPort)

  val fetchBuf = CompoReg(gen = BitwiseFetchResult(), write = regPort.ack, d = fetch.res)

  // ── 연산 단계: 인출이 끝난 값으로 실제 비트 연산을 수행 ──
  io.result := new BitwiseExec(fetchBuf.q).res
}
