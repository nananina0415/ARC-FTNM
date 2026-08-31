/*
 * GETA $X,@+4*YZ[-262144] — 상대주소를 절대주소로 변환 (get address)
 *   λ + 4·YZ (또는 백워드 버전 GETAB은 λ + 4·(YZ - 2^16))를 $X에 저장.
 *   분기 명령과 동일한 상대주소 문법(@ 기호, 어셈블러가 YZ 계산)을 따른다.
 *
 * PUT X,$Z|Z — 특수 레지스터에 쓰기
 *   코드번호 X인 특수 레지스터에 $Z(또는 즉치 Z)를 저장.
 *   쓰기가 금지된 레지스터(rN/rO/rS 등)나 항상 0이어야 하는 비트에 쓰려 하면
 *   illegal instruction 인터럽트 발생.
 *
 * GET $X,Z — 특수 레지스터 읽기
 *   코드번호 Z인 특수 레지스터의 값을 $X에 저장. 모든 특수 레지스터는 읽기 가능
 *   (비밀로 감춰지는 레지스터는 없음). Z≥32이면 illegal instruction 인터럽트 발생.
 *
 * SYNC XYZ — 동기화
 *   XYZ=0: 파이프라인을 비움(앞선 모든 명령이 끝날 때까지 정지).
 *   XYZ=1: 이 SYNC보다 앞선 모든 저장 명령이 뒤따르는 명령보다 먼저 끝나도록 보장.
 *   XYZ=2: 이 SYNC보다 앞선 모든 적재 명령이 뒤따르는 적재보다 먼저 끝나도록 보장.
 *   XYZ=3: 이 SYNC보다 앞선 모든 적재/저장이 뒤따르는 적재/저장보다 먼저 끝나도록 보장.
 *   XYZ=4~7은 캐시/절전모드 제어용 특권 명령 — 일반 사용자가 실행하면 illegal
 *   instruction 인터럽트. XYZ>7이면 무조건 illegal instruction.
 *
 * SWYM X,Y,Z — "sympathize with your machinery", 아무 동작도 하지 않음 (no-op)
 *   완전한 no-op 명령. 슈퍼스칼라/파이프라인 스케줄링 힌트나 디버거와의 통신 등에
 *   사용될 수 있다.
*/

package cpu

import chisel3._
import chisel3.util._

case class SpecialOp() extends Bundle {
  val flag = UInt(4.W)
  val xyz  = UInt(24.W)
}

case class SpecialResult() extends Bundle {
  val dest   = UInt(8.W)
  val res    = UInt(64.W)
  val writeX = Bool()  // GET/GETA(GETAB 포함)일 때만 true — 커미터가 이때만 $X에 res를 씀. PUT/SWYM은 false.
}

/** class Special의 배선 로직을 함수 단위로 뺀 것 — 파이프 단계를 나누는 게 아니라 그냥
 * 클래스 본문을 짧게 유지하려는 목적. 전부 io/pauseWire 등을 인자로 받는 순수 함수라
 * class Special 인스턴스 상태에 기대지 않는다.
 */
