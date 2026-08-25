package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/*
 * flag[5:4]가 최상위 분기(00=CMP, 01=CSWAP, 10=CS_, 11=ZS_)이고,
 * flag[3]=조건반전, flag[2:1]=조건(NEG/ZERO/POS/ODD), flag[0]=즉치.
 * 값은 설계/파이프/비교의 표를 그대로 16진수로 옮김.
 */

/** Comp를 감싸서 레지스터버스 쪽 신호(ack/data)를 평평한 IO로 노출하는 테스트용 하네스.
 * RegArbiter가 아직 목업이라 실제 버스 대신 여기서 직접 ack/data를 준다.
 */
class CompHarness extends Module {
  val io = IO(new Bundle {
    val op        = Input(CompOp())
    val pause     = Input(Bool())
    val regYData  = Input(UInt(64.W))
    val regYAck   = Input(Bool())
    val regZData  = Input(UInt(64.W))
    val regZAck   = Input(Bool())
    val result    = Output(CompResult())
  })

  val arbiter = Module(new RegArbiter)
  val factory = new RegReadPortFactory(arbiter)
  val comp    = Module(new Comp(factory))

  comp.io.op         := io.op
  comp.io.pause      := io.pause
  comp.io.reg.x.data := 0.U   // Comp는 X(목적지)를 읽지 않음 — req가 항상 0이라 값은 안 쓰임
  comp.io.reg.x.ack  := false.B
  comp.io.reg.y.data := io.regYData
  comp.io.reg.y.ack  := io.regYAck
  comp.io.reg.z.data := io.regZData
  comp.io.reg.z.ack  := io.regZAck
  io.result := comp.io.result
}

class CompTest extends AnyFlatSpec with ChiselScalatestTester {

  val CMP = 0x00; val CMPI = 0x01; val CMPU = 0x02; val CMPUI = 0x03

  def setOp(dut: CompHarness, f: Int, x: Int, y: Int, z: Int): Unit = {
    dut.io.op.flag.poke(f.U)
    dut.io.op.x.poke(x.U)
    dut.io.op.y.poke(y.U)
    dut.io.op.z.poke(z.U)
    dut.io.pause.poke(false.B)
  }

  val MASK64: BigInt = (BigInt(1) << 64) - 1
  def u64(v: BigInt): UInt = (v & MASK64).U(64.W)

  /** 실제 버스 왕복을 2단계로 흉내낸다 — 1클럭째는 op(주소)만 보이고 버스는 아직 응답 안 함,
   * 2클럭째에야 버스가 ack+data로 응답한다. 그래서 답은 2번째 step 이후에나 준비된다.
   * 즉치 모드에선 regZAck/regZData는 무시되니 아무 값이나 넘겨도 무해하다.
   */
  def issue(dut: CompHarness, f: Int, x: Int, y: Int, z: Int, yData: BigInt, zData: BigInt): Unit = {
    dut.io.regYAck.poke(false.B)
    dut.io.regZAck.poke(false.B)
    setOp(dut, f, x, y, z)
    dut.clock.step()

    dut.io.regYData.poke(u64(yData))
    dut.io.regZData.poke(u64(zData))
    dut.io.regYAck.poke(true.B)
    dut.io.regZAck.poke(true.B)
    dut.clock.step()
  }

  // CMP $X,$Y,$Z — 부호있는 비교
  "Comp" should "CMP: 3 < 5 → -1" in {
    test(new CompHarness) { dut =>
      issue(dut, CMP, 1, 2, 3, 3, 5)
      dut.io.result.res.expect(u64(-1))
      dut.io.result.dest.expect(1.U)
      dut.io.result.write.expect(true.B)
    }
  }

  "Comp" should "CMP: 5 == 5 → 0" in {
    test(new CompHarness) { dut =>
      issue(dut, CMP, 1, 2, 3, 5, 5)
      dut.io.result.res.expect(u64(0))
    }
  }

  "Comp" should "CMP: 5 > 3 → 1" in {
    test(new CompHarness) { dut =>
      issue(dut, CMP, 1, 2, 3, 5, 3)
      dut.io.result.res.expect(u64(1))
    }
  }

  "Comp" should "CMP: 부호있는 비교 — -1 < 1 → -1" in {
    test(new CompHarness) { dut =>
      issue(dut, CMP, 1, 2, 3, -1, 1)
      dut.io.result.res.expect(u64(-1))
    }
  }

  "Comp" should "CMPU: 부호없는 비교 — (-1의 비트패턴)이 1보다 커서 1" in {
    test(new CompHarness) { dut =>
      issue(dut, CMPU, 1, 2, 3, -1, 1)
      dut.io.result.res.expect(u64(1))
    }
  }

  // 경계값 — 큰 양수 vs 큰 음수: 부호있음/없음에서 결과가 정반대로 갈린다
  val I64_MAX: BigInt = (BigInt(1) << 63) - 1   // 0x7FFF...FFFF
  val I64_MIN: BigInt = -(BigInt(1) << 63)      // 0x8000...0000 비트패턴

