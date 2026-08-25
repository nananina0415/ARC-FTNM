package cpu

import chisel3._
import chisel3.util._

/** 16비트 값 v를 pos(0=HIGHEST..3=LOWEST)가 가리키는 16비트 슬롯에 배치하고
 * 나머지 세 슬롯은 0으로 채운 64비트 값을 만든다.
 *
 * pos를 원-핫으로 디코드해서 네 슬롯을 각각 독립적으로 게이팅하는 와이드 멀티플렉서 —
 * 선택 안 된 슬롯은 전부 0.
 */
class WidePlace extends Module {
  val io = IO(new Bundle {
    val v   = Input(UInt(16.W))
    val pos = Input(UInt(2.W))
    val res = Output(UInt(64.W))
  })

  val sel = UIntToOH(io.pos, 4)

  val slot0 = Mux(sel(0), io.v, 0.U(16.W))  // HIGHEST → 비트[63:48]
  val slot1 = Mux(sel(1), io.v, 0.U(16.W))  // HIGHER  → 비트[47:32]
  val slot2 = Mux(sel(2), io.v, 0.U(16.W))  // LOWER   → 비트[31:16]
  val slot3 = Mux(sel(3), io.v, 0.U(16.W))  // LOWEST  → 비트[15:0]

  io.res := Cat(slot0, slot1, slot2, slot3)
}
