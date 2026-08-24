/*
 * DIV $X,$Y,$Z|Z — 나눗셈
 *   $X ← ⌊$Y / $Z⌋ 또는 ⌊$Y / Z⌋ (부호있는 몫), 나머지는 특수 레지스터 rR(remainder)에 저장.
 *   나머지는 0이거나 제수(divisor)와 같은 부호를 가짐 (y = ⌊y/z⌋·z + 나머지).
 *   제수가 0이면 정수 나눗셈 예외(divide check) 발생 — 이때 $X←0, rR←$Y.
 *   -2^63을 -1로 나누는 경우에만 정수 오버플로우 예외 발생.
 *
 * DIVU $X,$Y,$Z|Z — 부호없는 나눗셈
 *   특수 레지스터 rD(dividend)를 상위 64비트로 하는 128비트 부호없는 수를
 *   $Y와 결합해 $Z(또는 Z)로 나눈다. 몫은 $X, 나머지는 rR에 저장.
 *   rD가 제수 이상이면(0으로 나누는 경우 포함) $X←rD, rR←$Y로 설정된다.
 *   부호없는 연산이므로 예외는 절대 발생하지 않는다.
*/

package cpu

import chisel3._
import chisel3.util._

case class DivOp() extends Bundle {
  val flag = UInt(5.W)
  val x    = UInt(8.W)
  val y    = UInt(8.W)
  val z    = UInt(8.W)
}

case class DivResult() extends Bundle {
  val dest      = UInt(8.W)
  val res       = UInt(64.W)
  val remainder = UInt(64.W)  // rR로
  val ovf       = Bool()      // 부호있는 DIV만: MIN을 -1로 나누는 유일한 경우
  val divCheck  = Bool()      // 제수가 0 (부호없는 DIVU는 항상 false)
}

class Div extends Module {
  val io = IO(new Bundle {
    val op     = Input(DivOp())
    val pause  = Input(Bool())

    val regY   = RegReadPort()
    val regZ   = RegReadPort()
    val rD     = RegD_R()  // DIVU가 128비트 피제수(rD:Y)를 만들 때 쓰는 특수 레지스터, 전용 읽기 포트

    val result = Output(DivResult())
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

  val I64_MIN = "h8000000000000000".U(64.W)

  // ── 부호있는 DIV — 절삭 나눗셈을 구한 뒤 floor로 보정 ──
  val zIsZero    = z === 0.U
  val minDivNeg1 = (y === I64_MIN) && (z.asSInt === -1.S)

  val ySInt  = y.asSInt
  val zSInt  = z.asSInt
  val safeZ  = Mux(zIsZero, 1.S(64.W), zSInt)  // 실제 0으로 나누는 하드웨어 동작을 피하기 위한 안전값

  val tq = ySInt / safeZ  // 절삭 몫
  val tr = ySInt % safeZ  // 절삭 나머지

  val needAdjust = (tr =/= 0.S) && ((tr < 0.S) =/= (zSInt < 0.S))
  val floorQ = Mux(needAdjust, tq - 1.S, tq)
  val floorR = Mux(needAdjust, tr + zSInt, tr)

  val divRes = MuxCase(floorQ.asUInt(63, 0), Seq(
    zIsZero    -> 0.U,
    minDivNeg1 -> I64_MIN
  ))
  val divRem = MuxCase(floorR.asUInt(63, 0), Seq(
    zIsZero    -> y,
    minDivNeg1 -> 0.U
  ))
  val divOvf      = minDivNeg1
  val divCheckSig = zIsZero

  // ── 부호없는 DIVU — rD:Y를 128비트 피제수로 취급 ──
  val dividend  = Cat(io.rD.data, y)          // 128비트
  val safeZPad  = Mux(zIsZero, 1.U(128.W), z.pad(128))
  val bigQ      = dividend / safeZPad
  val bigR      = dividend % safeZPad
  val rdGeZ     = io.rD.data >= z             // rD>=Z(제수 0 포함)면 몫이 64비트에 안 들어감

  val divuRes = Mux(rdGeZ, io.rD.data, bigQ(63, 0))
  val divuRem = Mux(rdGeZ, y, bigR(63, 0))

  io.result.dest      := io.op.x
  io.result.res       := Mux(isUnsigned, divuRes, divRes)
  io.result.remainder := Mux(isUnsigned, divuRem, divRem)
  io.result.ovf       := !isUnsigned && divOvf
  io.result.divCheck  := !isUnsigned && divCheckSig
}
