/*
 * TRAP X,Y,Z — 트랩
 *   OS가 처리하는 인터럽트를 강제로 발생시킨다. 인터럽트 마스크 rK를 0으로 지워
 *   인터럽트를 막고, rT(트랩 주소, 항상 음수)로 제어를 넘긴다.
 *   XYZ=0이면 사용자 프로세스 종료 요청, XYZ=1이면 핸들러가 없는 인터럽트에 대한
 *   기본 동작을 의미하도록 MMIX 아키텍처가 규정. mmix-sim 등 시뮬레이터에서는
 *   X=0에 Y로 Fopen 등 파일 I/O 트랩 번호를 구분해서 쓴다.
 *
 * GO $X,$Y,$Z|Z — 지정 위치로 이동
 *   $Y+$Z(또는 $Y+Z)에서 다음 명령을 가져와 실행을 계속한다.
 *   원래 다음에 실행됐을 위치(λ+4)를 $X에 저장.
 *   JMP와 달리 상대주소가 아니라 절대주소다.
 *
 * JMP @+4*XYZ[-67108864] — 무조건 점프
 *   X, Y, Z를 부호없는 24비트 정수로 보고, 현재 위치(λ) 기준 상대주소로 이동.
 *
 * PUSHJ $X,@+4*YZ[-262144] — 레지스터 push 후 점프
 *   여기서 $X는 레지스터가 아니라 즉치값(0~255) — 이하 n으로 표기. 
 *   로컬 레지스터 n개($0~$n)를 레지스터 스택에 push하고 
 *   상대주소(λ+4·YZ, PUSHJB는λ+4·(YZ-2^16))의 서브루틴으로 점프. 
 *   복귀 주소는 rJ(return-jump)에 저장.
 *   push 후 레지스터 $n에는 push된 개수 n이 저장된다(POP이 되돌릴 개수를 알 수 있게).
 *
 * PUSHGO $X,$Y,$Z|Z — 레지스터 push 후 GO
 *   PUSHJ와 동일하게 레지스터를 push하지만, 점프 목적지는 GO와 같은
 *   레지스터+오프셋 방식(절대주소)으로 지정.
 *
 * POP X,YZ — 서브루틴에서 복귀
 *   여기서도 X는 즉치값(0~255) — 이하 n으로 표기. 현재 로컬 레지스터 중 n개를
 *   보존하며 가장 최근 PUSHJ/PUSHGO를 되돌리고, M4[4·YZ + rJ]에 있는 명령으로 점프.
 *
 * RESUME X,Y,Z — 인터럽트/트립 처리 후 재개
 *   인터럽트나 트립 핸들러가 처리를 마친 뒤 원래 실행을 재개시킨다.
 *   X=0, Y=0 이어야 한다.
 *   Z=0이면 rW/rX/rY/rZ(트립용) 정보로 재개, Z=1이면 rWW/rXX/rYY/rZZ(트랩용)
 *   정보로 재개.
 *
 * TRIP X,Y,Z — 트립
 *   사용자가 정의한 인터럽트를 강제로 발생시킨다. TRAP과 형식은 같지만
 *   OS가 아니라 사용자 프로그램이 직접 핸들러를 정의해 처리한다는 점이 다르다.
*/

package cpu

import chisel3._
import chisel3.util._

// JMP는 XYZ 24비트, PUSHJ는 X+YZ, PUSHGO/GO/TRAP/TRIP은 X+Y+Z
// 파이프에서 flag에 따라 xyz를 분리해서 사용
case class JumpOp() extends Bundle {
  val flag = UInt(4.W)
  val xyz  = UInt(24.W)
}

/** Branch와 같은 계약 — 커미터가 PC에 반영한다. 00=저장 안 함(이 5개 명령에선 안 씀),
 * 01=절대주소 그대로 저장, 10=λ+offset으로 저장.
 */
