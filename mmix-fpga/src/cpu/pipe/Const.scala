/*
 * SETH/SETMH/SETML/SETL $X,YZ — 지정 와이드 위치에 값 설정
 *   16비트 부호없는 상수 YZ를 각각 48/32/16/0비트 왼쪽 시프트해 $X에 저장하고,
 *   나머지 세 와이드는 0으로 설정. 네 명령을 조합하면 임의의 64비트 상수를
 *   레지스터에 구성할 수 있다.
 *
 * INCH/INCMH/INCML/INCL $X,YZ — 지정 와이드 위치 증가
 *   YZ를 48/32/16/0비트 시프트해 $X에 더함(오버플로우 무시하고 그대로 저장).
 *   YZ가 #8000일 때 INCH를 쓰면 최상위 비트(부호비트)만 반전되므로
 *   부동소수점 수의 부호를 뒤집는 데 사용할 수 있다.
 *
 * ORH/ORMH/ORML/ORL $X,YZ — 지정 와이드 위치에 OR
 *   YZ를 48/32/16/0비트 시프트해 $X와 비트 OR한 결과를 $X에 저장.
 *
 * ANDNH/ANDNMH/ANDNML/ANDNL $X,YZ — 지정 와이드 위치에 AND-NOT
 *   YZ를 48/32/16/0비트 시프트한 뒤 보수를 취해 $X와 AND한 결과를 $X에 저장.
 *   YZ가 #8000일 때 ANDNH를 쓰면 최상위 비트(부호비트)를 0으로 만들 수 있으므로
 *   부동소수점 수의 절댓값을 계산하는 데 사용할 수 있다.
*/

package cpu

import chisel3._
import chisel3.util._

// SETH/INCH/ORH/ANDNH 계열 — $X,YZ 형식 (16비트 즉치값)
case class ConstOp() extends Bundle {
  val flag = UInt(4.W)
  val x    = UInt(8.W)
  val yz   = UInt(16.W)
}

case class ConstResult() extends Bundle {
  val dest   = UInt(8.W)
  val res    = UInt(64.W)
  val accEnd = Bool()  // true면 체이닝의 마지막 — 커미터가 이때만 실제로 커밋
}

class Const(regReadPortFactory: RegReadPortFactory) extends Module {
  val io = IO(new Bundle {
    val op      = Input(ConstOp())
    val pause   = Input(Bool())
    val accFlag = Input(Bool())  // 1이면 X를 새로 안 읽고 acc에 있는 직전 결과를 그대로 이어씀
    val accEnd  = Input(Bool())  // 1이면 이 명령이 체이닝의 마지막 — 그대로 결과에 실어 보냄

    val reg     = new RegBus

    val result  = Output(ConstResult())
  })

  val op  = io.op.flag(3, 2)  // 0:INC 1:SET 2:OR 3:ANDN
  val pos = io.op.flag(1, 0)

  val widePlace = Module(new WidePlace())
  widePlace.io.v   := io.op.yz
  widePlace.io.pos := pos
  val positioned = widePlace.io.res

  val useLogic    = op(1)         // OR/ANDN → 논리 유닛 경로
  val useZeroBase = op === 1.U    // SET일 때만 X 대신 0에서 시작
  val notB        = op === 3.U    // ANDN일 때만 positioned를 반전

  val pauseFactory = new PauseFactory
  pauseFactory.include(io.pause)

  // regPort 안의 SignalReg들도 pause에 물려야 하는데, 그 pause엔 regPort.ack 자신도 들어가서
  // 먼저 만들 수가 없다 — Wire로 자리만 잡아두고 값은 pauseFactory가 확정된 뒤에 채운다.
  val pauseWire = Wire(Bool())
  val regPort = regReadPortFactory(io.reg, pauseWire)
  pauseFactory.include(!regPort.ack)

  // X는 accFlag=0(새로 시작)이고 SET 계열이 아닐 때만 실제로 결과에 쓰인다 — 그때만 요청한다.
  val needX = !io.accFlag && !useZeroBase
  io.reg.x.addr := io.op.x
  regPort.x.set := needX
  io.reg.y.addr := 0.U
  regPort.y.set := false.B
  io.reg.z.addr := 0.U
  regPort.z.set := false.B

  val freshBase = Mux(useZeroBase, 0.U(64.W), io.reg.x.data)

  val adder = Module(new SimpleAdder64())
  val logic = Module(new LogicUnit())

  // acc만 실제 상태를 갖는 레지스터다 — freshBase/positioned은 매 사이클 그대로 조합적으로
  // 흘러들어가고(accFlag=false일 땐 이전 사이클에 기댈 게 없는 완전히 새 계산이라 래치가 딱히 필요 없다)
  // pause가 보호해야 하는 건 체이닝 중인 acc뿐이다. X를 기다리는 동안도 pause에 포함되니
  // (regPort.ack) write=true.B라도 그 사이엔 CompoReg 자체가 안 걸린다.
  val CompoReg = CompoRegFactory(pauseFactory)
  pauseWire := pauseFactory.pause
  val acc = CompoReg(
    gen   = UInt(64.W),
    write = true.B,
    d     = Mux(useLogic, logic.io.res, adder.io.res)  // 항상 이번 결과를 그대로 래치
  )

  val aOperand = Mux(io.accFlag, acc.q, freshBase)

  adder.io.a := aOperand
  adder.io.b := positioned

  logic.io.a      := aOperand
  logic.io.b      := positioned
  logic.io.notB   := notB
  logic.io.notRes := false.B
  logic.io.op     := Mux(notB, 1.U, 0.U)  // 0=OR, 1=AND(ANDN은 notB로 반전)

  io.result.dest   := io.op.x
  io.result.res    := Mux(useLogic, logic.io.res, adder.io.res)
  io.result.accEnd := io.accEnd
}
