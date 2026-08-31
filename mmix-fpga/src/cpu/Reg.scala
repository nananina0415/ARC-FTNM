// Reg는 레지스터 접근을 담당한다.
// 쓰기는 커미터에서만 수행하고 읽기는 각 파이프에서 요청한다.
// 항상 쓰기가 우선된다.
// 읽기는 reorder 버퍼의 앞에있는 파이프의 요청부터 처리한다.

package cpu

import chisel3._

/** 레지스터버스에 대한 요청 슬롯 하나(예: X/Y/Z 중 하나) — 파이프 자신의 io에 직접
 * 들어가는 순수 배선이다(그래야 밖에서 닿을 수 있다). 상태(SignalReg)는 안 갖는다.
 * addr/req는 파이프가 밖으로 내보내고, data/ack는 버스(중재기)가 파이프로 넣어준다.
 */
class RegSlot extends Bundle {
  val addr = Output(UInt(8.W))
  val data = Input(UInt(64.W))
  val ack  = Input(Bool())
  val req  = Output(Bool())
}

/** RegSlot 3개(X/Y/Z) 묶음 — 파이프의 io에 그대로 필드로 들어간다. */
class RegBus extends Bundle {
  val x = new RegSlot
  val y = new RegSlot
  val z = new RegSlot
}

/** 슬롯 하나(x/y/z 중 하나)의 제어 신호 — 파이프가 여기다 쓴다. addr은 RegSlot.addr과
 * 이름은 같지만 별개 포트다: RegSlot.addr은 "아비터로 나가는" Output이라 파이프(RegReadPort의
 * 부모) 밖에서 못 쓰고, 이건 RegReadPort 자신의 io로 새로 선언한 Input이라 파이프가 쓸 수
 * 있다.
 */
class RegSlotControl extends Bundle {
  val addr = Input(UInt(8.W))
  val set  = Input(Bool())
}

/** 상태(SignalReg 3개)를 갖는 진짜 Module. bus는 아비터 쪽 실제 연결 전용이라 파이프가
 * 직접 안 건드리고(팩토리가 relay), 파이프는 x/y/z 제어 필드와 ack만 본다.
 */
class RegReadPort extends Module {
  val io = IO(new Bundle {
    val x = new RegSlotControl
    val y = new RegSlotControl
    val z = new RegSlotControl
    val pause = Input(Bool())
    val ack   = Output(Bool())
    val bus   = new RegBus  // 아비터 쪽 — 파이프는 참조 안 함, RegReadPortFactory가 relay
  })

  private def track(ctrl: RegSlotControl, slot: RegSlot): SignalReg = {
    val r = Module(new SignalReg)
    r.clear   := slot.ack
    r.pause   := io.pause  // 이 SignalReg 바깥에서 온 pause만 — 자기 ack로 만들어진 pause를 자기 게이팅에 되먹이면 순환이 된다
    r.set     := ctrl.set
    slot.addr := ctrl.addr
    slot.req  := r.q
    r
  }
  private val xTrack = track(io.x, io.bus.x)
  private val yTrack = track(io.y, io.bus.y)
  private val zTrack = track(io.z, io.bus.z)

  /** x/y/z 전부 0(다 받음)일 때만 1. */
  io.ack := !(xTrack.q || yTrack.q || zTrack.q)

  // 파이프가 .io. 없이 짧게 쓸 수 있게 하는 별칭
  def x: RegSlotControl = io.x
  def y: RegSlotControl = io.y
  def z: RegSlotControl = io.z
  def ack: Bool = io.ack
  def pause_=(p: Bool): Unit = { io.pause := p }
}

/** 레지스터버스 중재기 — 목업. 여러 파이프의 동시 요청을 어떻게 처리할지는 아직 미정. */
class RegArbiter extends Module {
  val io = IO(new Bundle {})
}

/** RegArbiter를 캡처해서 그 버스에 연결된 RegReadPort(요청-대기 로직)를 찍어내는 팩토리.
 * cpu 수준에서 하나 만들어서 각 파이프 생성 시 주입한다. bus(파이프의 io.reg) ↔
 * RegReadPort의 내장 bus 사이 relay는 이 팩토리가 대신 해준다 — Module이 아닌 평범한
 * 함수 안에서 실행되므로 호출한 파이프 자신의 elaboration 범위 안에서 일어난다
 * (조부모 문제가 생기지 않는다).
 *
 * val regReadPortFactory = new RegReadPortFactory(regArbiter)
 * class SomePipe(regReadPortFactory: RegReadPortFactory) extends Module {
 *   val io = IO(new Bundle { val reg = new RegBus; ... })   // reg는 io 필드 — 밖에서 닿음
 *   val regPort = regReadPortFactory(io.reg, pauseBox.pause)
 * }
 */
