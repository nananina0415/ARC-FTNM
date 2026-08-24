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

class Comp extends Module {
  val io = IO(new Bundle {
    val op     = Input(CompOp())
    val pause  = Input(Bool())

    val regY   = RegReadPort()
    val regZ   = RegReadPort()

    val result = Output(CompResult())
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

  val y = y_buf.q
  val z = z_buf.q

  val comparator = Module(new Comparator())
  comparator.io.a     := y
  comparator.io.b     := z
  comparator.io.uFlag := io.op.flag(1)

  val cmpRes = comparator.io.res.pad(64).asUInt

  val cond    = io.op.flag(2, 1)
  val condInv = !io.op.flag(3)

  val condsetter = Module(new CondSetter())
  condsetter.io.cond    := cond
  condsetter.io.y       := y
  condsetter.io.z       := z
  condsetter.io.condInv := condInv
  condsetter.io.else0   := io.op.flag(5, 4) === "b11".U

  val res   = WireDefault(0.U(64.W))
  val write = WireDefault(true.B)

  // flag[5:4] — 00=CMP/CMPU, 01=CSWAP(미구현), 10=CS_, 11=ZS_
  switch(io.op.flag(5, 4)) {
    is("b00".U) { res := cmpRes }
    is("b10".U) { res := condsetter.io.res; write := condsetter.io.write }
    is("b11".U) { res := condsetter.io.res; write := condsetter.io.write }
  }

  io.result.dest  := io.op.x
  io.result.res   := res
  io.result.write := write
}