object Special {
  // 전역 코드번호(REG_CODE_*, GET/PUT의 X/Z 필드가 실제로 쓰는 값) → 그룹 안에서만
  // 통하는 로컬 번호(그룹 포트의 addr) 변환 테이블. Reg.scala의 그룹 포트 주석에 적힌
  // 배정과 정확히 일치해야 한다.
  val divMapping   = Seq(REG_CODE_D -> 0, REG_CODE_R -> 1)
  val jumpMapping  = Seq(
    REG_CODE_J -> 0, REG_CODE_B -> 1, REG_CODE_BB -> 2, REG_CODE_K -> 3, REG_CODE_Q -> 4,
    REG_CODE_T -> 5, REG_CODE_TT -> 6, REG_CODE_W -> 7, REG_CODE_WW -> 8, REG_CODE_X -> 9,
    REG_CODE_XX -> 10, REG_CODE_Y -> 11, REG_CODE_YY -> 12, REG_CODE_Z -> 13, REG_CODE_ZZ -> 14
  )
  val memReadMapping  = Seq(REG_CODE_P -> 0, REG_CODE_G -> 1, REG_CODE_L -> 2, REG_CODE_O -> 4, REG_CODE_S -> 5)
  val memWriteMapping = Seq(REG_CODE_P -> 0, REG_CODE_G -> 1, REG_CODE_L -> 2)  // rO/rS는 쓰기 금지라 제외
  val elseReadMapping  = Seq(
    REG_CODE_E -> 0, REG_CODE_F -> 1, REG_CODE_I -> 2, REG_CODE_U -> 3, REG_CODE_V -> 4,
    REG_CODE_C -> 8, REG_CODE_N -> 9
  )
  val elseWriteMapping = Seq(REG_CODE_E -> 0, REG_CODE_F -> 1, REG_CODE_I -> 2, REG_CODE_U -> 3, REG_CODE_V -> 4)  // rC/rN 제외

  /** target이 mapping에 있는 코드 중 하나인지. */
  def inGroup(target: UInt, mapping: Seq[(UInt, Int)]): Bool =
    mapping.map { case (code, _) => target === code }.reduce(_ || _)

  /** target(전역 코드)을 그룹 로컬 번호로 변환 — mapping에 없으면 0. */
  def groupAddr(target: UInt, mapping: Seq[(UInt, Int)], width: Int): UInt =
    MuxLookup(target, 0.U(width.W))(mapping.map { case (code, local) => code -> local.U(width.W) })

  /** GETA $X,@+4*YZ : λ + 4·YZ. GETAB(백워드)은 λ + 4·(YZ - 2^16) — U뺄셈은 mod 2^64라
   * 두 경우 모두 그냥 오프셋을 더하고 백워드일 때만 262144(=4·2^16)를 추가로 뺀다.
   */
  def computeGetA(pc: UInt, isBack: Bool, yz16: UInt): UInt = {
    val fwdOffset = (yz16 << 2).pad(64)
    Mux(isBack, pc + fwdOffset - 262144.U(64.W), pc + fwdOffset)
  }

  /** PUT의 $Z(비즉치) 오퍼랜드를 레지스터버스에 요청해서 받아온다 — 즉치면 z를 그대로 쓴다. */
  def wirePutOperand(reg: RegBus, regPort: RegReadPort, z: UInt, isPut: Bool, zImm: Bool): UInt = {
    regPort.x.addr := 0.U
    regPort.x.set  := false.B
    regPort.y.addr := 0.U
    regPort.y.set  := false.B
    regPort.z.addr := z             // PUT이 레지스터 오퍼랜드일 때만 의미 있음
    regPort.z.set  := isPut && !zImm
    Mux(zImm, z.pad(64), reg.z.data)
  }

