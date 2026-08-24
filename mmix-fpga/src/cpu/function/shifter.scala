package cpu

import chisel3._
import chisel3.util._

/** 64비트 논리 왼쪽 시프터.
 *
 * shamt가 64 이상이면 결과는 0. MMIX SL/SLU/nADDU 계열에서 사용.
 * shamt는 부호없는 정수로 처리되며 최대 255까지 받는다.
 */
class Shifter64Left extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(64.W))
    val b   = Input(UInt(8.W))   // 0~255, 64 이상은 결과 0
    val res = Output(UInt(64.W))
  })

  io.res := Mux(io.b >= 64.U, 0.U(64.W), (io.a << io.b)(63, 0))
}

/** 저→고 방향 OR 전파 게이트 4개짜리 블록.
 *
 * res(i) = OR(in(0..i), cin) — 자기 자신 포함, 인접 상위 게이트로 캐리(res(3))가 이어진다.
 */
class Propa4 extends Module {
  val io = IO(new Bundle {
    val in  = Input(UInt(4.W))
    val cin = Input(Bool())
    val res = Output(UInt(4.W))
  })

  val c = Wire(Vec(4, Bool()))
  c(0) := io.in(0) | io.cin
  c(1) := io.in(1) | c(0)
  c(2) := io.in(2) | c(1)
  c(3) := io.in(3) | c(2)

  io.res := c.asUInt
}

/** Propa4 4개를 저→고로 묶은 16비트 블록. */
class Propa16 extends Module {
  val io = IO(new Bundle {
    val in  = Input(UInt(16.W))
    val cin = Input(Bool())
    val res = Output(UInt(16.W))
  })

  val p4 = Seq.fill(4)(Module(new Propa4()))

  p4(0).io.in  := io.in(3, 0)
  p4(0).io.cin := io.cin
  p4(1).io.in  := io.in(7, 4)
  p4(1).io.cin := p4(0).io.res(3)
  p4(2).io.in  := io.in(11, 8)
  p4(2).io.cin := p4(1).io.res(3)
  p4(3).io.in  := io.in(15, 12)
  p4(3).io.cin := p4(2).io.res(3)

  io.res := Cat(p4(3).io.res, p4(2).io.res, p4(1).io.res, p4(0).io.res)
}

/** Propa16 4개를 저→고로 묶은 64비트 블록 — ovf_mask가 사용하는 최상위 전파 회로. */
class Propa64 extends Module {
  val io = IO(new Bundle {
    val in  = Input(UInt(64.W))
    val cin = Input(Bool())
    val res = Output(UInt(64.W))
  })

  val p16 = Seq.fill(4)(Module(new Propa16()))

  p16(0).io.in  := io.in(15, 0)
  p16(0).io.cin := io.cin
  p16(1).io.in  := io.in(31, 16)
  p16(1).io.cin := p16(0).io.res(15)
  p16(2).io.in  := io.in(47, 32)
  p16(2).io.cin := p16(1).io.res(15)
  p16(3).io.in  := io.in(63, 48)
  p16(3).io.cin := p16(2).io.res(15)

  io.res := Cat(p16(3).io.res, p16(2).io.res, p16(1).io.res, p16(0).io.res)
}
