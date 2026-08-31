/*
 * CMP $X,$Y,$Z|Z — 부호있는 비교
 *   $Y < $Z(또는 Z)이면 $X←-1, $Y = $Z(또는 Z)이면 $X←0, 그 외엔 $X←1.
 *   부호있는 산술 규칙으로 비교.
 *
 * CMPU $X,$Y,$Z|Z — 부호없는 비교
 *   CMP와 동일하나 부호없는 산술 규칙으로 비교.
 *
 * CSN/CSZ/CSP/CSOD/CSNN/CSNZ/CSNP/CSEV $X,$Y,$Z|Z — 조건부 설정 (8종)
 *   $Y가 각각 음수/0/양수/홀수/0아님(nonnegative)/0아님(nonzero)/0또는음수(nonpositive)/짝수 조건을
 *   만족하면 $X ← $Z(또는 Z). 조건을 만족하지 않으면 $X는 변하지 않는다 —
 *   레지스터 파일 쓰기 자체를 생략(CompResult.write=false)해서 구현한다.
 *
 * ZSN/ZSZ/ZSP/ZSOD/ZSNN/ZSNZ/ZSNP/ZSEV $X,$Y,$Z|Z — 0 또는 설정 (8종)
 *   CS 계열과 동일한 8가지 조건을 $Y에 대해 검사하지만,
 *   조건을 만족하면 $X ← $Z(또는 Z), 만족하지 않으면 $X ← 0.
 *
 * CSWAP $X,$Y,$Z|Z — 원자적 비교 후 교환 (compare-and-swap octabytes)
 *   메모리 M8[$Y+$Z](또는 $Y+Z)가 특수 레지스터 rP(prediction register)와 같으면
 *   그 메모리를 레지스터 $X의 내용으로 교체하고 $X←1.
 *   다르면 메모리 값을 rP로 옮기고 $X←0.
 *   인터럽트 불가한(uninterruptible) 원자적 연산 — 여러 프로세서가 메모리를 공유할 때
 *   프로세스 간 통신에 사용.
*/

package cpu

import chisel3._
import chisel3.util._

case class CompOp() extends Bundle {
  val flag = UInt(6.W)
  val x    = UInt(8.W)
  val y    = UInt(8.W)
  val z    = UInt(8.W)
}

case class CompResult() extends Bundle {
  val dest  = UInt(8.W)
  val res   = UInt(64.W)
  val write = Bool()  // false면 레지스터 파일 쓰기 생략 (CS_ 조건 불만족 시)
}

/** 인출 단계가 연산 단계로 넘기는 값 — 레지스터버스에서 실제로 받아온 Y/Z. */
case class CompFetchResult() extends Bundle {
  val flag = UInt(6.W)
  val x    = UInt(8.W)
  val y    = UInt(64.W)
  val z    = UInt(64.W)
}

/** 인출 단계 — 레지스터버스에 Y/Z를 요청해서 받아온다. */
class CompFetch(flag: UInt, x: UInt, y: UInt, z: UInt, bus: RegBus, regPort: RegReadPort) {
  private val isImm = flag(0)

  regPort.x.addr := 0.U
  regPort.x.set  := false.B
  regPort.y.addr := y
  regPort.y.set  := true.B
  regPort.z.addr := z
  regPort.z.set  := !isImm

  val res = Wire(CompFetchResult())
  res.flag := flag
  res.x    := x
  res.y    := bus.y.data
  res.z    := Mux(isImm, z.pad(64), bus.z.data)
}

/** 연산 단계 — 인출이 끝난 값으로 실제 비교/조건부설정을 수행한다. */
class CompExec(f: CompFetchResult) {
  private val comparator = Module(new Comparator())
  comparator.io.a     := f.y
  comparator.io.b     := f.z
  comparator.io.uFlag := f.flag(1)

  private val cmpRes = comparator.io.res.pad(64).asUInt

  private val cond    = f.flag(2, 1)
  private val condInv = !f.flag(3)

  private val condsetter = Module(new CondSetter())
  condsetter.io.cond    := cond
  condsetter.io.y       := f.y
  condsetter.io.z       := f.z
  condsetter.io.condInv := condInv
  condsetter.io.else0   := f.flag(5, 4) === "b11".U

  private val res   = WireDefault(0.U(64.W))
  private val write = WireDefault(true.B)

  // flag[5:4] — 00=CMP/CMPU, 01=CSWAP(미구현), 10=CS_, 11=ZS_
  switch(f.flag(5, 4)) {
    is("b00".U) { res := cmpRes }
    is("b10".U) { res := condsetter.io.res; write := condsetter.io.write }
    is("b11".U) { res := condsetter.io.res; write := condsetter.io.write }
  }

  val out = Wire(CompResult())
  out.dest  := f.x
  out.res   := res
  out.write := write
}

class Comp(regReadPortFactory: RegReadPortFactory) extends Module {
  val io = IO(new Bundle {
    val op     = Input(CompOp())
    val pause  = Input(Bool())

    val reg    = new RegBus

    val result = Output(CompResult())
  })

  val pauseBox = new PauseBox(2)
  pauseBox.reasons(0) := io.pause

  val regPort = regReadPortFactory(io.reg, pauseBox.pause)
  pauseBox.reasons(1) := !regPort.ack

  val CompoReg = CompoRegFactory(pauseBox.pause)

  val fetch = new CompFetch(io.op.flag, io.op.x, io.op.y, io.op.z, io.reg, regPort)

  val fetchBuf = CompoReg(gen = CompFetchResult(), write = regPort.ack, d = fetch.res)

  // ── 연산 단계: 인출이 끝난 값으로 실제 비교/조건부설정을 수행 ──
  io.result := new CompExec(fetchBuf.q).out
}
