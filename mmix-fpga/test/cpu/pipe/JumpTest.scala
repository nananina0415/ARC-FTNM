package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/*
 * Jump 파이프(TRAP/GO/JMP/PUSHJ/PUSHGO/POP/RESUME/TRIP) 전체 명령이 만족해야 할 조건을
 * mmix-doc 기준으로 나열해둔 테스트 케이스 목록. GO/JMP/PUSHJ/PUSHGO/POP은 구현 완료돼서
 * 실제 poke/expect로 채웠다. TRAP/TRIP/RESUME은 아직 미구현이라 pending 유지.
 *
 * PUSHJ/PUSHGO/POP의 실제 레지스터스택 push/pop 메커니즘(L/G 문턱값 계산, hole 처리)은
 * 이 파이프가 하지 않는다 — stackOp/stackN만 커미터(또는 향후 만들 레지스터스택 엔진)에
 * 넘길 뿐이라, 그 메커니즘 자체를 검증하는 케이스는 여기서 테스트할 수 없어 pending으로
 * 남겨둔다.
 */

/** Jump를 감싸서 레지스터버스/rJ 쪽 신호(ack/data)를 평평한 IO로 노출하는 테스트용 하네스.
 * RegArbiter가 아직 목업이라 실제 버스 대신 여기서 직접 ack/data를 준다.
 */
class JumpHarness extends Module {
  val io = IO(new Bundle {
    val op       = Input(JumpOp())
    val pause    = Input(Bool())
    val regXData = Input(UInt(64.W))  // $255(TRAP/TRIP/RESUME)
    val regXAck  = Input(Bool())
    val regYData = Input(UInt(64.W))
    val regYAck  = Input(Bool())
    val regZData = Input(UInt(64.W))
    val regZAck  = Input(Bool())
    val rJData   = Input(UInt(64.W))
    val rJAck    = Input(Bool())
    val rTData   = Input(UInt(64.W))  // TRAP 전용
    val rTAck    = Input(Bool())
    val bankData = Input(Vec(5, UInt(64.W)))  // RESUME이 읽는 트립뱅크/트랩뱅크
    val result   = Output(JumpResult())
  })

  val arbiter       = Module(new RegArbiter)
  val factory       = new RegReadPortFactory(arbiter)
  val specialFactory = new SpecialRegReadPortFactory
  val jump          = Module(new Jump(factory, specialFactory))

  jump.io.op         := io.op
  jump.io.pause      := io.pause
  jump.io.reg.x.data := io.regXData
  jump.io.reg.x.ack  := io.regXAck
  jump.io.reg.y.data := io.regYData
  jump.io.reg.y.ack  := io.regYAck
  jump.io.reg.z.data := io.regZData
  jump.io.reg.z.ack  := io.regZAck
  jump.io.jumpR.data  := io.rJData
  jump.io.jumpR.ack   := io.rJAck
  jump.io.jumpR2.data := io.rTData
  jump.io.jumpR2.ack  := io.rTAck
  jump.io.trapBank.rdata := io.bankData
  io.result := jump.io.result
}

class JumpTest extends AnyFlatSpec with ChiselScalatestTester {

  // Decoder.scala 기준 역산 확인된 flag 값
  val TRAP = 0x0; val TRIP = 0x2; val POP = 0x4; val RESUME = 0x6
  val GO = 0x8; val GOI = 0x9; val JMP = 0xA; val JMPB = 0xB
  val PUSHGO = 0xC; val PUSHGOI = 0xD; val PUSHJ = 0xE; val PUSHJB = 0xF

  val MASK64: BigInt = (BigInt(1) << 64) - 1
  def u64(v: BigInt): UInt = (v & MASK64).U(64.W)

  def setOp(dut: JumpHarness, f: Int, xyz: Int): Unit = {
    dut.io.op.flag.poke(f.U)
    dut.io.op.xyz.poke(xyz.U)
    dut.io.pause.poke(false.B)
  }

  val ZERO_BANK: Seq[BigInt] = Seq.fill(5)(BigInt(0))