class RegReadPortFactory(regArbiter: RegArbiter) {
  def apply(bus: RegBus, pause: Bool): RegReadPort = {
    val m = Module(new RegReadPort)
    m.pause_=(pause)
    bus.x.addr := m.io.bus.x.addr; bus.x.req := m.io.bus.x.req; m.io.bus.x.ack := bus.x.ack; m.io.bus.x.data := bus.x.data
    bus.y.addr := m.io.bus.y.addr; bus.y.req := m.io.bus.y.req; m.io.bus.y.ack := bus.y.ack; m.io.bus.y.data := bus.y.data
    bus.z.addr := m.io.bus.z.addr; bus.z.req := m.io.bus.z.req; m.io.bus.z.ack := bus.z.ack; m.io.bus.z.data := bus.z.data
    m
  }
}

/** 옛 방식의 단순 즉시읽기 포트 — 아직 새 RegReadPort로 마이그레이션 안 된 파이프들이 씀. */
case class LegacyRegReadPort() extends Bundle {
  val addr = Output(UInt(8.W))
  val data = Input(UInt(64.W))
}



// ==========================================================================
//  레지스터 외 상태값 (카운터 등)
// ==========================================================================

/** λ(현재 명령어 주소, program counter) 카운터. 파이프라인 내부 레지스터가 아니라
 * pause에 연결되지 않는다 — fetch 단계가 독립적으로 관리하는 전역 상태.
 *
 * mode=11(clear)이면 즉시(비동기) 0으로 리셋된다. mode=10(set)이면 다음 클럭에 load
 * 값을 그대로 싣는다(분기/점프 타겟 등 외부에서 계산해 넣어줌). mode=01(count)이면
 * 매 클럭 4씩 증가(명령어 하나가 4바이트). mode=00(stay)이면 아무 데도 안 걸려서
 * Chisel Reg 기본 동작대로 값을 그대로 유지한다. clear는 리셋이라 mode의 다른 어떤
 * 값보다도 우선한다(비동기 리셋이 D입력보다 항상 이김).
 */
class ProgramCounter extends Module {
  val mode  = IO(Input(UInt(2.W)))  // 00 stay, 01 count, 10 set, 11 clear
  val load  = IO(Input(UInt(64.W)))
  val count = IO(Output(UInt(64.W)))

  private val doClear = mode === 3.U
  private val doCount = mode === 1.U
  private val doSet   = mode === 2.U

  val adder = Module(new SimpleAdder64())

  val reg = withReset(doClear.asAsyncReset) { RegInit(0.U(64.W)) }
  adder.io.a := reg
  adder.io.b := 4.U(64.W)

  when(doSet) {
    reg := load
  }.elsewhen(doCount) {
    reg := adder.io.res
  }

  count := reg
}

/** λ(program counter) 읽기 전용 포트 — ProgramCounter의 현재 값을 그대로 읽는다.
 * 특수 레지스터 포트와 달리 req/ack가 없다 — PC는 매 사이클 항상 유효한 값을 내놓는
 * 조합 출력이라(중재기를 거쳐야 하는 공유자원이 아니다) 기다릴 이유가 없다. GETA 등에서
 * 그대로 가져다 쓰면 된다.
 */
case class ProgramCounterReadPort() extends Bundle {
  val data = Input(UInt(64.W))
}




// ==========================================================================
//  특수 레지스터
// ==========================================================================

// 특수 레지스터는 범용레지스터처럼 256개가 파이프당 버스 하나를 공유하는 게 아니라,
// 레지스터 하나하나가 그걸 쓰는 파이프에만 직결되는 전용선이다. 그래서 addr 필드가 없고,
// 레지스터별로 이름이 다른 포트 타입을 따로 둔다 — 타입이 다르면 실수로 다른 레지스터에
// 연결하는 것 자체가 컴파일 에러가 되고, 여러 레지스터를 하나의 공용 포트로 묶어 쓰고 싶은
// 유혹도 구조적으로 막힌다.

/** 읽기 전용 특수 레지스터 포트의 요청-대기 SignalReg를 갖는 진짜 Module — rD든 다른
 * 읽기전용 특수레지스터든 ack/req 배선 모양만 같으면 전부 이 로직 하나를 공유한다.
 * 물리 레지스터가 어디에 연결됐는지는 진입점 타입(RegD_R 등)마다 다르지만, 로직 자체는
 * 같기 때문이다. addr이 없다 — 슬롯 하나뿐인 전용선이라 고를 레지스터가 하나뿐이다.
 */