  /** GET — 그룹 7개 각각에 요청-대기 포트를 만들고 z가 속한 그룹 하나만 무장한다.
   * (데이터, 7개 그룹 ack를 다 모은 allAck)를 돌려준다.
   */
  def wireGet(
    z: UInt, isGet: Bool,
    divR: SpecialRegPort_Div_R, mulR: SpecialRegPort_Mul_R, aR: SpecialRegPort_A_R,
    jumpR: SpecialRegPort_Jump_R, memR: SpecialRegPort_Mem_R, maskR: SpecialRegPort_Mask_R,
    elseR: SpecialRegPort_else_R,
    pauseWire: Bool, specialRegReadPortFactory: SpecialRegReadPortFactory
  ): (UInt, Bool) = {
    val divPort  = specialRegReadPortFactory(divR.ack,  pauseWire)
    val mulPort  = specialRegReadPortFactory(mulR.ack,  pauseWire)
    val aPort    = specialRegReadPortFactory(aR.ack,    pauseWire)
    val jumpPort = specialRegReadPortFactory(jumpR.ack, pauseWire)
    val memPort  = specialRegReadPortFactory(memR.ack,  pauseWire)
    val maskPort = specialRegReadPortFactory(maskR.ack, pauseWire)
    val elsePort = specialRegReadPortFactory(elseR.ack, pauseWire)

    divR.req  := divPort.req
    mulR.req  := mulPort.req
    aR.req    := aPort.req
    jumpR.req := jumpPort.req
    memR.req  := memPort.req
    maskR.req := maskPort.req
    elseR.req := elsePort.req

    divPort.set_=(isGet && inGroup(z, divMapping))
    mulPort.set_=(isGet && (z === REG_CODE_H))
    aPort.set_=(isGet && (z === REG_CODE_A))
    jumpPort.set_=(isGet && inGroup(z, jumpMapping))
    memPort.set_=(isGet && inGroup(z, memReadMapping))
    maskPort.set_=(isGet && (z === REG_CODE_M))
    elsePort.set_=(isGet && inGroup(z, elseReadMapping))

    divR.addr  := groupAddr(z, divMapping, 1)
    jumpR.addr := groupAddr(z, jumpMapping, 4)
    memR.addr  := groupAddr(z, memReadMapping, 3)
    elseR.addr := groupAddr(z, elseReadMapping, 4)

    // 7개 중 z가 속한 그룹 하나만 무장되므로, 무장 안 된 나머지는 항상 ack=true라
    // 이 AND는 실질적으로 "그 하나가 응답했는가"와 같다(Div의 rDPort와 같은 원리).
    val allAck = Seq(divPort, mulPort, aPort, jumpPort, memPort, maskPort, elsePort).map(_.ack).reduce(_ && _)

    // z가 속한 그룹의 데이터를 고른다 — 어느 그룹에도 없으면(코드번호 32 이상 등) 0.
    val data = MuxCase(0.U(64.W), Seq(
      inGroup(z, divMapping)      -> divR.data,
      (z === REG_CODE_H)          -> mulR.data,
      (z === REG_CODE_A)          -> aR.data,
      inGroup(z, jumpMapping)     -> jumpR.data,
      inGroup(z, memReadMapping)  -> memR.data,
      (z === REG_CODE_M)          -> maskR.data,
      inGroup(z, elseReadMapping) -> elseR.data
    ))

    (data, allAck)
  }

  /** PUT — $Z(레지스터) 또는 즉치 Z를 x가 가리키는 특수레지스터에 쓴다. rC/rN/rO/rS는
   * 쓰기용 로컬 번호 매핑 자체에 없어서(폭이 좁은 write addr으론 표현도 안 됨) 여기서
   * 쓸 수조차 없다.
   */
  def wirePut(
    x: UInt, putVal: UInt, isPut: Bool, pauseWire: Bool,
    divW: SpecialRegPort_Div_W, mulW: SpecialRegPort_Mul_W, aW: SpecialRegPort_A_W,
    jumpW: SpecialRegPort_Jump_W, memW: SpecialRegPort_Mem_W, maskW: SpecialRegPort_Mask_W,
    elseW: SpecialRegPort_else_W
  ): Unit = {
    // 외부 pause뿐 아니라 $Z 버스 요청이 아직 안 끝난 상태(regPort.ack=false)도 같이
    // 막아야 한다 — 안 그러면 레지스터 오퍼랜드 PUT이 데이터 도착 전에 먼저 write부터
    // 나가버린다. Const의 acc CompoReg가 pauseWire로 게이팅되는 것과 같은 이유.
    val putWrite = isPut && !pauseWire

    divW.addr   := groupAddr(x, divMapping, 1)
    divW.data   := putVal
    divW.write  := putWrite && inGroup(x, divMapping)

    mulW.data   := putVal
    mulW.write  := putWrite && (x === REG_CODE_H)

    aW.data     := putVal
    aW.write    := putWrite && (x === REG_CODE_A)

    jumpW.addr  := groupAddr(x, jumpMapping, 4)
    jumpW.data  := putVal
    jumpW.write := putWrite && inGroup(x, jumpMapping)

    memW.addr   := groupAddr(x, memWriteMapping, 2)
    memW.data   := putVal
    memW.write  := putWrite && inGroup(x, memWriteMapping)

    maskW.data  := putVal
    maskW.write := putWrite && (x === REG_CODE_M)

    elseW.addr  := groupAddr(x, elseWriteMapping, 3)
    elseW.data  := putVal
    elseW.write := putWrite && inGroup(x, elseWriteMapping)
  }
}

