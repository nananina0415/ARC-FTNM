package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/*
 * flag(4)=PROB(설계상 이 파이프의 조건판정/storeMethod엔 영향 없음 — 예측/추측페치는
 * 디코더 몫), flag(3)=cond_inv 원본비트(condInv = !flag(3)), flag(2:1)=cond
 * (0=NEG 1=ZERO 2=POS 3=ODD), flag(0)=back. 값은 package.scala의 NEG/ZERO/POS/ODD/
 * NOTNEG/NOTZERO/NOTPOS/EVEN/PROB/BACK 상수를 5비트로 자른 것과 동일.
 */

/** Branch를 감싸서 레지스터버스 쪽 신호(ack/data)를 평평한 IO로 노출하는 테스트용 하네스.
 * RegArbiter가 아직 목업이라 실제 버스 대신 여기서 직접 ack/data를 준다.
 */
class BranchHarness extends Module {
  val io = IO(new Bundle {
    val op       = Input(BranchOp())
    val pause    = Input(Bool())
    val regXData = Input(UInt(64.W))
    val regXAck  = Input(Bool())
    val regXReq  = Output(Bool())  // X를 실제로 요청 중인지 밖에서 관찰용
    val result   = Output(BranchResult())
  })

  val arbiter = Module(new RegArbiter)
  val factory = new RegReadPortFactory(arbiter)
  val branch  = Module(new Branch(factory))

  branch.io.op         := io.op
  branch.io.pause      := io.pause
  branch.io.reg.x.data := io.regXData
  branch.io.reg.x.ack  := io.regXAck
  branch.io.reg.y.data := 0.U   // Branch는 Y/Z를 안 씀 — req가 항상 0이라 값은 안 쓰임
  branch.io.reg.y.ack  := false.B
  branch.io.reg.z.data := 0.U
  branch.io.reg.z.ack  := false.B
  io.regXReq := branch.io.reg.x.req
  io.result  := branch.io.result
}

class BranchTest extends AnyFlatSpec with ChiselScalatestTester {

  // package.scala의 NEG/ZERO/.../PROB/BACK 상수(6비트)를 bits(4,0)로 자른 값 — Decoder가
  // branchOut()에서 실제로 넘기는 것과 동일한 값.
  val NEG = 0x08; val ZERO = 0x0A; val POS = 0x0C; val ODD = 0x0E
  val NOTNEG = 0x00; val NOTZERO = 0x02; val NOTPOS = 0x04; val EVEN = 0x06
  val PROB = 0x10; val BACK = 0x01

  def setOp(dut: BranchHarness, f: Int, x: Int, yz: Int): Unit = {
    dut.io.op.flag.poke(f.U)
    dut.io.op.x.poke(x.U)
    dut.io.op.yz.poke(yz.U)
    dut.io.pause.poke(false.B)
  }

  val MASK64: BigInt = (BigInt(1) << 64) - 1
  def u64(v: BigInt): UInt = (v & MASK64).U(64.W)

  /** 실제 버스 왕복을 2단계로 흉내낸다 — 1클럭째는 op(주소)만 보이고 버스는 아직 응답
   * 안 함, 2클럭째에야 버스가 ack+data로 응답한다. 그래서 답은 2번째 step 이후에나 준비된다.
   */
  def issue(dut: BranchHarness, f: Int, x: Int, yz: Int, xData: BigInt): Unit = {
    dut.io.regXAck.poke(false.B)
    setOp(dut, f, x, yz)
    dut.clock.step()

    dut.io.regXData.poke(u64(xData))
    dut.io.regXAck.poke(true.B)
    dut.clock.step()
  }

  def fwdOffset(yz: Int): BigInt  = BigInt(yz) * 4
  def backOffset(yz: Int): BigInt = BigInt(yz) * 4 - 262144

  val I64_MAX: BigInt = (BigInt(1) << 63) - 1   // 0x7FFF...FFFF (홀수)
  val I64_MIN: BigInt = -(BigInt(1) << 63)      // 0x8000...0000 비트패턴 (짝수)

  // ── 8조건 각각 — 경계값(만족/불만족 갈리는 지점)으로 storeMethod 확인 ──
  case class Cond(name: String, flag: Int, satisfyX: BigInt, failX: BigInt)
  val conds = Seq(
    Cond("NEG",     NEG,     -1, 0),
    Cond("ZERO",    ZERO,     0, 1),
    Cond("POS",     POS,      1, 0),
    Cond("ODD",     ODD,      1, 0),
    Cond("NOTNEG",  NOTNEG,   0, -1),
    Cond("NOTZERO", NOTZERO,  1, 0),
    Cond("NOTPOS",  NOTPOS,   0, 1),
    Cond("EVEN",    EVEN,     0, 1)
  )

  conds.foreach { c =>
    "Branch" should s"B${c.name}: 조건 만족(X=${c.satisfyX}) → storeMethod=10(분기), offset=YZ*4" in {
      test(new BranchHarness) { dut =>
        issue(dut, c.flag, 1, 0x0100, c.satisfyX)
        dut.io.result.storeMethod.expect(2.U)  // 10
        dut.io.result.offset.expect(u64(fwdOffset(0x0100)))
      }
    }

    "Branch" should s"B${c.name}: 조건 불만족(X=${c.failX}) → storeMethod=00(분기 안 함)" in {
      test(new BranchHarness) { dut =>
        issue(dut, c.flag, 1, 0x0100, c.failX)
        dut.io.result.storeMethod.expect(0.U)
      }
    }
  }

