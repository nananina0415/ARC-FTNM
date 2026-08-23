/** 
 * ADD $X,$Y,$Z|Z — 부호있는 덧셈
 *  $X ← $Y + $Z (레지스터) 또는 $Y + Z (즉치값, 0~255 비음수)
 *  2의 보수 64비트 산술. 결과가 2^63 이상 또는 -2^63 미만이면 정수 오버플로우 예외 발생.
 *
 * ADDU $X,$Y,$Z|Z — 부호없는 덧셈
 *   $X ← ($Y + $Z) mod 2^64 또는 ($Y + Z) mod 2^64
 *   ADD와 동작이 같으나 오버플로우 검사를 하지 않는다.
 *   오버플로우 감지가 필요하면 덧셈 후 CMPU ovflo,$X,$Y 로 확인.
 *
 * 2ADDU $X,$Y,$Z|Z — 2배 곱하고 부호없는 덧셈
 *   $X ← (2·$Y + $Z) mod 2^64 또는 (2·$Y + Z) mod 2^64
 *   오버플로우 검사 없음.
 *
 * 4ADDU $X,$Y,$Z|Z — 4배 곱하고 부호없는 덧셈
 *   $X ← (4·$Y + $Z) mod 2^64 또는 (4·$Y + Z) mod 2^64
 *   오버플로우 검사 없음.
 *
 * 8ADDU $X,$Y,$Z|Z — 8배 곱하고 부호없는 덧셈
 *   $X ← (8·$Y + $Z) mod 2^64 또는 (8·$Y + Z) mod 2^64
 *   오버플로우 검사 없음.
 *
 * 16ADDU $X,$Y,$Z|Z — 16배 곱하고 부호없는 덧셈
 *   $X ← (16·$Y + $Z) mod 2^64 또는 (16·$Y + Z) mod 2^64
 *   오버플로우 검사 없음.
 *
 * SUB $X,$Y,$Z|Z — 부호있는 뺄셈
 *   $X ← $Y - $Z 또는 $Y - Z
 *   2의 보수 64비트 산술. 결과가 2^63 이상 또는 -2^63 미만이면 오버플로우 예외 발생.
 *
 * SUBU $X,$Y,$Z|Z — 부호없는 뺄셈
 *   $X ← ($Y - $Z) mod 2^64 또는 ($Y - Z) mod 2^64
 *   SUB와 동작이 같으나 오버플로우 검사를 하지 않는다.
 *
 * NEG $X,Y,$Z|Z — 부호있는 부정(negate)
 *   $X ← Y - $Z 또는 Y - Z
 *   Y는 레지스터가 아니라 즉치 상수(0~255). 문서에서 명시적으로 구별함.
 *   결과가 2^63 - 1 초과이면 오버플로우 예외. Y=0일 때 $Z = -2^63이면 오버플로우.
 *   NEG $X,1,2는 NEG $X,0,1과 동일한 효과.
 *
 * NEGU $X,Y,$Z|Z — 부호없는 부정(negate)
 *   $X ← (Y - $Z) mod 2^64 또는 (Y - Z) mod 2^64
 *   Y는 즉치 상수(0~255). NEG와 동작이 같으나 오버플로우 검사를 하지 않는다.
*/

package cpu

import chisel3._
import chisel3.util._

case class AddSubOp() extends Bundle {
  val flag = UInt(5.W)
  val x    = UInt(8.W)
  val y    = UInt(8.W)
  val z    = UInt(8.W)
}

case class AddSubResult() extends Bundle {
  val ovf_trap = Bool()
  val ovf      = Bool()
  val carry    = Bool()
  val dest     = UInt(8.W)
  val res      = UInt(64.W)
}

class AddSub extends Module {
  val io = IO(new Bundle {
    val op     = Input(AddSubOp())
    val pause  = Input(Bool())           // 외부 일시정지: 1이면 컴포넌트 전체 홀드

    val regY   = RegReadPort()  // Y 레지스터 읽기 (NEG/NEGU 시 미사용)
    val regZ   = RegReadPort()  // Z 레지스터 읽기 (즉치 시 미사용)

    val result = Output(AddSubResult())  // 연산 결과 와이어
  })

  val adder   = Module(new ComplexAdder64())
  val shifter = Module(new Shifter64Left())

  // 제어 신호 — 스위치 로직에서 구동
  val acc_write   = WireDefault(false.B)  // 1: acc 래치
  val acc_sel     = WireDefault(false.B)  // 1: 애더 결과, 0: Z값
  val y_imm       = WireDefault(false.B)  // 1: Y 즉치, 0: 레지스터 값
  val z_imm       = WireDefault(false.B)  // 1: Z 즉치, 0: 레지스터 값
  val use_shift   = WireDefault(false.B)  // 1: 애더 첫째인자에 시프터 출력 사용 (nADDU)

  // 레지스터 번호 배선 — 연산과 무관하게 항상 연결
  io.regY.addr := io.op.y
  io.regZ.addr := io.op.z

  val CompoReg = compoRegFactory(pause = io.pause)

  val y_buf = CompoReg(
    gen   = UInt(64.W),
    write = true.B,
    d     = Mux(y_imm, io.op.y.pad(64), io.regY.data)
  )

  val acc = CompoReg(
    gen   = UInt(64.W),
    write = true.B,
    d     = Mux(acc_sel, adder.io.res, Mux(z_imm, io.op.z.pad(64), io.regZ.data))
  )

  // y_buf → 시프터 상시 연결, 애더 첫째인자는 use_shift로 선택
  shifter.io.a := y_buf.q

  adder.io.a   := Mux(use_shift, shifter.io.res, y_buf.q)
  adder.io.b   := Mux(adder.io.c0, ~acc.q, acc.q)    // acc → 애더 둘째인자 (상시 연결)

  // op 플래그 파싱 - 가감산
  adder.io.c0  := !io.op.flag(4) & (io.op.flag(3) | io.op.flag(2)) // 뺄셈여부
  y_imm        := !io.op.flag(4) & io.op.flag(3)                   // 부정여부

  // op 플래그 파싱 - 시프트 후 덧셈 (shamt = flag[3:2] + 1)
  use_shift    := io.op.flag(4)
  shifter.io.b := Cat( io.op.flag(3) & io.op.flag(2),
                       io.op.flag(3) ^ io.op.flag(2),
                       !io.op.flag(2)                ).pad(8)

  // op 플래그 파싱 - 공통
  io.result.ovf_trap := !io.op.flag(1)
  z_imm              := io.op.flag(0)

  // 결과 배선 — 연산과 무관하게 상시 연결
  io.result.dest     := io.op.x
  io.result.ovf      := adder.io.ovf
  io.result.carry    := adder.io.carry
  io.result.res      := adder.io.res

}