case class JumpResult() extends Bundle {
  val storeMethod = UInt(2.W)
  val offset      = UInt(64.W)

  // 9비트 return_addr_reg 인코딩(설계/파이프/점프 상단 표) — λ+4를 어디에 쓸지 지정.
  // 값(λ+4) 자체는 커미터가 채운다 — 이 파이프는 λ를 모른다.
  //   0_0000_0001 = none         (아무 데도 안 씀)
  //   0_0000_0010 = rJ           (PUSHJ/PUSHGO)
  //   0_0000_0100 = rW           (TRIP)
  //   0_0000_1000 = rWW          (TRAP)
  //   1_xxxx_xxxx = 즉치 레지스터 번호(그 번호 레지스터에 씀, GO)
  val returnAddrReg = UInt(9.W)

  val stackOp = UInt(2.W)  // 00=없음, 01=push, 10=pop
  val stackN  = UInt(8.W)  // push/pop할 개수(즉치 n) — 실제 push/pop 메커니즘은 커미터 몫

  val write255 = Bool()        // true면 $255 ← value255
  val value255 = UInt(64.W)    // TRAP/TRIP: rJ 이전값. RESUME(Z=1): rBB(뱅크 read 결과)

  // 트립뱅크/트랩뱅크 쓰기(TRAP/TRIP 전용) — 이 파이프는 λ를 몰라서 W/WW 자리를 못 채우니
  // 그 자리는 returnAddrReg(RETURN_RW/RETURN_RWW)로 커미터에게 지시하고, 나머지 4워드만
  // 여기 싣는다. 실제 TrapBankPort 연결은 커미터 몫 — 이 파이프는 직접 안 몬다.
  val bankActive = Bool()
  val bankSel    = Bool()              // false=트립뱅크, true=트랩뱅크
  val bankData   = Vec(4, UInt(64.W))  // [X워드, $Y, $Z, $255이전값]

  val writeRK = Bool()        // true면 커미터가 rK←rkValue
  val rkValue = UInt(64.W)    // TRAP: 0 고정. RESUME(Z=1): 이전 $255 값

  val illegal = Bool()        // RESUME: Z>1 또는 rX(rXX)>=0(ropcode 미구현이라 illegal 처리)
}

/** class Jump의 배선 로직을 함수 단위로 뺀 것 — Special.scala와 같은 패턴. 전부 io 등을
 * 인자로 받는 순수 함수라 class Jump 인스턴스 상태에 기대지 않는다.
 */
object Jump {
  private val NEG_67108864 = ((BigInt(1) << 64) - 67108864).U(64.W)  // -67108864의 2의 보수
  private val NEG_262144   = ((BigInt(1) << 64) - 262144).U(64.W)    // -262144의 2의 보수

  // return_addr_reg 9비트 인코딩 (설계/파이프/점프 상단 표)
  val RETURN_NONE = "b0_0000_0001".U(9.W)
  val RETURN_RJ   = "b0_0000_0010".U(9.W)
  val RETURN_RW   = "b0_0000_0100".U(9.W)
  val RETURN_RWW  = "b0_0000_1000".U(9.W)

  /** 즉치 레지스터 번호 x를 return_addr_reg 인코딩으로 변환 — 최상위 비트=1, 나머지 8비트=x. */
  def returnToReg(x: UInt): UInt = Cat(1.U(1.W), x)

  /** TRAP/TRIP/RESUME 전용 필드를 전부 "이 명령은 안 씀"으로 채운다 — JMP/GO/PUSHJ/PUSHGO/POP이 쓴다. */
  def fillNoop(res: JumpResult): Unit = {
    res.write255   := false.B
    res.value255   := 0.U
    res.bankActive := false.B
    res.bankSel    := false.B
    res.bankData   := VecInit(Seq.fill(4)(0.U(64.W)))
    res.writeRK    := false.B
    res.rkValue    := 0.U
    res.illegal    := false.B
  }

  /** JMP @+4*XYZ[-67108864] — 무조건 분기라 storeMethod는 항상 10. back(flag(0))이면
   * XYZ·4에서 67108864(=4·2^24)를 뺀다.
   */
  def jmp(back: Bool, xyz: UInt): JumpResult = {
    val fwd = (xyz << 2).pad(64)

    val backAdder = Module(new SimpleAdder64())
    backAdder.io.a := fwd
    backAdder.io.b := NEG_67108864

    val res = Wire(JumpResult())
    res.storeMethod   := 2.U
    res.offset        := Mux(back, backAdder.io.res, fwd)
    res.returnAddrReg := RETURN_NONE
    res.stackOp       := 0.U
    res.stackN        := 0.U
    fillNoop(res)
    res
  }

