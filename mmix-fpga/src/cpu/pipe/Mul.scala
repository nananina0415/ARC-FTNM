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
  val flag = UInt(5.W)
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

class Mul extends Module {
  val io = IO(new Bundle {
    val op     = Input(MulOp())
    val pause  = Input(Bool())

    val regY   = RegReadPort()
    val regZ   = RegReadPort()

    val result = Output(MulResult())
  })

  io.regY.addr := io.op.y
  io.regZ.addr := io.op.z

  val isUnsigned = io.op.flag(1)
  val isImm      = io.op.flag(0)

  val CompoReg = CompoRegFactory(pause = io.pause)

  val y_buf = CompoReg(gen = UInt(64.W), write = true.B, d = io.regY.data)
  val z_buf = CompoReg(
    gen   = UInt(64.W),
    write = true.B,
    d     = Mux(isImm, io.op.z.pad(64), io.regZ.data)
  )

  val y = y_buf.q
  val z = z_buf.q

  // 128비트 곱 — 부호에 따라 signed/unsigned 곱셈 (둘 다 자동으로 128비트 폭)
  val prodSigned   = y.asSInt * z.asSInt
  val prodUnsigned = y * z
  val prod = Mux(isUnsigned, prodUnsigned, prodSigned.asUInt)

  // 결과 부호비트(63) + 상위 64비트, 총 65비트 — 전부 같으면 안전, 하나라도 다르면 오버플로우
  val top65 = prod(127, 63)
  val safe  = top65.andR || !top65.orR

  io.result.dest   := io.op.x
  io.result.res    := prod(63, 0)
  io.result.himult := prod(127, 64)
  io.result.writeH := isUnsigned
  io.result.ovf    := !isUnsigned && !safe
}