  // ── 경계값 — 아주 큰 양수/음수에서도 조건이 정확히 갈리는지 ──
  "Branch" should "BN: X=I64_MIN(가장 큰 음수) → 조건 만족" in {
    test(new BranchHarness) { dut =>
      issue(dut, NEG, 1, 1, I64_MIN)
      dut.io.result.storeMethod.expect(2.U)
    }
  }

  "Branch" should "BP: X=I64_MAX(가장 큰 양수) → 조건 만족" in {
    test(new BranchHarness) { dut =>
      issue(dut, POS, 1, 1, I64_MAX)
      dut.io.result.storeMethod.expect(2.U)
    }
  }

  "Branch" should "BOD: X=I64_MAX(홀수) → 조건 만족" in {
    test(new BranchHarness) { dut =>
      issue(dut, ODD, 1, 1, I64_MAX)
      dut.io.result.storeMethod.expect(2.U)
    }
  }

  "Branch" should "BEV: X=I64_MIN(짝수) → 조건 만족" in {
    test(new BranchHarness) { dut =>
      issue(dut, EVEN, 1, 1, I64_MIN)
      dut.io.result.storeMethod.expect(2.U)
    }
  }

  "Branch" should "BZ: X=-1도 X=1도 둘 다 불만족(0의 양쪽 경계)" in {
    test(new BranchHarness) { dut =>
      issue(dut, ZERO, 1, 1, -1)
      dut.io.result.storeMethod.expect(0.U)
    }
    test(new BranchHarness) { dut =>
      issue(dut, ZERO, 1, 1, 1)
      dut.io.result.storeMethod.expect(0.U)
    }
  }

  "Branch" should "BNP: X=-1도 조건 만족(0 또는 음수)" in {
    test(new BranchHarness) { dut =>
      issue(dut, NOTPOS, 1, 1, -1)
      dut.io.result.storeMethod.expect(2.U)
    }
  }

  "Branch" should "BNN: X=I64_MAX도 조건 만족(0 이상)" in {
    test(new BranchHarness) { dut =>
      issue(dut, NOTNEG, 1, 1, I64_MAX)
      dut.io.result.storeMethod.expect(2.U)
    }
  }

  // ── forward/backward 오프셋 계산 ──
  "Branch" should "BN: forward — offset = YZ*4" in {
    test(new BranchHarness) { dut =>
      issue(dut, NEG, 1, 0x1234, -1)
      dut.io.result.offset.expect(u64(fwdOffset(0x1234)))
    }
  }

  "Branch" should "BNB: backward — offset = YZ*4 - 262144" in {
    test(new BranchHarness) { dut =>
      issue(dut, NEG | BACK, 1, 0x1234, -1)
      dut.io.result.offset.expect(u64(backOffset(0x1234)))
    }
  }

  "Branch" should "BNB: YZ가 작아도 backward는 offset이 음수가 됨" in {
    test(new BranchHarness) { dut =>
      issue(dut, NEG | BACK, 1, 0x0001, -1)
      dut.io.result.offset.expect(u64(backOffset(0x0001)))
    }
  }

  // ── PROB 비트는 이 파이프의 조건판정/storeMethod에 영향 없음(예측은 디코더 몫) ──
  "Branch" should "PBN: PROB가 섞여도 일반 BN과 조건판정 결과가 같다(만족)" in {
    test(new BranchHarness) { dut =>
      issue(dut, PROB | NEG, 1, 0x0100, -1)
      dut.io.result.storeMethod.expect(2.U)
      dut.io.result.offset.expect(u64(fwdOffset(0x0100)))
    }
  }

  "Branch" should "PBN: PROB가 섞여도 일반 BN과 조건판정 결과가 같다(불만족)" in {
    test(new BranchHarness) { dut =>
      issue(dut, PROB | NEG, 1, 0x0100, 0)
      dut.io.result.storeMethod.expect(0.U)
    }
  }

  // ── 지연/pause ──
  "Branch" should "레지스터 응답 오기 전엔 X를 계속 요청한다" in {
    test(new BranchHarness) { dut =>
      dut.io.regXAck.poke(false.B)
      setOp(dut, NEG, 1, 0x0100)
      dut.io.regXReq.expect(false.B) // set은 다음 클럭에야 q에 반영됨
      dut.clock.step()
      dut.io.regXReq.expect(true.B)  // 이제 요청이 걸림 — 아직 응답 없음

      dut.io.regXData.poke(u64(-1))
      dut.io.regXAck.poke(true.B)    // clear는 비동기라 즉시 반영 — step 없이도 바로 풀림
      dut.io.regXReq.expect(false.B)
    }
  }

  "Branch" should "pause=1이면 이전 값 유지" in {
    test(new BranchHarness) { dut =>
      issue(dut, NEG, 1, 0x0100, -1)
      dut.io.result.storeMethod.expect(2.U)

      dut.io.pause.poke(true.B)
      dut.io.regXAck.poke(true.B)
      dut.io.regXData.poke(u64(0))   // 조건이 안 맞는 값을 흘려도
      setOp(dut, ZERO, 1, 0x0200)
      dut.io.pause.poke(true.B)      // setOp가 pause를 false로 되돌리므로 다시 걸어준다
      dut.clock.step()

      dut.io.result.storeMethod.expect(2.U) // pause 중이라 여전히 이전 결과
      dut.io.result.offset.expect(u64(fwdOffset(0x0100)))
    }
  }
}