  /** GO $X,$Y,$Z|Z — $Y+$Z(또는 $Y+Z)로 절대주소 이동, $X에 λ+4를 저장(값은 커미터가 채움). */
  def go(x: UInt, y64: UInt, z64: UInt): JumpResult = {
    val adder = Module(new SimpleAdder64())
    adder.io.a := y64
    adder.io.b := z64

    val res = Wire(JumpResult())
    res.storeMethod   := 1.U
    res.offset        := adder.io.res
    res.returnAddrReg := returnToReg(x)
    res.stackOp       := 0.U
    res.stackN        := 0.U
    fillNoop(res)
    res
  }

  /** PUSHJ $X,@+4*YZ[-262144] — X는 즉치 n(push할 개수). rJ←λ+4(커미터가 채움),
   * λ+YZ·4(±back)의 서브루틴으로 점프. 실제 push는 stackOp/stackN만 넘기고 커미터 몫.
   */
  def pushj(back: Bool, n: UInt, yz: UInt): JumpResult = {
    val fwd = (yz << 2).pad(64)

    val backAdder = Module(new SimpleAdder64())
    backAdder.io.a := fwd
    backAdder.io.b := NEG_262144

    val res = Wire(JumpResult())
    res.storeMethod   := 2.U
    res.offset        := Mux(back, backAdder.io.res, fwd)
    res.returnAddrReg := RETURN_RJ
    res.stackOp       := 1.U
    res.stackN        := n
    fillNoop(res)
    res
  }

  /** PUSHGO $X,$Y,$Z|Z — X는 즉치 n(push할 개수). rJ←λ+4, $Y+$Z(절대주소)로 점프. */
  def pushgo(n: UInt, y64: UInt, z64: UInt): JumpResult = {
    val adder = Module(new SimpleAdder64())
    adder.io.a := y64
    adder.io.b := z64

    val res = Wire(JumpResult())
    res.storeMethod   := 1.U
    res.offset        := adder.io.res
    res.returnAddrReg := RETURN_RJ
    res.stackOp       := 1.U
    res.stackN        := n
    fillNoop(res)
    res
  }

  /** POP X,YZ — X는 즉치 n(출력으로 보존할 개수). rJ+YZ·4(절대주소)로 점프. 실제 되돌리는
   * 개수는 push 때 hole에 적힌 값이라 n과 다를 수 있음 — 그 계산은 커미터 몫.
   */
  def pop(n: UInt, yz: UInt, rJ: UInt): JumpResult = {
    val adder = Module(new SimpleAdder64())
    adder.io.a := rJ
    adder.io.b := (yz << 2).pad(64)

    val res = Wire(JumpResult())
    res.storeMethod   := 1.U
    res.offset        := adder.io.res
    res.returnAddrReg := RETURN_NONE
    res.stackOp       := 2.U
    res.stackN        := n
    fillNoop(res)
    res
  }

  private val TRAP_OPCODE = "h00".U(8.W)  // Decoder.scala 확인됨
  private val TRIP_OPCODE = "hFF".U(8.W)

