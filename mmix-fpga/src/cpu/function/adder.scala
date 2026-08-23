package cpu

import chisel3._

/** 64비트 덧셈/뺄셈기.
 *
 * c0(캐리인)을 지원해 감산기로도 사용할 수 있다.
 * 뺄셈 시 호출자가 b를 비트반전(~b)하고 c0=1을 넘기면 a + ~b + 1 = a - b 가 된다.
 *
 * carry: 비트64, 부호없는 오버플로우
 * ovf:   두 입력 부호가 같은데 결과 부호가 다른 경우 (부호있는 오버플로우)
 */
class ComplexAdder64 extends Module {
  val io = IO(new Bundle {
    val a     = Input(UInt(64.W))
    val b     = Input(UInt(64.W))
    val c0    = Input(Bool())       // 캐리인: 뺄셈 시 1
    val res   = Output(UInt(64.W))
    val carry = Output(Bool())
    val ovf   = Output(Bool())
  })

  // 65비트로 확장 후 합산 — 최대값 (2^64-1)*2+1 = 2^65-1 이므로 65비트로 충분
  val full  = io.a.pad(65) + io.b.pad(65) + io.c0.asUInt
  io.res   := full(63, 0)
  io.carry := full(64)
  // 두 피연산자 부호가 같고 결과 부호가 달라지면 오버플로우
  io.ovf   := (io.a(63) === io.b(63)) && (io.res(63) =/= io.a(63))
}
