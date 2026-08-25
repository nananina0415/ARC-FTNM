package cpu

import chisel3._
import scala.collection.mutable.ArrayBuffer

/** pause=0 && write=1일 때만 D를 래치하는 레지스터.
 *
 * pause=1: write 무관하게 Q 유지 (파이프 일시정지)
 * write=1: 다음 클럭에 D를 래치
 */
class CompoReg[T <: Data](gen: T) extends Module {
  val pause = IO(Input(Bool()))
  val write = IO(Input(Bool()))
  val d     = IO(Input(gen))
  val q     = IO(Output(gen))

  val reg = RegInit(0.U.asTypeOf(gen))
  when(!pause && write) { reg := d }
  q := reg
}

/** PauseFactory를 캡처한 CompoReg 팩토리.
 *
 * val CR = CompoRegFactory(pauseFactory)
 * val y_buf = CR(gen = UInt(64.W), write = ..., d = ...)
 */
object CompoRegFactory {
  def apply(pauseFactory: PauseFactory) = new {
    private val pause = pauseFactory.pause  // 버튼을 다 만든 뒤 마지막에 호출돼야 함
    def apply[T <: Data](gen: T, write: Bool, d: T) = {
      val m = Module(new CompoReg(gen))
      m.pause := pause
      m.write := write
      m.d     := d
      m
    }
  }
}

/** set에서 다음 클럭에 1로, clear가 서면 그 즉시(비동기) 0으로 돌아가는 요청-대기 플래그.
 * 파이프 안의 다른 레지스터들과 마찬가지로 pause에 연결된다 — pause=1인 동안은 set이
 * 안 먹혀서(이미 끝난 요청을 재무장하지 않고) 값을 그대로 유지한다.
 *
 * clear는 pause와 무관하게 그대로 즉시(비동기) 반응한다 — q를 읽는 곳이 어디든 clear가
 * 서는 바로 그 사이클에 즉시 0을 보게 만든다(저장값과 노출값이 하나의 물리적 노드다).
 * clear를 pause로 게이팅하면 안 되는 이유: pause 자체가 이 SignalReg의 q(예: 레지스터
 * 대기중 여부)로부터 만들어지는 경우가 많아서, clear를 pause로 막으면 응답 펄스를
 * 영영 놓치고 순환적으로 멈춰버릴 수 있다. set/clear가 같은 사이클에 겹치면 clear가
 * 이긴다(리셋이 D입력보다 항상 우선).
 *
 * 리셋 소스를 clear "만"으로 완전히 대체하면 안 된다 — 한 번도 요청된 적 없는 슬롯(예:
 * 아예 안 쓰는 레지스터, 혹은 이번엔 필요 없어서 요청을 안 건 경우)은 clear도 평생
 * 안 걸려서 전원 인가 시(시뮬레이션 시작 시) 초기값이 정의되지 않는다. 그래서 모듈
 * 자신의 기본 reset도 같이 OR로 걸어서, clear가 한 번도 안 왔어도 최초 리셋 펄스로
 * 반드시 false로 시작하게 만든다.
 */
class SignalReg extends Module {
  val set   = IO(Input(Bool()))
  val clear = IO(Input(Bool()))
  val pause = IO(Input(Bool()))
  val q     = IO(Output(Bool()))

  val reg = withReset((reset.asBool || clear).asAsyncReset) {
    RegInit(false.B)
  }
  when(!pause && set) { reg := true.B }
  q := reg
}

object SignalReg {
  def apply(set: Bool, clear: Bool, pause: Bool): Bool = {
    val m = Module(new SignalReg)
    m.set   := set
    m.clear := clear
    m.pause := pause
    m.q
  }
}

/** 정지 사유(조합 신호)들을 모아서 하나의 읽기전용 pause로 합친다.
 *
 * 각 정지 사유는 [[include]]로 등록한다. [[pause]]는 지금까지 등록된 모든 사유의 OR —
 * 파이프 전체가 보는 진짜 pause다.
 *
 * val pauseFactory = new PauseFactory
 * pauseFactory.include(someStallCondition)
 * ...
 * val CompoReg = CompoRegFactory(pauseFactory)  // 사유를 다 등록한 뒤 마지막에 확정
 */
class PauseFactory {
  private val reasons = ArrayBuffer[Bool]()
  private var finalized = false

  /** 정지 사유(조합 신호)를 그대로 등록한다. 레지스터를 새로 만들지 않으므로 지연이 없다 —
   * 등록한 신호가 바뀌는 바로 그 사이클에 pause에 반영된다.
   */
  def include(w: Bool): Unit = {
    require(!finalized, "pause를 이미 읽은 뒤에는 사유를 더 추가할 수 없다")
    reasons += w
  }

  /** 지금까지 등록된 모든 사유의 OR. 호출 시점에 확정(finalize)되어 이후 include는 금지된다. */
  def pause: Bool = {
    finalized = true
    reasons.foldLeft(false.B)(_ || _)
  }
}

/** 파이프 ↔ 범용레지스터 버스 사이의 실제 포트. 256개 레지스터가 버스 하나를 공유하므로
 * 요청(req)을 걸고 버스 쪽이 자기 차례가 됐을 때 data+ack를 돌려주는 방식이다.
 */
class RegPort extends Bundle {
  val addr = Output(UInt(8.W))
  val req  = Output(Bool())    // 이 포트가 버스에 요청 중 — plz의 바깥쪽 얼굴
  val data = Input(UInt(64.W))
  val ack  = Input(Bool())     // 이번 사이클에 data가 유효함(요청이 처리됨)
}

/** RegPort 하나 만들 때마다 received/buf 상태기계를 구성한다.
 *
 * plz(= !received & valid)는 레지스터가 아니라 조합 와이어다 — "아직 못 받았고 명령은
 * 유효하다"는 조건 그 자체라서, received만 상태로 있으면 plz는 매 사이클 즉시 다시
 * 계산되는 값일 뿐이다. 레지스터로 한 번 더 감쌌으면 pause 반영이 한 사이클 밀렸을 것.
 *
 * - valid=0: received/buf 리셋 (새 명령을 위해 완전히 비움), plz도 자연히 0
 * - valid=1, received=0: plz=1 — 버스에 요청 중. ack가 오면 buf←data, received←1
 * - valid=1, received=1: 이미 받아놓음 — plz=0, valid가 계속 걸려있어도 재요청하지 않음
 *
 * plz는 조합 와이어 그대로 PauseFactory에 포함되어(include), 요청이 안 끝난 동안
 * 파이프 전체가 지연 없이 즉시 멈춘다.
 */
class RegPortFactory(pauseFactory: PauseFactory) {
  def apply(port: RegPort, valid: Bool, addr: UInt): UInt = {
    val received = RegInit(false.B)
    val buf      = RegInit(0.U(64.W))

    val plz = !received && valid

    port.addr := addr
    port.req  := plz
    pauseFactory.include(plz)

    when(!valid) {
      received := false.B
      buf      := 0.U
    }.elsewhen(plz && port.ack) {
      buf      := port.data
      received := true.B
    }

    buf
  }
}