  /** 레지스터버스/rJ/rT 왕복을 2단계로 흉내낸다 — 1클럭째는 요청만, 2클럭째에 ack+data
   * 응답. xData($255)/rTData(rT)/bank(트립뱅크·트랩뱅크 5워드)는 TRAP/TRIP/RESUME 전용이라
   * 기본값(0)을 두고 필요할 때만 넘긴다.
   */
  def issue(
    dut: JumpHarness, f: Int, xyz: Int, yData: BigInt, zData: BigInt, rJData: BigInt,
    xData: BigInt = 0, rTData: BigInt = 0, bank: Seq[BigInt] = ZERO_BANK
  ): Unit = {
    dut.io.regXAck.poke(false.B)
    dut.io.regYAck.poke(false.B)
    dut.io.regZAck.poke(false.B)
    dut.io.rJAck.poke(false.B)
    dut.io.rTAck.poke(false.B)
    for (i <- 0 until 5) dut.io.bankData(i).poke(u64(bank(i)))
    setOp(dut, f, xyz)
    dut.clock.step()

    dut.io.regXData.poke(u64(xData))
    dut.io.regYData.poke(u64(yData))
    dut.io.regZData.poke(u64(zData))
    dut.io.rJData.poke(u64(rJData))
    dut.io.rTData.poke(u64(rTData))
    dut.io.regXAck.poke(true.B)
    dut.io.regYAck.poke(true.B)
    dut.io.regZAck.poke(true.B)
    dut.io.rJAck.poke(true.B)
    dut.io.rTAck.poke(true.B)
    dut.clock.step()
  }

  // ── TRAP X,Y,Z ──
  // XYZ=0/1의 "종료 요청"/"기본 동작" 의미는 OS 소프트웨어 관례일 뿐 하드웨어가 XYZ 값을
  // 보고 분기하는 게 아니다(§29) — 순수 하드웨어 동작만 테스트 가능, pending 유지.
  "Jump" should "TRAP: XYZ=0 → 사용자 프로세스 종료 요청" in (pending)
  "Jump" should "TRAP: XYZ=1 → 핸들러 없는 인터럽트에 대한 기본 동작" in (pending)

  "Jump" should "TRAP: rK가 0으로 클리어된다" in {
    test(new JumpHarness) { dut =>
      val xyz = (0x11 << 16) | (0x22 << 8) | 0x33
      issue(dut, TRAP, xyz, yData = 1, zData = 2, rJData = 3, xData = 4, rTData = 0x9000L)
      dut.io.result.writeRK.expect(true.B)
      dut.io.result.rkValue.expect(u64(0))
    }
  }

  "Jump" should "TRAP: rT가 가리키는 위치(절대주소)로 제어가 넘어간다" in {
    test(new JumpHarness) { dut =>
      val xyz = (0x11 << 16) | (0x22 << 8) | 0x33
      issue(dut, TRAP, xyz, yData = 1, zData = 2, rJData = 3, xData = 4, rTData = 0x9000L)
      dut.io.result.storeMethod.expect(1.U)
      dut.io.result.offset.expect(u64(0x9000L))
    }
  }

  "Jump" should "TRAP: return_addr_reg=rWW(λ+4는 커미터가 채움)" in {
    test(new JumpHarness) { dut =>
      val xyz = (0x11 << 16) | (0x22 << 8) | 0x33
      issue(dut, TRAP, xyz, yData = 1, zData = 2, rJData = 3, xData = 4, rTData = 0x9000L)
      dut.io.result.returnAddrReg.expect(8.U)  // 0_0000_1000 = rWW
    }
  }

  "Jump" should "TRAP: $255 ← rJ(트랩 전 값), rBB 자리엔 $255(트랩 전 값)가 뱅크에 실린다" in {
    test(new JumpHarness) { dut =>
      val xyz = (0x11 << 16) | (0x22 << 8) | 0x33
      issue(dut, TRAP, xyz, yData = 1, zData = 2, rJData = 3, xData = 4, rTData = 0x9000L)
      dut.io.result.write255.expect(true.B)
      dut.io.result.value255.expect(u64(3))  // rJ 이전 값
      dut.io.result.bankActive.expect(true.B)
      dut.io.result.bankSel.expect(true.B)   // 트랩뱅크
      dut.io.result.bankData(1).expect(u64(1))  // $Y
      dut.io.result.bankData(2).expect(u64(2))  // $Z
      dut.io.result.bankData(3).expect(u64(4))  // $255 이전 값(=rBB 자리)
    }
  }

