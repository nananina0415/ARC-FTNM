/*
 * MUL $X,$Y,$Z|Z — 곱셈
 *   $X ← $Y × $Z (레지스터) 또는 $Y × Z (즉치값, 0~255 비음수)
 *   부호있는 64비트 곱. 결과가 2^63 이상 또는 -2^63 미만이면 정수 오버플로우 예외 발생.
 *
 * MULU $X,$Y,$Z|Z — 부호없는 곱셈
 *   $Y와 $Z(또는 Z)의 128비트 부호없는 곱 중 하위 64비트를 $X에 저장.
 *   상위 64비트는 특수 레지스터 rH(himult register)에 저장.
 *   오버플로우 검사 없음.
*/

package cpu

import chisel3._
import chisel3.util._

case class MulOp() extends Bundle {
  val flag = UInt(2.W)
  val x    = UInt(8.W)
  val y    = UInt(8.W)
  val z    = UInt(8.W)
}

case class MulResult() extends Bundle {
  val dest   = UInt(8.W)
  val res    = UInt(64.W)
  val himult = UInt(64.W)  // rH로 — writeH가 true일 때만 의미있음
  val writeH = Bool()      // true면 himult를 rH에 씀 (MULU만)
  val ovf    = Bool()      // 부호있는 MUL만 유효, MULU는 항상 false
}

/** 인출 단계가 연산 단계로 넘기는 값 — 레지스터버스에서 실제로 받아온 Y/Z. */
case class MulFetchResult() extends Bundle {
  val uFlag = Bool()
  val x     = UInt(8.W)
  val y     = UInt(64.W)
  val z     = UInt(64.W)
}

/** 연산 단계 — 인출이 끝난 값으로 실제 곱셈을 수행한다. */
class MulExec(f: MulFetchResult) {
  // 128비트 곱 — 부호에 따라 signed/unsigned 곱셈 (둘 다 자동으로 128비트 폭)
  private val prodSigned   = f.y.asSInt * f.z.asSInt
  private val prodUnsigned = f.y * f.z
  private val prod = Mux(f.uFlag, prodUnsigned, prodSigned.asUInt)

  // 결과 부호비트(63) + 상위 64비트, 총 65비트 — 전부 같으면 안전, 하나라도 다르면 오버플로우
  private val top65 = prod(127, 63)
  private val safe  = top65.andR || !top65.orR

  val res = Wire(MulResult())
  res.dest   := f.x
  res.res    := prod(63, 0)
  res.himult := prod(127, 64)
  res.writeH := f.uFlag
  res.ovf    := !f.uFlag && !safe
}

/** 인출 단계 — 레지스터버스에 Y/Z를 요청해서 받아온다. */
class MulFetch(flag: UInt, x: UInt, y: UInt, z: UInt, bus: RegBus, regPort: RegReadPort) {
  private val zImm = flag(0)

  regPort.x.addr := 0.U
  regPort.x.set  := false.B
  regPort.y.addr := y
  regPort.y.set  := true.B
  regPort.z.addr := z
  regPort.z.set  := !zImm

  val res = Wire(MulFetchResult())
  res.uFlag := flag(1)
  res.x     := x
  res.y     := bus.y.data
  res.z     := Mux(zImm, z.pad(64), bus.z.data)
}

class Mul(regReadPortFactory: RegReadPortFactory) extends Module {
  val io = IO(new Bundle {
    val op     = Input(MulOp())
    val pause  = Input(Bool())

    val reg    = new RegBus

    val result = Output(MulResult())
  })

  val pauseBox = new PauseBox(2)
  pauseBox.reasons(0) := io.pause

  val regPort = regReadPortFactory(io.reg, pauseBox.pause)
  pauseBox.reasons(1) := !regPort.ack

  val CompoReg = CompoRegFactory(pauseBox.pause)

  val fetch = new MulFetch(io.op.flag, io.op.x, io.op.y, io.op.z, io.reg, regPort)

  val fetchBuf = CompoReg(gen = MulFetchResult(), write = regPort.ack, d = fetch.res)

  // ── 연산 단계: 인출이 끝난 값으로 실제 곱셈을 수행 ──
  io.result := new MulExec(fetchBuf.q).res
}