  "Comp" should "CMP: MAX > MIN (부호있는 크기로는 큰 양수가 큼) → 1" in {
    test(new CompHarness) { dut =>
      issue(dut, CMP, 1, 2, 3, I64_MAX, I64_MIN)
      dut.io.result.res.expect(u64(1))
    }
  }

  "Comp" should "CMP: MIN < MAX → -1" in {
    test(new CompHarness) { dut =>
      issue(dut, CMP, 1, 2, 3, I64_MIN, I64_MAX)
      dut.io.result.res.expect(u64(-1))
    }
  }

  "Comp" should "CMP: MIN == MIN → 0" in {
    test(new CompHarness) { dut =>
      issue(dut, CMP, 1, 2, 3, I64_MIN, I64_MIN)
      dut.io.result.res.expect(u64(0))
    }
  }

  "Comp" should "CMP: 큰 음수끼리 — -2 < -1 → -1" in {
    test(new CompHarness) { dut =>
      issue(dut, CMP, 1, 2, 3, -2, -1)
      dut.io.result.res.expect(u64(-1))
    }
  }

  "Comp" should "CMPU: MAX < MIN의 비트패턴(최상위 비트가 서서 unsigned로는 더 큼) → -1" in {
    test(new CompHarness) { dut =>
      issue(dut, CMPU, 1, 2, 3, I64_MAX, I64_MIN)
      dut.io.result.res.expect(u64(-1)) // CMP와 정반대 — 부호 해석 차이
    }
  }

  "Comp" should "CMPU: MIN의 비트패턴 > MAX (unsigned) → 1" in {
    test(new CompHarness) { dut =>
      issue(dut, CMPU, 1, 2, 3, I64_MIN, I64_MAX)
      dut.io.result.res.expect(u64(1))
    }
  }

  "Comp" should "CMPU: 최대 unsigned값(-1의 비트패턴) vs MIN → 여전히 더 큼 → 1" in {
    test(new CompHarness) { dut =>
      issue(dut, CMPU, 1, 2, 3, -1, I64_MIN)
      dut.io.result.res.expect(u64(1))
    }
  }

  "Comp" should "CMPI: 즉치 — 10 > imm(3) → 1" in {
    test(new CompHarness) { dut =>
      issue(dut, CMPI, 1, 2, 3, 10, 0)
      dut.io.result.res.expect(u64(1))
    }
  }

  // CS_/ZS_ 8조건 — 만족 시 X←Z(write=true), CS는 불만족 시 쓰기 자체를 생략(write=false),
  // ZS는 불만족 시 X←0(write=true)
  case class Cond(name: String, csFlag: Int, zsFlag: Int, yTrue: BigInt, yFalse: BigInt)
  val conds = Seq(
    Cond("N",  0x28, 0x38, -1, 1), // NEG
    Cond("Z",  0x2A, 0x3A, 0, 1),  // ZERO
    Cond("P",  0x2C, 0x3C, 1, 0),  // POS
    Cond("OD", 0x2E, 0x3E, 3, 4),  // ODD
    Cond("NN", 0x20, 0x30, 0, -1), // NOTNEG
    Cond("NZ", 0x22, 0x32, 1, 0),  // NOTZERO
    Cond("NP", 0x24, 0x34, 0, 1),  // NOTPOS
    Cond("EV", 0x26, 0x36, 4, 3)   // EVEN
  )

  conds.foreach { c =>
    "Comp" should s"CS${c.name}: 조건 만족(y=${c.yTrue}) → res=Z, write=true" in {
      test(new CompHarness) { dut =>
        issue(dut, c.csFlag, 1, 2, 3, c.yTrue, 42)
        dut.io.result.res.expect(u64(42))
        dut.io.result.write.expect(true.B)
      }
    }

    "Comp" should s"CS${c.name}: 조건 불만족(y=${c.yFalse}) → write=false (레지스터 쓰기 생략)" in {
      test(new CompHarness) { dut =>
        issue(dut, c.csFlag, 1, 2, 3, c.yFalse, 42)
        dut.io.result.write.expect(false.B)
      }
    }

    "Comp" should s"ZS${c.name}: 조건 만족(y=${c.yTrue}) → res=Z, write=true" in {
      test(new CompHarness) { dut =>
        issue(dut, c.zsFlag, 1, 2, 3, c.yTrue, 42)
        dut.io.result.res.expect(u64(42))
        dut.io.result.write.expect(true.B)
      }
    }

    "Comp" should s"ZS${c.name}: 조건 불만족(y=${c.yFalse}) → res=0, write=true" in {
      test(new CompHarness) { dut =>
        issue(dut, c.zsFlag, 1, 2, 3, c.yFalse, 42)
        dut.io.result.res.expect(u64(0))
        dut.io.result.write.expect(true.B)
      }
    }
  }

  "Comp" should "pause=1이면 이전 값 유지" in {
    test(new CompHarness) { dut =>
      issue(dut, CMP, 1, 2, 3, 5, 3)
      dut.io.result.res.expect(u64(1))

      dut.io.pause.poke(true.B)
      dut.io.regYData.poke(u64(1))
      dut.io.regZData.poke(u64(9))
      dut.clock.step()
      dut.io.result.res.expect(u64(1))
    }
  }
}