  "Jump" should "TRAP: rXX(뱅크 0번 워드) = #80000000 + 이 명령 인코딩" in {
    test(new JumpHarness) { dut =>
      val xyz = (0x11 << 16) | (0x22 << 8) | 0x33
      issue(dut, TRAP, xyz, yData = 1, zData = 2, rJData = 3, xData = 4, rTData = 0x9000L)
      // opcode=0x00, X=0x11,Y=0x22,Z=0x33 → 인코딩=0x00112233, 좌측 #80000000
      dut.io.result.bankData(0).expect(u64(BigInt("8000000000112233", 16)))
    }
  }

  // ── GO $X,$Y,$Z|Z ──
  "Jump" should "GO: $Y+$Z(레지스터 오퍼랜드) 절대주소로 이동한다" in {
    test(new JumpHarness) { dut =>
      val xyz = (1 << 16) | (2 << 8) | 3  // X=1,Y=2,Z=3(레지스터)
      issue(dut, GO, xyz, 100, 23, 0)
      dut.io.result.storeMethod.expect(1.U)
      dut.io.result.offset.expect(u64(123))
    }
  }

  "Jump" should "GO: $Y+Z(즉치 오퍼랜드) 절대주소로 이동한다" in {
    test(new JumpHarness) { dut =>
      val xyz = (1 << 16) | (2 << 8) | 5  // X=1,Y=2,Z=5(즉치)
      issue(dut, GOI, xyz, 100, 0, 0)     // regZ는 안 쓰이니 무해한 값
      dut.io.result.storeMethod.expect(1.U)
      dut.io.result.offset.expect(u64(105))
    }
  }

  "Jump" should "GO: $X ← λ+4(원래 다음에 실행됐을 위치)가 저장된다" in {
    test(new JumpHarness) { dut =>
      val xyz = (7 << 16) | (2 << 8) | 3
      issue(dut, GO, xyz, 100, 23, 0)
      dut.io.result.returnAddrReg.expect(((1 << 8) | 7).U)  // 1_xxxxxxxx = 즉치 레지스터 7번
    }
  }

  "Jump" should "GO: JMP와 달리 상대주소가 아니다 — λ에 더하지 않고 $Y+$Z를 그대로 저장" in {
    test(new JumpHarness) { dut =>
      val xyz = (1 << 16) | (2 << 8) | 3
      issue(dut, GO, xyz, 100, 23, 0)
      dut.io.result.storeMethod.expect(1.U)  // 01=그냥 저장(절대주소), 10=λ+offset(상대)이 아님
    }
  }

  // ── JMP @+4*XYZ[-67108864] ──
  "Jump" should "JMP: XYZ=0(최소) → offset=0" in {
    test(new JumpHarness) { dut =>
      issue(dut, JMP, 0, 0, 0, 0)
      dut.io.result.offset.expect(u64(0))
    }
  }

  "Jump" should "JMP: XYZ=0xFFFFFF(24비트 최대, forward) → offset=67108860" in {
    test(new JumpHarness) { dut =>
      issue(dut, JMP, 0xFFFFFF, 0, 0, 0)
      dut.io.result.offset.expect(u64(67108860L))
    }
  }

  "Jump" should "JMP: forward — offset = XYZ*4" in {
    test(new JumpHarness) { dut =>
      issue(dut, JMP, 0x1234, 0, 0, 0)
      dut.io.result.offset.expect(u64(0x1234L * 4))
    }
  }

  "Jump" should "JMPB: backward — offset = XYZ*4 - 67108864" in {
    test(new JumpHarness) { dut =>
      issue(dut, JMPB, 0x1234, 0, 0, 0)
      dut.io.result.offset.expect(u64(0x1234L * 4 - 67108864L))
    }
  }

  "Jump" should "JMP/JMPB: 무조건 분기라 storeMethod는 항상 상대(λ+offset) 저장" in {
    test(new JumpHarness) { dut =>
      issue(dut, JMP, 0x1234, 0, 0, 0)
      dut.io.result.storeMethod.expect(2.U)
    }
    test(new JumpHarness) { dut =>
      issue(dut, JMPB, 0x1234, 0, 0, 0)
      dut.io.result.storeMethod.expect(2.U)
    }
  }