  /** TRAP/TRIP 공통 로직 — opcode/뱅크선택/rK클리어여부/목적지/return_addr_reg만 다르다.
   * §35-36: rX(rXX) ← #80000000(좌측) + 이 명령 인코딩(우측), rY(rYY)←$Y, rZ(rZZ)←$Z,
   * rB(rBB)←$255(이전값), $255←rJ(이전값). rW(rWW)←λ+4는 이 파이프가 모르는 값이라
   * bankData에 안 싣고 returnAddrReg로 커미터에게 지시한다.
   */
  private def trapOrTrip(
    opcode: UInt, bankSelTrap: Bool, clearK: Bool, target: UInt, retReg: UInt,
    x8: UInt, y8: UInt, z8: UInt, y64: UInt, z64: UInt, old255: UInt, oldRJ: UInt
  ): JumpResult = {
    val encoding = Cat(opcode, x8, y8, z8)             // 32비트: opcode:8,X:8,Y:8,Z:8
    val xWord    = Cat("h80000000".U(32.W), encoding)  // 좌측 #80000000 + 우측 인코딩

    val res = Wire(JumpResult())
    res.storeMethod   := 1.U
    res.offset        := target
    res.returnAddrReg := retReg
    res.stackOp       := 0.U
    res.stackN        := 0.U
    res.write255      := true.B
    res.value255      := oldRJ
    res.bankActive    := true.B
    res.bankSel       := bankSelTrap
    res.bankData      := VecInit(xWord, y64, z64, old255)
    res.writeRK       := clearK
    res.rkValue       := 0.U
    res.illegal       := false.B
    res
  }

  /** TRAP X,Y,Z — 목적지는 rT(파이프가 읽어옴), rK←0, return_addr_reg=rWW. */
  def trap(x8: UInt, y8: UInt, z8: UInt, y64: UInt, z64: UInt, old255: UInt, oldRJ: UInt, rT: UInt): JumpResult =
    trapOrTrip(TRAP_OPCODE, true.B, true.B, rT, RETURN_RWW, x8, y8, z8, y64, z64, old255, oldRJ)

  /** TRIP X,Y,Z — 목적지는 가상주소 0(고정), rK는 안 건드림, return_addr_reg=rW. */
  def trip(x8: UInt, y8: UInt, z8: UInt, y64: UInt, z64: UInt, old255: UInt, oldRJ: UInt): JumpResult =
    trapOrTrip(TRIP_OPCODE, false.B, false.B, 0.U, RETURN_RW, x8, y8, z8, y64, z64, old255, oldRJ)

  /** RESUME X,Y,Z — Z(0/1)로 뱅크를 골라 5워드를 한 번에 읽는다(bankWord0=rX/rXX,
   * bankWord3=rB/rBB, bankWord4=rW/rWW — 트립뱅크/트랩뱅크 접근경로 설계 참고).
   * ropcode(rX≥0)는 구현범위 밖이라 illegal로 처리(사용자 확정). Z>1도 illegal.
   */
  def resume(z: UInt, bankWord0: UInt, bankWord3: UInt, bankWord4: UInt, old255: UInt): JumpResult = {
    val illegalZ = z > 1.U
    val isZ1     = !illegalZ && z === 1.U
    val illegal  = illegalZ || !bankWord0(63)  // rX(rXX)가 음수가 아니면 ropcode 필요 — 미구현

    val res = Wire(JumpResult())
    res.storeMethod   := Mux(illegal, 0.U, 1.U)
    res.offset        := bankWord4
    res.returnAddrReg := RETURN_NONE
    res.stackOp       := 0.U
    res.stackN        := 0.U
    res.write255      := isZ1
    res.value255      := bankWord3
    res.bankActive    := false.B
    res.bankSel       := false.B
    res.bankData      := VecInit(Seq.fill(4)(0.U(64.W)))
    res.writeRK       := isZ1
    res.rkValue       := old255
    res.illegal       := illegal
    res
  }
}

/** 인출 단계가 연산 단계로 넘기는 값. 각 명령이 실제로 쓰는 것만 다르고 나머지는 그냥
 * 같이 실어 나른다(Bitwise/Shift의 FetchResult 스타일과 동일).
 */
case class JumpFetchResult() extends Bundle {
  val flag = UInt(4.W)
  val x8   = UInt(8.W)   // GO의 dest, PUSHJ/PUSHGO/POP의 n, TRAP/TRIP의 X(즉치, 같은 비트자리)
  val y8   = UInt(8.W)   // TRAP/TRIP의 Y(즉치, 인코딩용)
  val z8   = UInt(8.W)   // TRAP/TRIP의 Z(즉치, 인코딩용), RESUME의 Z(0/1)
  val yz16 = UInt(16.W)  // PUSHJ/POP의 YZ
  val xyz  = UInt(24.W)  // JMP의 XYZ
  val back = Bool()      // JMP/PUSHJ 계열의 back
  val y64  = UInt(64.W)  // GO/PUSHGO/TRAP/TRIP의 $Y
  val z64  = UInt(64.W)  // GO/PUSHGO의 $Z(또는 즉치), TRAP/TRIP의 $Z
  val rJ64 = UInt(64.W)  // POP/TRAP/TRIP이 읽어온 rJ
  val old255 = UInt(64.W)          // TRAP/TRIP/RESUME이 읽어온 $255
  val rT64   = UInt(64.W)          // TRAP이 읽어온 rT(목적지)
  val bankRead = Vec(5, UInt(64.W))  // RESUME이 읽어온 뱅크 5워드
}