class Special(
  regReadPortFactory: RegReadPortFactory,
  specialRegReadPortFactory: SpecialRegReadPortFactory
) extends Module {
  val io = IO(new Bundle {
    val op    = Input(SpecialOp())
    val pause = Input(Bool())

    val reg   = new RegBus  // PUT의 $Z(비즉치) 오퍼랜드용
    val pc    = ProgramCounterReadPort()  // GETA/GETAB의 λ

    // 그룹 포트(Reg.scala의 "특수 레지스터 — 그룹 포트" 절 참고)
    val divR  = SpecialRegPort_Div_R();  val divW  = SpecialRegPort_Div_W()
    val mulR  = SpecialRegPort_Mul_R();  val mulW  = SpecialRegPort_Mul_W()
    val aR    = SpecialRegPort_A_R();    val aW    = SpecialRegPort_A_W()
    val jumpR = SpecialRegPort_Jump_R(); val jumpW = SpecialRegPort_Jump_W()
    val memR  = SpecialRegPort_Mem_R();  val memW  = SpecialRegPort_Mem_W()
    val maskR = SpecialRegPort_Mask_R(); val maskW = SpecialRegPort_Mask_W()
    val elseR = SpecialRegPort_else_R(); val elseW = SpecialRegPort_else_W()

    val result = Output(SpecialResult())
  })

  private val x = io.op.xyz(23, 16)
  private val z = io.op.xyz(7, 0)

  private val isGet  = io.op.flag === GET_REG
  private val isPut  = io.op.flag(2) && io.op.flag(1)  // SET_REG/SET_REG|IMM(PUT/PUTI) 공통
  private val zImm   = io.op.flag(0)                    // PUT에서만 의미 있음(PUTI일 때 1)
  private val isGetA = !io.op.flag(2) && io.op.flag(1)  // REL_ADDR/REL_ADDR|BACK(GETA/GETAB) 공통
  private val isBack = io.op.flag(0)                    // GETA에서만 의미 있음(GETAB일 때 1)
  private val yz16   = io.op.xyz(15, 0)

  val pauseBox = new PauseBox(3)
  pauseBox.reasons(0) := io.pause

  val regPort = regReadPortFactory(io.reg, pauseBox.pause)

  private val getaTarget = Special.computeGetA(io.pc.data, isBack, yz16)
  private val putVal     = Special.wirePutOperand(io.reg, regPort, z, isPut, zImm)
  private val (getData, allAck) = Special.wireGet(
    z, isGet,
    io.divR, io.mulR, io.aR, io.jumpR, io.memR, io.maskR, io.elseR,
    pauseBox.pause, specialRegReadPortFactory
  )

  pauseBox.reasons(1) := !regPort.ack
  pauseBox.reasons(2) := !allAck

  Special.wirePut(
    x, putVal, isPut, pauseBox.pause,
    io.divW, io.mulW, io.aW, io.jumpW, io.memW, io.maskW, io.elseW
  )

  io.result.dest   := x
  io.result.res    := Mux(isGet, getData, getaTarget)  // PUT/SWYM은 writeX=false라 res는 안 쓰임
  io.result.writeX := isGet || isGetA
}
