/*
 * SL $X,$Y,$Z|Z — 왼쪽 시프트
 *   $Y(부호있는 수)를 $Z(또는 Z, 부호없는 시프트량)비트만큼 왼쪽 시프트, 오른쪽은 0으로 채움.
 *   2^$Z를 곱하는 것과 동일한 효과. 결과가 2^63 이상 또는 -2^63 미만이면 오버플로우 예외.
 *   시프트량이 64 이상이면 결과는 0 ($Y가 0이 아니었다면 오버플로우 예외).
 *
 * SLU $X,$Y,$Z|Z — 부호없는 왼쪽 시프트
 *   SL과 동일하나 두 피연산자 모두 부호없는 수로 취급, 오버플로우 검사 없음.
 *
 * SR $X,$Y,$Z|Z — 오른쪽 시프트
 *   $Y(부호있는 수)를 $Z(또는 Z, 부호없는 시프트량)비트만큼 오른쪽 시프트,
 *   왼쪽은 부호비트로 채움(산술 시프트). 2^$Z로 나눠 내림한 것과 동일한 효과.
 *   시프트량이 64 이상이면 $Y≥0이면 결과 0, $Y<0이면 결과 -1.
 *
 * SRU $X,$Y,$Z|Z — 부호없는 오른쪽 시프트
 *   $Y를 $Z(또는 Z)비트만큼 오른쪽 시프트, 왼쪽은 0으로 채움(논리 시프트).
 *   부호없는 나눗셈(2^$Z)과 동일한 효과. 시프트량이 64 이상이면 결과 0.
*/

package cpu

import chisel3._
import chisel3.util._

case class ShiftOp() extends Bundle {
  val flag = UInt(5.W)
  val x    = UInt(8.W)
  val y    = UInt(8.W)
  val z    = UInt(8.W)
}

case class ShiftResult() extends Bundle {
  val ovf  = Bool()
  val dest = UInt(8.W)
  val res  = UInt(64.W)
}

class Shift extends Module {
  val io = IO(new Bundle {
    val op     = Input(ShiftOp())
    val pause  = Input(Bool())           // 외부 일시정지: 1이면 컴포넌트 전체 홀드

    val regY   = RegReadPort()  // 시프트 대상 값
    val regZ   = RegReadPort()  // 레지스터 모드일 때 시프트량

    val result = Output(ShiftResult())
  })

  io.regY.addr := io.op.y
  io.regZ.addr := io.op.z

  // op 플래그 파싱 — direction:flag(2), unsign:flag(1), imm:flag(0)
  val isLeft     = !io.op.flag(2)
  val isUnsigned =  io.op.flag(1)
  val isImm      =  io.op.flag(0)

  val CompoReg = CompoRegFactory(pause = io.pause)

  val y_buf = CompoReg(
    gen   = UInt(64.W),
    write = true.B,
    d     = io.regY.data
  )

  val sh_buf = CompoReg(
    gen   = UInt(64.W),
    write = true.B,
    d     = Mux(isImm, io.op.z.pad(64), io.regZ.data)
  )

  val y  = y_buf.q
  val sh = sh_buf.q

  val isYMinus = y(63)
  val isZBig   = sh(63, 6).orR                 // 시프트량 ≥ 64

  // 정상 경로(시프트량 < 64) — 실제 시프트 수행
  val sh6      = sh(5, 0)
  val leftRes  = (y << sh6)(63, 0)
  val rightRes = Mux(isUnsigned, y >> sh6, (y.asSInt >> sh6).asUInt)
  val shiftedRes = Mux(isLeft, leftRes, rightRes)

  // ovf_mask: decode_6to64(sh6)를 뒤집어서(63-sh6 위치) 저→고로 전파 — 상위 (sh+1)비트를 선택
  val decoded  = UIntToOH(sh6, 64)
  val reversed = Cat(decoded(63), Reverse(decoded(63, 1)))
  val propa64  = Module(new Propa64())
  propa64.io.in  := reversed
  propa64.io.cin := false.B
  val ovfMask  = propa64.io.res

  // 음수면 Y를 반전해서 "부호와 같은 패턴"을 항상 0으로 맞춘다 (마스크 위치는 부호와 무관하게 고정)
  val checkVal    = Mux(isYMinus, ~y, y)
  val masked      = checkVal & ovfMask
  val maskedEqual = masked.andR || !masked.orR // bit_fold(equal): 전부 1이거나 전부 0 (=안전)
  val normalOvf   = isLeft && !isUnsigned && !maskedEqual  // 마스크된 비트가 서로 다르면 오버플로우

  // 빅시프트 경로(시프트량 ≥ 64)
  val bigRes = Mux(isLeft, 0.U(64.W), Mux(!isUnsigned && isYMinus, "hffffffffffffffff".U(64.W), 0.U(64.W)))
  val bigOvf = isLeft && !isUnsigned && (y =/= 0.U)

  io.result.dest := io.op.x
  io.result.res  := Mux(isZBig, bigRes, shiftedRes)
  io.result.ovf  := Mux(isZBig, bigOvf, normalOvf)
}