/** 인출 단계 — flag에 따라 필요한 것만 무장한다: GO/PUSHGO/TRAP/TRIP은 레지스터버스로
 * $Y,$Z(+TRAP/TRIP은 $255도), POP/TRAP/TRIP은 Jump 그룹 전용 포트로 rJ, TRAP만 추가로
 * rT, RESUME은 트립뱅크/트랩뱅크를 통째로 읽는다. JMP/PUSHJ는 즉치만으로 계산돼서
 * 아무것도 무장 안 한다.
 */
class JumpFetch(
  flag: UInt, xyz: UInt, bus: RegBus, regPort: RegReadPort,
  jumpR: SpecialRegPort_Jump_R, jumpRPort: SpecialRegReadPort,
  jumpR2: SpecialRegPort_Jump_R, jumpR2Port: SpecialRegReadPort,
  trapBank: TrapBankPort
) {
  private val back = flag(0)  // JMP/PUSHJ 계열
  private val zImm = flag(0)  // GO/PUSHGO 계열 — 같은 비트지만 명령 계열마다 뜻이 다름
  private val x8   = xyz(23, 16)
  private val y8   = xyz(15, 8)
  private val z8   = xyz(7, 0)
  private val yz16 = xyz(15, 0)

  private val isGoLike  = flag === 0x8.U || flag === 0x9.U || flag === 0xC.U || flag === 0xD.U  // GO/GOI/PUSHGO/PUSHGOI
  private val isPop     = flag === 0x4.U
  private val isTrap    = flag === 0x0.U
  private val isTrip    = flag === 0x2.U
  private val isResume  = flag === 0x6.U
  private val needsReg  = isGoLike || isTrap || isTrip  // $Y,$Z를 레지스터버스로 읽어야 하는 것들

  regPort.x.addr := 255.U
  regPort.x.set  := isTrap || isTrip || isResume  // $255 — TRAP/TRIP은 rB(rBB) 대상, RESUME(Z=1)은 rkValue 소스
  regPort.y.addr := y8
  regPort.y.set  := needsReg  // GO/PUSHGO/TRAP/TRIP은 $Y가 항상 레지스터(즉치 아님)
  regPort.z.addr := z8
  regPort.z.set  := needsReg && (isTrap || isTrip || !zImm)  // TRAP/TRIP의 $Z는 항상 레지스터

  jumpRPort.set_=(isPop || isTrap || isTrip)  // rJ — POP은 목적지 계산용, TRAP/TRIP은 $255 백업용
  jumpR2Port.set_=(isTrap)                    // rT — TRAP의 목적지, rJ와 동시에 필요해서 별도 포트

  trapBank.active := isResume
  trapBank.bank   := z8(0)  // RESUME의 Z(0/1) — 트립뱅크/트랩뱅크 선택
  trapBank.write  := false.B  // 이 파이프는 뱅크를 읽기만 한다(쓰기는 커미터 몫)
  trapBank.wdata  := VecInit(Seq.fill(5)(0.U(64.W)))  // 안 씀

  val res = Wire(JumpFetchResult())
  res.flag   := flag
  res.x8     := x8
  res.y8     := y8
  res.z8     := z8
  res.yz16   := yz16
  res.xyz    := xyz
  res.back   := back
  res.y64    := bus.y.data
  res.z64    := Mux(zImm && !(isTrap || isTrip), z8.pad(64), bus.z.data)
  res.rJ64   := jumpR.data
  res.old255 := bus.x.data
  res.rT64   := jumpR2.data
  res.bankRead := trapBank.rdata
}