  // ── PUSHJ $X,@+4*YZ[-262144] (X=n, 즉치) ──
  // n<L / L<=n<G / n>=G / n=255 push 범위 규칙, hole에 개수 저장 — 실제 push 메커니즘은
  // 커미터(레지스터스택 엔진) 몫이라 이 파이프만으로는 검증 불가, pending 유지.
  "Jump" should "PUSHJ: n < L — 로컬 레지스터 $0..$n을 push하고 L이 감소한다" in (pending)
  "Jump" should "PUSHJ: L <= n < G — L이 n+1로 확장된 뒤 push 규칙이 적용된다" in (pending)
  "Jump" should "PUSHJ: n >= G — 로컬 레지스터 전부가 push되고 L이 재설정된다" in (pending)
  "Jump" should "PUSHJ: n=255 — 현재 정의된 로컬 레지스터 전부를 push하고 L=0이 된다" in (pending)
  "Jump" should "PUSHJ: push 후 새로 로컬이 된 레지스터 $n(hole)에 push된 개수 n이 저장된다" in (pending)

  "Jump" should "PUSHJ: rJ ← λ+4(복귀 주소)가 저장된다" in {
    test(new JumpHarness) { dut =>
      val xyz = (5 << 16) | 0x1234
      issue(dut, PUSHJ, xyz, 0, 0, 0)
      dut.io.result.returnAddrReg.expect(2.U)  // 0_0000_0010 = rJ
      dut.io.result.stackOp.expect(1.U)
      dut.io.result.stackN.expect(5.U)
    }
  }

  "Jump" should "PUSHJ: forward — λ+4*YZ의 서브루틴으로 점프한다" in {
    test(new JumpHarness) { dut =>
      val xyz = (5 << 16) | 0x1234
      issue(dut, PUSHJ, xyz, 0, 0, 0)
      dut.io.result.storeMethod.expect(2.U)
      dut.io.result.offset.expect(u64(0x1234L * 4))
    }
  }

  "Jump" should "PUSHJB: backward — λ+4*(YZ-2^16)의 서브루틴으로 점프한다" in {
    test(new JumpHarness) { dut =>
      val xyz = (5 << 16) | 0x1234
      issue(dut, PUSHJB, xyz, 0, 0, 0)
      dut.io.result.offset.expect(u64(0x1234L * 4 - 262144L))
    }
  }

  // ── PUSHGO $X,$Y,$Z|Z ──
  "Jump" should "PUSHGO: PUSHJ와 동일한 push 규칙(n<L, L<=n<G, n>=G, n=255)을 따른다" in (pending)

  "Jump" should "PUSHGO: 점프 목적지가 $Y+$Z(레지스터) 절대주소다" in {
    test(new JumpHarness) { dut =>
      val xyz = (5 << 16) | (2 << 8) | 3
      issue(dut, PUSHGO, xyz, 100, 23, 0)
      dut.io.result.storeMethod.expect(1.U)
      dut.io.result.offset.expect(u64(123))
    }
  }

  "Jump" should "PUSHGO: 점프 목적지가 $Y+Z(즉치) 절대주소다" in {
    test(new JumpHarness) { dut =>
      val xyz = (5 << 16) | (2 << 8) | 7
      issue(dut, PUSHGOI, xyz, 100, 0, 0)
      dut.io.result.offset.expect(u64(107))
    }
  }

  "Jump" should "PUSHGO: rJ ← λ+4(복귀 주소)가 저장된다" in {
    test(new JumpHarness) { dut =>
      val xyz = (5 << 16) | (2 << 8) | 3
      issue(dut, PUSHGO, xyz, 100, 23, 0)
      dut.io.result.returnAddrReg.expect(2.U)  // 0_0000_0010 = rJ
      dut.io.result.stackOp.expect(1.U)
      dut.io.result.stackN.expect(5.U)
    }
  }

  // ── POP X,YZ (X=n, 즉치) ──
  // 실제 몇 개를 되돌릴지·hole 처리·L/G 보정은 커미터(레지스터스택 엔진) 몫이라 pending 유지.
  "Jump" should "POP: n=0 — 출력 없음, hole이 사라지고 marginal이 된다" in (pending)
  "Jump" should "POP: n=1 — 출력 $0 하나, hole에 이전 $0 값이 들어간다" in (pending)
  "Jump" should "POP: n>=2(예: 5) — 출력이 여러 개면 마지막 값이 hole로 가는 순서 뒤바뀜(레지스터 셔플)이 일어난다" in (pending)
  "Jump" should "POP: n > L이면 n을 L+1로 치환하는 보정 규칙이 적용된다" in (pending)
  "Jump" should "POP: n=255 — 현재 로컬 레지스터 전부가 출력으로 보존된다" in (pending)

