/*
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

/** flag/x를 Y/acc와 같은 타이밍으로 붙잡아두는 작은 버퍼 — 버스 대기 중에 io.op가
 * 바뀌어도(다음 명령으로 스케줄러가 넘어가도) dest/플래그가 지금 인출 중인 명령 것과
 * 어긋나지 않게 한다.
 */
case class AddSubCtrl() extends Bundle {
  val flag = UInt(5.W)
  val x    = UInt(8.W)
}

/** 인출 단계 — 레지스터버스에 Y/Z를 요청해서 받아온다. Y는 NEG/NEGU일 때만 즉치라
 * 그때만 요청을 건너뛰고, Z는 기존과 같이 즉치 모드일 때만 건너뛴다.
 */
class AddSubFetch(flag: UInt, x: UInt, y: UInt, z: UInt, bus: RegBus, regPort: RegReadPort) {
  private val yImm = !flag(4) & flag(3)  // 부정여부(NEG/NEGU) — 이때만 Y자리가 즉치
  private val zImm = flag(0)

  bus.x.addr    := 0.U
  regPort.x.set := false.B
  bus.y.addr    := y
  regPort.y.set := !yImm
  bus.z.addr    := z
  regPort.z.set := !zImm

  val y64    = Mux(yImm, y.pad(64), bus.y.data)
  val zAcc64 = Mux(zImm, z.pad(64), bus.z.data)

  val ctrl = Wire(AddSubCtrl())
  ctrl.flag := flag
  ctrl.x    := x
}

/** 연산 단계 — 인출이 끝난 Y/acc 값으로 시프트+덧셈을 수행한다. */
class AddSubExec(y: UInt, accIn: UInt, flag: UInt, x: UInt) {
  private val adder   = Module(new ComplexAdder64())
  private val shifter = Module(new Shifter64Left())

  // op 플래그 파싱 - 시프트 후 덧셈 (shamt = flag[3:2] + 1)
  private val useShift = flag(4)
  shifter.io.a := y
  shifter.io.b := Cat( flag(3) & flag(2),
                       flag(3) ^ flag(2),
                       !flag(2)          ).pad(8)

  adder.io.a  := Mux(useShift, shifter.io.res, y)
  // op 플래그 파싱 - 가감산
  adder.io.c0 := !flag(4) & (flag(3) | flag(2))  // 뺄셈여부
  adder.io.b  := Mux(adder.io.c0, ~accIn, accIn) // acc → 애더 둘째인자

  val res = Wire(AddSubResult())
  res.dest     := x
  res.ovf      := adder.io.ovf
  res.carry    := adder.io.carry
  res.res      := adder.io.res
  res.ovf_trap := !flag(1)
}

class AddSub(regReadPortFactory: RegReadPortFactory) extends Module {
  val io = IO(new Bundle {
    val op     = Input(AddSubOp())
    val pause  = Input(Bool())           // 외부 일시정지: 1이면 컴포넌트 전체 홀드

    val reg    = new RegBus

    val result = Output(AddSubResult())  // 연산 결과 와이어
  })

  val pauseFactory = new PauseFactory
  pauseFactory.include(io.pause)

  // regPort 안의 SignalReg들도 pause에 물려야 하는데, 그 pause엔 regPort.ack 자신도 들어가서
  // 먼저 만들 수가 없다 — Wire로 자리만 잡아두고 값은 pauseFactory가 확정된 뒤에 채운다.
  val pauseWire = Wire(Bool())
  val regPort = regReadPortFactory(io.reg, pauseWire)
  pauseFactory.include(!regPort.ack)

  val CompoReg = CompoRegFactory(pauseFactory)
  pauseWire := pauseFactory.pause

  val fetch = new AddSubFetch(io.op.flag, io.op.x, io.op.y, io.op.z, io.reg, regPort)

  val y_buf   = CompoReg(gen = UInt(64.W), write = regPort.ack, d = fetch.y64)
  val ctrlBuf = CompoReg(gen = AddSubCtrl(), write = regPort.ack, d = fetch.ctrl)

  // acc_sel — 애더 결과를 다시 acc로 되먹이는 체이닝 선택인데, 아직 설계가 안 끝나서
  // 항상 false로 둔다(원래 동작 그대로 — 매번 새로 받아온 Z만 래치).
  val acc_sel  = WireDefault(false.B)
  val accDWire = Wire(UInt(64.W))
  val acc      = CompoReg(gen = UInt(64.W), write = regPort.ack, d = accDWire)

  val exec = new AddSubExec(y_buf.q, acc.q, ctrlBuf.q.flag, ctrlBuf.q.x)
  accDWire := Mux(acc_sel, exec.res.res, fetch.zAcc64)

  io.result := exec.res
}