class SpecialRegReadPort extends Module {
  val io = IO(new Bundle {
    val set   = Input(Bool())
    val ackIn = Input(Bool())   // 커미터 쪽에서 오는 ack
    val pause = Input(Bool())
    val req   = Output(Bool())
    val ack   = Output(Bool())
  })
  private val r = Module(new SignalReg)
  r.clear := io.ackIn
  r.pause := io.pause  // RegReadPort와 같은 이유 — 자기 ack로 만들어진 pause를 되먹이면 안 됨
  r.set   := io.set
  io.req  := r.q
  io.ack  := !r.q

  def set_=(s: Bool): Unit   = { io.set   := s }
  def ackIn_=(a: Bool): Unit = { io.ackIn := a }
  def pause_=(p: Bool): Unit = { io.pause := p }
  def req: Bool = io.req
  def ack: Bool = io.ack
}

/** 특수 레지스터는 범용레지스터버스처럼 여러 파이프가 공유하는 큰 중재기가 아니라,
 * 레지스터마다 자기만의 작은 중재기(그 레지스터를 쥔 쪽, 예: 커미터, 하나와만 조율)를
 * 갖는다. 지금은 그 작은 중재기가 아직 없어서 캡처할 게 없을 뿐이고, 생기면 이 팩토리에
 * 넣어서 SpecialRegReadPort를 그 중재기에 연결하는 자리로 쓰면 된다.
 */
class SpecialRegReadPortFactory {
  def apply(ackIn: Bool, pause: Bool): SpecialRegReadPort = {
    val m = Module(new SpecialRegReadPort)
    m.ackIn_=(ackIn)
    m.pause_=(pause)
    m
  }
}

// ==========================================================================
//  특수 레지스터 — 그룹 포트
// ==========================================================================
// 레지스터마다 타입을 따로 두는 대신, 실제로 그 레지스터를 쓰는 파이프 단위로 묶는다.
// 한 그룹의 레지스터들은 항상 그 파이프 하나만 쓰므로(다른 파이프가 같은 포트를 동시에
// 경합할 일이 없으므로) addr로 골라 공유해도 안전하다 — 그룹 안에 레지스터가 하나뿐이면
// (Mul/A/Mask) addr 자체가 필요 없다.
//
// addr은 GET/PUT이 쓰는 전역 코드번호(REG_CODE_*, 0~31)가 아니라 그룹 안에서만 쓰는
// 로컬 번호다. 쓰기가 금지된 레지스터가 섞인 그룹은 그 로컬 번호를 "쓰기 가능한 것부터
// 낮은 값으로" 배정해서, 쓰기 포트의 addr 폭 자체를 읽기보다 좁게 만든다 — 그러면 금지된
// 레지스터의 로컬 번호는 그 좁은 폭으로 아예 표현이 안 되니, 타입(비트 폭)만으로 "이
// 레지스터엔 못 쓴다"가 강제된다(런타임 체크가 필요 없다). 필요하면 읽기 폭을 최소치보다
// 일부러 넓혀서(예: else 그룹처럼) 이 분리를 만들어낸다.
//
//   Div   그룹: rD=0, rR=1                                                    (Div 파이프)
//               — 2개 다 쓰기 가능, addr 1비트(읽기/쓰기 동일)
//   Mul   그룹: rH                                                            (Mul 파이프)
//               — 1개뿐이라 addr 없음
//   A     그룹: rA                                                            (거의 모든 파이프)
//               — 1개뿐이라 addr 없음
//   Jump  그룹: rJ=0,rB=1,rBB=2,rK=3,rQ=4,rT=5,rTT=6,rW=7,rWW=8,rX=9,rXX=10,
//               rY=11,rYY=12,rZ=13,rZZ=14                                     (Jump: TRAP/TRIP/RESUME)
//               — 15개 다 쓰기 가능, addr 4비트(읽기/쓰기 동일)
//   Mem   그룹: 쓰기가능 rP=0,rG=1,rL=2 / 쓰기금지 rO=4,rS=5                    (Mem: CSWAP, SAVE/UNSAVE)
//               — 읽기 addr 3비트(5개), 쓰기 addr 2비트(3개만 — 100/101은 2비트로 표현 불가)
//   Mask  그룹: rM                                                            (Bitwise: MOR/MXOR)
//               — 1개뿐이라 addr 없음
//   else  그룹: 쓰기가능 rE=0,rF=1,rI=2,rU=3,rV=4 / 쓰기금지 rC=8,rN=9          (아직 어떤 파이프도 안 씀)
//               — 읽기 addr 4비트(일부러 넓힘, 7개인데 8비트공간 씀), 쓰기 addr 3비트(5개만)

