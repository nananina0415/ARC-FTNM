/*
 * BN/BZ/BP/BOD/BNN/BNZ/BNP/BEV $X,@+4*YZ[-262144] — 분기 (8종)
 *   $X가 각각 음수/0/양수/홀수/0아님(nonnegative)/0아님(nonzero)/0또는음수(nonpositive)/짝수
 *   조건을 만족하면 현재 위치(λ) + 4·YZ 로 분기(forward branch).
 *   *B 접미사 버전(BNB/BZB/BPB/BODB/BNNB/BNZB/BNPB/BEVB)은
 *   λ + 4·(YZ - 2^16) 로 분기(backward branch) — 동일 8조건.
 *
 * PBN/PBZ/PBP/PBOD/PBNN/PBNZ/PBNP/PBEV (및 *B 버전) $X,@+4*YZ[-262144]
 *   — 확률적 분기(probable branch, 8종 × forward/backward = 16개)
 *   위와 완전히 동일한 8가지 조건과 분기 동작을 갖지만,
 *   "이 분기는 실제로 자주 일어날 것"이라는 힌트를 CPU에 제공하는 변형.
 *   분기가 자주 일어날 것으로 예상될 때는 PB를, 드물 것으로 예상될 때는 B를 사용 권장.
*/

package cpu

import chisel3._
import chisel3.util._

case class BranchOp() extends Bundle {
  val flag = UInt(5.W)
  val x    = UInt(8.W)   // 테스트할 레지스터 번호
  val yz   = UInt(16.W)  // 16비트 상대주소 오프셋
}

/** 이 파이프는 PC를 직접 건드리지 않는다 — 정확한 순서에서의 λ은 커미터만 알기 때문에,
 * 커미터가 offset을 λ에 더하거나 말거나를 결정한다. 분기가 실제로 일어날지 예측하는
 * 로직(추측 페치)도 디코더 단계(또는 그 이전)의 몫이라 이 파이프는 신경 쓰지 않는다 —
 * 여기서는 그냥 실제 조건 결과만 storeMethod로 알려준다.
 */
case class BranchResult() extends Bundle {
  val storeMethod = UInt(2.W)  // 00=저장 안 함, 10=λ+offset으로 저장 (설계/파이프/분기 참고)
  val offset      = UInt(64.W) // YZ·4 (백워드면 -262144) — PC 자체엔 안 더함
}

/** 인출 단계가 연산 단계로 넘기는 값 — 레지스터버스에서 실제로 받아온 X와, X 없이도
 * 바로 조합적으로 나오는 오프셋 원료 필드들을 같이 묶는다. 버스 대기 중에 io.op가
 * 바뀌어도(다음 명령으로 스케줄러가 넘어가도) X와 이 필드들이 지금 인출 중인 명령 것과
 * 어긋나지 않게 한다.
 */
case class BranchFetchResult() extends Bundle {
  val x       = UInt(64.W)
  val yz      = UInt(16.W)
  val back    = Bool()
  val cond    = UInt(2.W)
  val condInv = Bool()
}

/** 인출 단계 — 레지스터버스에 X를 요청해서 받아온다. */
class BranchFetch(flag: UInt, x: UInt, yz: UInt, bus: RegBus, regPort: RegReadPort) {
  private val condInv = !flag(3)
  private val cond    = flag(2, 1)  // 0=NEG 1=ZERO 2=POS 3=ODD
  private val back    = flag(0)

  regPort.x.addr := x
  regPort.x.set  := true.B
  regPort.y.addr := 0.U
  regPort.y.set  := false.B
  regPort.z.addr := 0.U
  regPort.z.set  := false.B

  val res = Wire(BranchFetchResult())
  res.x       := bus.x.data
  res.yz      := yz
  res.back    := back
  res.cond    := cond
  res.condInv := condInv
}

/** 연산 단계 — 인출이 끝난 X로 실제 조건을 판정하고, YZ/BACK으로 offset을 계산한다.
 * 조건 판정은 Comp.scala의 CondSetter가 CS_/ZS_에 쓰는 것과 같은 로직
 * (Comparator(X,0) + cond + condInv)을 그대로 재사용한다.
 */
class BranchExec(f: BranchFetchResult) {
  private val comparator = Module(new Comparator())
  comparator.io.a     := f.x
  comparator.io.b     := 0.U
  comparator.io.uFlag := false.B

  private val baseSatisfied = MuxLookup(f.cond, f.x(0))(Seq(
    0.U -> (comparator.io.res === -1.S),
    1.U -> (comparator.io.res === 0.S),
    2.U -> (comparator.io.res === 1.S),
    3.U -> f.x(0)
  ))
  private val actualTaken = Mux(f.condInv, !baseSatisfied, baseSatisfied)

  private val fwd    = (f.yz << 2).pad(64)
  private val offset = Mux(f.back, fwd - 262144.U(64.W), fwd)

  val res = Wire(BranchResult())
  res.storeMethod := Mux(actualTaken, 2.U, 0.U)
  res.offset      := offset
}

class Branch(regReadPortFactory: RegReadPortFactory) extends Module {
  val io = IO(new Bundle {
    val op     = Input(BranchOp())
    val pause  = Input(Bool())

    val reg    = new RegBus

    val result = Output(BranchResult())
  })

  val pauseBox = new PauseBox(2)
  pauseBox.reasons(0) := io.pause

  val regPort = regReadPortFactory(io.reg, pauseBox.pause)
  pauseBox.reasons(1) := !regPort.ack

  val CompoReg = CompoRegFactory(pauseBox.pause)

  val fetch = new BranchFetch(io.op.flag, io.op.x, io.op.yz, io.reg, regPort)

  val fetchBuf = CompoReg(gen = BranchFetchResult(), write = regPort.ack, d = fetch.res)

  // ── 연산 단계: 인출이 끝난 X로 실제 조건 판정 + storeMethod 결정 ──
  io.result := new BranchExec(fetchBuf.q).res
}