  "Jump" should "POP: M4[4*YZ + rJ]가 가리키는 위치(절대주소)로 점프한다" in {
    test(new JumpHarness) { dut =>
      val xyz = (3 << 16) | 0x0010  // n=3, YZ=0x10
      issue(dut, POP, xyz, 0, 0, 1000)
      dut.io.result.storeMethod.expect(1.U)
      dut.io.result.offset.expect(u64(1000 + 0x10 * 4))
      dut.io.result.stackOp.expect(2.U)
      dut.io.result.stackN.expect(3.U)
    }
  }

  // ── RESUME Z ──
  // rX(rXX)가 음수인 "단순 재개" 경로만 구현 범위 — ropcode(rX>=0) 실제 명령 삽입 동작은
  // 미구현이라 우리 구현은 illegal로 처리한다(사용자 확정, 아래 별도 케이스로 검증).
  private val NEG_ONE: BigInt = (BigInt(1) << 64) - 1  // rX 부호비트=1(음수)인 대표값

  "Jump" should "RESUME: Z=0 — 트립뱅크(rW/rX/rY/rZ)로 재개한다" in {
    test(new JumpHarness) { dut =>
      val bank = Seq(NEG_ONE, BigInt(0), BigInt(0), BigInt(0), BigInt(0x9000))  // [rX,rY,rZ,rB,rW]
      issue(dut, RESUME, 0, yData = 0, zData = 0, rJData = 0, xData = 0, bank = bank)
      dut.io.result.storeMethod.expect(1.U)
      dut.io.result.offset.expect(u64(0x9000))
      dut.io.result.illegal.expect(false.B)
    }
  }

  "Jump" should "RESUME: Z=1 — 트랩뱅크(rWW/rXX/rYY/rZZ)로 재개한다(RESUME이 음수 위치에 있어야 함)" in {
    test(new JumpHarness) { dut =>
      val bank = Seq(NEG_ONE, BigInt(0), BigInt(0), BigInt(0), BigInt(0xA000))  // [rXX,rYY,rZZ,rBB,rWW]
      issue(dut, RESUME, 1, yData = 0, zData = 0, rJData = 0, xData = 0, bank = bank)
      dut.io.result.storeMethod.expect(1.U)
      dut.io.result.offset.expect(u64(0xA000))
      dut.io.result.illegal.expect(false.B)
    }
  }

  "Jump" should "RESUME: rX가 음수면 그대로 rW부터 재개한다(illegal 아님)" in {
    test(new JumpHarness) { dut =>
      val bank = Seq(NEG_ONE, BigInt(0), BigInt(0), BigInt(0), BigInt(0x9000))
      issue(dut, RESUME, 0, yData = 0, zData = 0, rJData = 0, xData = 0, bank = bank)
      dut.io.result.illegal.expect(false.B)
    }
  }

  // ropcode(rX>=0)는 이 구현 범위 밖 — 실제 명령 삽입 대신 illegal로 처리한다.
  "Jump" should "RESUME: rX>=0, ropcode=0 — rX의 명령을 그대로 실행 스트림에 삽입한다" in (pending)
  "Jump" should "RESUME: rX>=0, ropcode=1 — rY/rZ로 오퍼랜드를 대체해서 삽입한다" in (pending)
  "Jump" should "RESUME: rX>=0, ropcode=2 — $X(rX 우측절반의 둘째 바이트) ← rZ로 설정하고 rA에 예외비트를 반영한다" in (pending)
  "Jump" should "RESUME: rX>=0, ropcode=3 — ropcode 0과 같되 rY를 가상주소, rZ를 그 페이지테이블 엔트리로 취급한다(RESUME 1 전용)" in (pending)

  "Jump" should "RESUME: rX>=0(ropcode 필요, 미구현) → illegal로 처리한다" in {
    test(new JumpHarness) { dut =>
      val bank = Seq(BigInt(0), BigInt(0), BigInt(0), BigInt(0), BigInt(0x9000))  // rX=0(음수 아님)
      issue(dut, RESUME, 0, yData = 0, zData = 0, rJData = 0, xData = 0, bank = bank)
      dut.io.result.illegal.expect(true.B)
      dut.io.result.storeMethod.expect(0.U)  // 저장 안 함
    }
  }

