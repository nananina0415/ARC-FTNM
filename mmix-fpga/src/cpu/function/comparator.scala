package cpu

import chisel3._
import chisel3.util._

/** a와 b를 비교해 -1(a<b) / 0(a==b) / 1(a>b)을 돌려준다.
 *
 * uFlag=true(부호없음)면 부호비트를 항상 0으로 강제해서 모든 케이스가
 * 크기 비교(64비트 부호없는 비교) 브랜치로 수렴한다 — 부호있음/없음이 같은 회로를 공유한다.
 */
class Comparator extends Module {
  val io = IO(new Bundle {
    val a     = Input(UInt(64.W))
    val b     = Input(UInt(64.W))
    val uFlag = Input(Bool())
    val res   = Output(SInt(2.W))
  })

  val a0 = io.a === 0.U
  val b0 = io.b === 0.U
  val aSign = io.a(63) && !io.uFlag
  val bSign = io.b(63) && !io.uFlag

  val magCmp = Mux(io.a === io.b, 0.S(2.W), Mux(io.a > io.b, 1.S(2.W), -1.S(2.W)))

  io.res := MuxCase(magCmp, Seq(
    (a0 && b0)                      -> 0.S(2.W),
    (a0 && !b0 && !bSign)           -> -1.S(2.W),
    (a0 && !b0 && bSign)            -> 1.S(2.W),
    (!a0 && !aSign && b0)           -> 1.S(2.W),
    (!a0 && aSign && b0)            -> -1.S(2.W),
    (!a0 && !aSign && !b0 && bSign) -> 1.S(2.W),
    (!a0 && aSign && !b0 && !bSign) -> -1.S(2.W)
  ))
}

/** CS_/ZS_ 8조건(NEG/ZERO/POS/ODD 및 그 반전)을 판정해 결과를 만든다.
 *
 * NEG/ZERO/POS는 Comparator(y,0)의 결과를 재사용하고, ODD만 y의 최하위 비트를 따로 본다.
 * cond: 0=NEG 1=ZERO 2=POS 3=ODD. condInv=true면 결과를 반전(NOTNEG/NOTZERO/NOTPOS/EVEN).
 * else0=false(CS_)면 조건 불만족 시 write=false, else0=true(ZS_)면 항상 write=true고
 * 불만족 시 res=0.
 */
class CondSetter extends Module {
  val io = IO(new Bundle {
    val cond    = Input(UInt(2.W))
    val y       = Input(UInt(64.W))
    val z       = Input(UInt(64.W))
    val condInv = Input(Bool())
    val else0   = Input(Bool())

    val res   = Output(UInt(64.W))
    val write = Output(Bool())
  })

  val comparator = Module(new Comparator())
  comparator.io.a     := io.y
  comparator.io.b     := 0.U
  comparator.io.uFlag := false.B

  val baseSatisfied = MuxLookup(io.cond, io.y(0))(Seq(
    0.U -> (comparator.io.res === -1.S),
    1.U -> (comparator.io.res === 0.S),
    2.U -> (comparator.io.res === 1.S),
    3.U -> io.y(0)
  ))

  val satisfied = Mux(io.condInv, !baseSatisfied, baseSatisfied)

  io.res   := Mux(satisfied, io.z, 0.U)
  io.write := io.else0 || satisfied
}