/** 연산 단계 — flag로 명령 결과 중 하나를 고른다. */
class JumpExec(f: JumpFetchResult) {
  private val goRes     = Jump.go(f.x8, f.y64, f.z64)
  private val jmpRes    = Jump.jmp(f.back, f.xyz)
  private val pushgoRes = Jump.pushgo(f.x8, f.y64, f.z64)
  private val pushjRes  = Jump.pushj(f.back, f.x8, f.yz16)
  private val popRes    = Jump.pop(f.x8, f.yz16, f.rJ64)
  private val trapRes   = Jump.trap(f.x8, f.y8, f.z8, f.y64, f.z64, f.old255, f.rJ64, f.rT64)
  private val tripRes   = Jump.trip(f.x8, f.y8, f.z8, f.y64, f.z64, f.old255, f.rJ64)
  private val resumeRes = Jump.resume(f.z8, f.bankRead(0), f.bankRead(3), f.bankRead(4), f.old255)

  val res = MuxLookup(f.flag, jmpRes)(Seq(
    0x0.U -> trapRes,
    0x2.U -> tripRes,
    0x4.U -> popRes,
    0x6.U -> resumeRes,
    0x8.U -> goRes,     0x9.U -> goRes,
    0xA.U -> jmpRes,    0xB.U -> jmpRes,
    0xC.U -> pushgoRes, 0xD.U -> pushgoRes,
    0xE.U -> pushjRes,  0xF.U -> pushjRes
  ))
}

class Jump(regReadPortFactory: RegReadPortFactory, specialRegReadPortFactory: SpecialRegReadPortFactory) extends Module {
  val io = IO(new Bundle {
    val op     = Input(JumpOp())
    val pause  = Input(Bool())

    val reg    = new RegBus               // GO/PUSHGO/TRAP/TRIP의 $Y,$Z,$255
    val jumpR  = SpecialRegPort_Jump_R()  // POP/TRAP/TRIP의 rJ — Jump 그룹 전용 읽기 포트
    val jumpR2 = SpecialRegPort_Jump_R()  // TRAP의 rT 전용 — jumpR(rJ)과 동시에 필요해서 별도 포트
    val trapBank = TrapBankPort()         // RESUME의 트립뱅크/트랩뱅크 읽기 — 파이프가 직접 몲

    val result = Output(JumpResult())
  })

  val pauseBox = new PauseBox(4)
  pauseBox.reasons(0) := io.pause

  val regPort    = regReadPortFactory(io.reg, pauseBox.pause)
  val jumpRPort  = specialRegReadPortFactory(io.jumpR.ack, pauseBox.pause)
  val jumpR2Port = specialRegReadPortFactory(io.jumpR2.ack, pauseBox.pause)
  io.jumpR.req   := jumpRPort.req
  io.jumpR.addr  := 0.U  // Jump 그룹 안 rJ의 로컬번호(jumpWriteMapping 기준 0) — 고정
  io.jumpR2.req  := jumpR2Port.req
  io.jumpR2.addr := 5.U  // Jump 그룹 안 rT의 로컬번호(jumpWriteMapping 기준 5) — 고정, TRAP만 씀
  pauseBox.reasons(1) := !regPort.ack
  pauseBox.reasons(2) := !jumpRPort.ack
  pauseBox.reasons(3) := !jumpR2Port.ack
  // trapBank는 req/ack가 없는(항상 즉시 유효한) 포트라 pause 사유에 안 들어간다.

  val CompoReg = CompoRegFactory(pauseBox.pause)

  val fetch = new JumpFetch(io.op.flag, io.op.xyz, io.reg, regPort, io.jumpR, jumpRPort, io.jumpR2, jumpR2Port, io.trapBank)

  val fetchBuf = CompoReg(gen = JumpFetchResult(), write = regPort.ack && jumpRPort.ack && jumpR2Port.ack, d = fetch.res)

  // ── 연산 단계: flag로 명령 결과 중 하나를 고른다 ──
  io.result := new JumpExec(fetchBuf.q).res
}
