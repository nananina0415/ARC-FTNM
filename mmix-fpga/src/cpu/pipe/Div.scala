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

/** 인출 단계가 연산 단계로 넘기는 값 — 레지스터버스에서 실제로 받아온 Y/Z와 rD. */
case class DivFetchResult() extends Bundle {
  val uFlag = Bool()
  val x     = UInt(8.W)
  val y     = UInt(64.W)
  val z     = UInt(64.W)
  val rD    = UInt(64.W)  // 전용선이라 버스 왕복 없이 그대로 실어옴
}

/** 인출 단계 — 레지스터버스에 Y/Z를 요청해서 받아온다. rD는 전용선이지만 그쪽도 자기만의
 * 작은 중재기를 거치므로 똑같이 요청-대기해야 한다 — 다만 rD는 부호없는 DIVU에서만
 * 쓰이므로(부호있는 DIV는 아예 안 봄) 그때만 요청한다.
 */
class DivFetch(
  flag: UInt, x: UInt, y: UInt, z: UInt,
  bus: RegBus, regPort: RegReadPort,
  rD: SpecialRegPort_Div_R, rDPort: SpecialRegReadPort
) {
  private val zImm      = flag(0)
  private val isUnsigned = flag(1)

  regPort.x.addr := 0.U
  regPort.x.set  := false.B
  regPort.y.addr := y
  regPort.y.set  := true.B
  regPort.z.addr := z
  regPort.z.set  := !zImm

  rDPort.set_=(isUnsigned)

  val res = Wire(DivFetchResult())
  res.uFlag := isUnsigned
  res.x     := x
  res.y     := bus.y.data
  res.z     := Mux(zImm, z.pad(64), bus.z.data)
  res.rD    := rD.data
}

/** 연산 단계 — 인출이 끝난 값으로 실제 나눗셈을 수행한다. */
class DivExec(f: DivFetchResult) {
  private val I64_MIN = "h8000000000000000".U(64.W)

  // ── 부호있는 DIV — 절삭 나눗셈을 구한 뒤 floor로 보정 ──
  private val zIsZero    = f.z === 0.U
  private val minDivNeg1 = (f.y === I64_MIN) && (f.z.asSInt === -1.S)

  private val ySInt = f.y.asSInt
  private val zSInt = f.z.asSInt
  private val safeZ = Mux(zIsZero, 1.S(64.W), zSInt)  // 실제 0으로 나누는 하드웨어 동작을 피하기 위한 안전값

  private val tq = ySInt / safeZ  // 절삭 몫
  private val tr = ySInt % safeZ  // 절삭 나머지

  private val needAdjust = (tr =/= 0.S) && ((tr < 0.S) =/= (zSInt < 0.S))
  private val floorQ = Mux(needAdjust, tq - 1.S, tq)
  private val floorR = Mux(needAdjust, tr + zSInt, tr)

  private val divRes = MuxCase(floorQ.asUInt(63, 0), Seq(
    zIsZero    -> 0.U,
    minDivNeg1 -> I64_MIN
  ))
  private val divRem = MuxCase(floorR.asUInt(63, 0), Seq(
    zIsZero    -> f.y,
    minDivNeg1 -> 0.U
  ))
  private val divOvf      = minDivNeg1
  private val divCheckSig = zIsZero

  // ── 부호없는 DIVU — rD:Y를 128비트 피제수로 취급 ──
  private val dividend = Cat(f.rD, f.y)          // 128비트
  private val safeZPad = Mux(zIsZero, 1.U(128.W), f.z.pad(128))
  private val bigQ     = dividend / safeZPad
  private val bigR     = dividend % safeZPad
  private val rdGeZ    = f.rD >= f.z             // rD>=Z(제수 0 포함)면 몫이 64비트에 안 들어감

  private val divuRes = Mux(rdGeZ, f.rD, bigQ(63, 0))
  private val divuRem = Mux(rdGeZ, f.y, bigR(63, 0))

  val res = Wire(DivResult())
  res.dest      := f.x
  res.res       := Mux(f.uFlag, divuRes, divRes)
  res.remainder := Mux(f.uFlag, divuRem, divRem)
  res.ovf       := !f.uFlag && divOvf
  res.divCheck  := !f.uFlag && divCheckSig
}

class Div(regReadPortFactory: RegReadPortFactory, specialRegReadPortFactory: SpecialRegReadPortFactory) extends Module {
  val io = IO(new Bundle {
    val op     = Input(DivOp())
    val pause  = Input(Bool())

    val reg    = new RegBus
    val rD     = SpecialRegPort_Div_R()  // DIVU가 128비트 피제수(rD:Y)를 만들 때 쓰는 특수 레지스터,
                                          // Div 그룹 공유 읽기 포트(로컬번호 0=rD)

    val result = Output(DivResult())
  })

  val pauseBox = new PauseBox(3)
  pauseBox.reasons(0) := io.pause

  // 두 포트 다 같은 pauseBox.pause를 봐야 서로의 대기 상태 때문에 재무장되는 것도
  // 막힌다(예: rD 기다리는 동안 Y가 먼저 도착해도 Y가 재요청 안 함).
  val regPort = regReadPortFactory(io.reg, pauseBox.pause)
  val rDPort  = specialRegReadPortFactory(io.rD.ack, pauseBox.pause)
  io.rD.req  := rDPort.req
  io.rD.addr := 0.U  // Div 그룹 안에서 rD의 로컬번호(0) — 고정, Div는 rD만 읽는다
  pauseBox.reasons(1) := !regPort.ack
  pauseBox.reasons(2) := !rDPort.ack

  val CompoReg = CompoRegFactory(pauseBox.pause)

  val fetch = new DivFetch(io.op.flag, io.op.x, io.op.y, io.op.z, io.reg, regPort, io.rD, rDPort)

  val fetchBuf = CompoReg(gen = DivFetchResult(), write = regPort.ack && rDPort.ack, d = fetch.res)

  // ── 연산 단계: 인출이 끝난 값으로 실제 나눗셈을 수행 ──
  io.result := new DivExec(fetchBuf.q).res
}
