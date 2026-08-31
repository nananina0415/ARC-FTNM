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

/** 인출 단계가 연산 단계로 넘기는 값 — 레지스터버스에서 실제로 받아온 Y/시프트량. */
case class ShiftFetchResult() extends Bundle {
  val isLeft     = Bool()
  val isUnsigned = Bool()
  val x  = UInt(8.W)
  val y  = UInt(64.W)
  val sh = UInt(64.W)
}

/** 인출 단계 — 레지스터버스에 Y/Z를 요청해서 받아온다. */
class ShiftFetch(flag: UInt, x: UInt, y: UInt, z: UInt, bus: RegBus, regPort: RegReadPort) {
  private val isImm = flag(0)

  regPort.x.addr := 0.U
  regPort.x.set  := false.B
  regPort.y.addr := y
  regPort.y.set  := true.B
  regPort.z.addr := z
  regPort.z.set  := !isImm

  val res = Wire(ShiftFetchResult())
  res.isLeft     := !flag(2)
  res.isUnsigned := flag(1)
  res.x  := x
  res.y  := bus.y.data
  res.sh := Mux(isImm, z.pad(64), bus.z.data)
}

/** 연산 단계 — 인출이 끝난 값으로 실제 시프트를 수행한다. */
class ShiftExec(f: ShiftFetchResult) {
  private val y  = f.y
  private val sh = f.sh

  private val isYMinus = y(63)
  private val isZBig   = sh(63, 6).orR                 // 시프트량 ≥ 64

  // 정상 경로(시프트량 < 64) — 실제 시프트 수행
  private val sh6      = sh(5, 0)
  private val leftRes  = (y << sh6)(63, 0)
  private val rightRes = Mux(f.isUnsigned, y >> sh6, (y.asSInt >> sh6).asUInt)
  private val shiftedRes = Mux(f.isLeft, leftRes, rightRes)

  // ovf_mask: decode_6to64(sh6)를 뒤집어서(63-sh6 위치) 저→고로 전파 — 상위 (sh+1)비트를 선택
  private val decoded  = UIntToOH(sh6, 64)
  private val reversed = Cat(decoded(63), Reverse(decoded(63, 1)))
  private val propa64  = Module(new Propa64())
  propa64.io.in  := reversed
  propa64.io.cin := false.B
  private val ovfMask  = propa64.io.res

  // 음수면 Y를 반전해서 "부호와 같은 패턴"을 항상 0으로 맞춘다 (마스크 위치는 부호와 무관하게 고정)
  private val checkVal    = Mux(isYMinus, ~y, y)
  private val masked      = checkVal & ovfMask
  private val maskedEqual = masked.andR || !masked.orR // bit_fold(equal): 전부 1이거나 전부 0 (=안전)
  private val normalOvf   = f.isLeft && !f.isUnsigned && !maskedEqual  // 마스크된 비트가 서로 다르면 오버플로우

  // 빅시프트 경로(시프트량 ≥ 64)
  private val bigRes = Mux(f.isLeft, 0.U(64.W), Mux(!f.isUnsigned && isYMinus, "hffffffffffffffff".U(64.W), 0.U(64.W)))
  private val bigOvf = f.isLeft && !f.isUnsigned && (y =/= 0.U)

  val res = Wire(ShiftResult())
  res.dest := f.x
  res.res  := Mux(isZBig, bigRes, shiftedRes)
  res.ovf  := Mux(isZBig, bigOvf, normalOvf)
}

class Shift(regReadPortFactory: RegReadPortFactory) extends Module {
  val io = IO(new Bundle {
    val op     = Input(ShiftOp())
    val pause  = Input(Bool())           // 외부 일시정지: 1이면 컴포넌트 전체 홀드

    val reg    = new RegBus

    val result = Output(ShiftResult())
  })

  val pauseBox = new PauseBox(2)
  pauseBox.reasons(0) := io.pause

  val regPort = regReadPortFactory(io.reg, pauseBox.pause)
  pauseBox.reasons(1) := !regPort.ack

  val CompoReg = CompoRegFactory(pauseBox.pause)

  val fetch = new ShiftFetch(io.op.flag, io.op.x, io.op.y, io.op.z, io.reg, regPort)

  val fetchBuf = CompoReg(gen = ShiftFetchResult(), write = regPort.ack, d = fetch.res)

  // ── 연산 단계: 인출이 끝난 값으로 실제 시프트를 수행 ──
  io.result := new ShiftExec(fetchBuf.q).res
}