  "Jump" should "RESUME: Z=1일 때 rK ← $255, $255 ← rBB로 설정된다" in {
    test(new JumpHarness) { dut =>
      val bank = Seq(NEG_ONE, BigInt(0), BigInt(0), BigInt(777), BigInt(0x9000))  // rBB=777
      issue(dut, RESUME, 1, yData = 0, zData = 0, rJData = 0, xData = 555, bank = bank)  // $255(이전)=555
      dut.io.result.writeRK.expect(true.B)
      dut.io.result.rkValue.expect(u64(555))    // 이전 $255
      dut.io.result.write255.expect(true.B)
      dut.io.result.value255.expect(u64(777))   // rBB
    }
  }

  "Jump" should "RESUME: Z=0일 때는 rK/$255를 안 건드린다" in {
    test(new JumpHarness) { dut =>
      val bank = Seq(NEG_ONE, BigInt(0), BigInt(0), BigInt(0), BigInt(0x9000))
      issue(dut, RESUME, 0, yData = 0, zData = 0, rJData = 0, xData = 0, bank = bank)
      dut.io.result.writeRK.expect(false.B)
      dut.io.result.write255.expect(false.B)
    }
  }

  "Jump" should "RESUME: Z>1이면 illegal instruction(rQ의 b비트가 세팅됨)이 발생한다" in {
    test(new JumpHarness) { dut =>
      val bank = Seq(NEG_ONE, BigInt(0), BigInt(0), BigInt(0), BigInt(0x9000))
      issue(dut, RESUME, 2, yData = 0, zData = 0, rJData = 0, xData = 0, bank = bank)
      dut.io.result.illegal.expect(true.B)
      dut.io.result.storeMethod.expect(0.U)
    }
  }

  // ── TRIP X,Y,Z ──
  "Jump" should "TRIP: 가상주소 0번지(고정)로 제어가 넘어간다" in {
    test(new JumpHarness) { dut =>
      issue(dut, TRIP, 0x112233, yData = 1, zData = 2, rJData = 3, xData = 4)
      dut.io.result.storeMethod.expect(1.U)
      dut.io.result.offset.expect(u64(0))
    }
  }

  "Jump" should "TRIP: rX 우측절반에 자기 자신(TRIP 명령)이 기록된다" in {
    test(new JumpHarness) { dut =>
      issue(dut, TRIP, 0x112233, yData = 1, zData = 2, rJData = 3, xData = 4)
      // opcode=0xFF, X=0x11,Y=0x22,Z=0x33 → 인코딩=0xFF112233
      dut.io.result.bankData(0).expect(u64(BigInt("80000000FF112233", 16)))
    }
  }

  "Jump" should "TRIP: rX 좌측 32비트가 #80000000으로 설정되어 rX가 음수가 된다(RESUME의 재-TRIP 방지 표시)" in {
    test(new JumpHarness) { dut =>
      issue(dut, TRIP, 0x112233, yData = 1, zData = 2, rJData = 3, xData = 4)
      dut.io.result.bankData(0).expect(u64(BigInt("80000000FF112233", 16)))
    }
  }

  "Jump" should "TRIP: rK는 안 건드리고(TRAP만의 동작), return_addr_reg=rW" in {
    test(new JumpHarness) { dut =>
      issue(dut, TRIP, 0x112233, yData = 1, zData = 2, rJData = 3, xData = 4)
      dut.io.result.writeRK.expect(false.B)
      dut.io.result.returnAddrReg.expect(4.U)  // 0_0000_0100 = rW
    }
  }

  "Jump" should "TRIP: $255 ← rJ(이전 값), 뱅크는 트립뱅크(트랩뱅크 아님)" in {
    test(new JumpHarness) { dut =>
      issue(dut, TRIP, 0x112233, yData = 1, zData = 2, rJData = 3, xData = 4)
      dut.io.result.write255.expect(true.B)
      dut.io.result.value255.expect(u64(3))
      dut.io.result.bankSel.expect(false.B)  // 트립뱅크
    }
  }
}