/** Div 그룹(rD, rR) 읽기 포트. addr: 0=rD, 1=rR. */
case class SpecialRegPort_Div_R() extends Bundle {
  val addr = Output(UInt(1.W))
  val data = Input(UInt(64.W))
  val ack  = Input(Bool())
  val req  = Output(Bool())
}
/** Div 그룹(rD, rR) 쓰기 포트 — 2개 다 쓰기 가능이라 읽기와 폭이 같다. */
case class SpecialRegPort_Div_W() extends Bundle {
  val addr  = Output(UInt(1.W))
  val data  = Output(UInt(64.W))
  val write = Output(Bool())
}

/** Mul 그룹(rH) 읽기 포트 — 레지스터가 하나뿐이라 addr 없음. */
case class SpecialRegPort_Mul_R() extends Bundle {
  val data = Input(UInt(64.W))
  val ack  = Input(Bool())
  val req  = Output(Bool())
}
/** Mul 그룹(rH) 쓰기 포트. */
case class SpecialRegPort_Mul_W() extends Bundle {
  val data  = Output(UInt(64.W))
  val write = Output(Bool())
}

/** A 그룹(rA) 읽기 포트 — 레지스터가 하나뿐이라 addr 없음. */
case class SpecialRegPort_A_R() extends Bundle {
  val data = Input(UInt(64.W))
  val ack  = Input(Bool())
  val req  = Output(Bool())
}
/** A 그룹(rA) 쓰기 포트. */
case class SpecialRegPort_A_W() extends Bundle {
  val data  = Output(UInt(64.W))
  val write = Output(Bool())
}

/** Jump 그룹(rJ, rB, rBB, rK, rQ, rT, rTT, rW, rWW, rX, rXX, rY, rYY, rZ, rZZ —
 * TRAP/TRIP/RESUME 전체) 읽기 포트. 15개 다 쓰기 가능이라 addr 폭이 읽기/쓰기 동일.
 */
case class SpecialRegPort_Jump_R() extends Bundle {
  val addr = Output(UInt(4.W))
  val data = Input(UInt(64.W))
  val ack  = Input(Bool())
  val req  = Output(Bool())
}
/** Jump 그룹 쓰기 포트. */
case class SpecialRegPort_Jump_W() extends Bundle {
  val addr  = Output(UInt(4.W))
  val data  = Output(UInt(64.W))
  val write = Output(Bool())
}

/** Mem 그룹(rP, rO, rS, rG, rL — CSWAP + SAVE/UNSAVE) 읽기 포트. 5개 전부 addr로 고른다. */
case class SpecialRegPort_Mem_R() extends Bundle {
  val addr = Output(UInt(3.W))
  val data = Input(UInt(64.W))
  val ack  = Input(Bool())
  val req  = Output(Bool())
}
/** Mem 그룹 쓰기 포트 — rO/rS(쓰기금지)는 로컬번호가 4/5라 2비트로는 표현이 안 돼서
 * 애초에 이 addr로 못 고른다.
 */
case class SpecialRegPort_Mem_W() extends Bundle {
  val addr  = Output(UInt(2.W))
  val data  = Output(UInt(64.W))
  val write = Output(Bool())
}

/** Mask 그룹(rM) 읽기 포트 — 레지스터가 하나뿐이라 addr 없음. */
case class SpecialRegPort_Mask_R() extends Bundle {
  val data = Input(UInt(64.W))
  val ack  = Input(Bool())
  val req  = Output(Bool())
}
/** Mask 그룹(rM) 쓰기 포트. */
case class SpecialRegPort_Mask_W() extends Bundle {
  val data  = Output(UInt(64.W))
  val write = Output(Bool())
}

/** else 그룹(rC, rE, rF, rI, rN, rU, rV — 지금 구현된 어떤 파이프도 안 씀) 읽기 포트.
 * 7개뿐이라 3비트로도 충분하지만, 쓰기금지인 rC/rN을 4비트째로 밀어내려고 일부러
 * 4비트를 쓴다.
 */
case class SpecialRegPort_else_R() extends Bundle {
  val addr = Output(UInt(4.W))
  val data = Input(UInt(64.W))
  val ack  = Input(Bool())
  val req  = Output(Bool())
}
/** else 그룹 쓰기 포트 — rC/rN(쓰기금지)는 로컬번호가 8/9라 3비트로는 표현이 안 돼서
 * 애초에 이 addr로 못 고른다.
 */
case class SpecialRegPort_else_W() extends Bundle {
  val addr  = Output(UInt(3.W))
  val data  = Output(UInt(64.W))
  val write = Output(Bool())
}
