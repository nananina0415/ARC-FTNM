package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/*
 * flag[5:4]가 최상위 분기(00=CMP, 01=CSWAP, 10=CS_, 11=ZS_)이고,
 * flag[3]=조건반전, flag[2:1]=조건(NEG/ZERO/POS/ODD), flag[0]=즉치.
 * 값은 설계/파이프/비교의 표를 그대로 16진수로 옮김.
 */
class CompTest extends AnyFlatSpec with ChiselScalatestTester {

  val CMP = 0x00; val CMPI = 0x01; val CMPU = 0x02; val CMPUI = 0x03

  def setOp(dut: Comp, f: Int, x: Int, y: Int, z: Int): Unit = {
    dut.io.op.flag.poke(f.U)
    dut.io.op.x.poke(x.U)
    dut.io.op.y.poke(y.U)
    dut.io.op.z.poke(z.U)
    dut.io.pause.poke(false.B)
  }

  val MASK64: BigInt = (BigInt(1) << 64) - 1
  def u64(v: BigInt): UInt = (v & MASK64).U(64.W)

  // CMP $X,$Y,$Z — 부호있는 비교
  "Comp" should "CMP: 3 < 5 → -1" in {
    test(new Comp) { dut =>
      dut.io.regY.data.poke(u64(3))
      dut.io.regZ.data.poke(u64(5))
      setOp(dut, CMP, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(-1))
      dut.io.result.dest.expect(1.U)
      dut.io.result.write.expect(true.B)
    }
  }

  "Comp" should "CMP: 5 == 5 → 0" in {
    test(new Comp) { dut =>
      dut.io.regY.data.poke(u64(5))
      dut.io.regZ.data.poke(u64(5))
      setOp(dut, CMP, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(0))
    }
  }

  "Comp" should "CMP: 5 > 3 → 1" in {
    test(new Comp) { dut =>
      dut.io.regY.data.poke(u64(5))
      dut.io.regZ.data.poke(u64(3))
      setOp(dut, CMP, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(1))
    }
  }

  "Comp" should "CMP: 부호있는 비교 — -1 < 1 → -1" in {
    test(new Comp) { dut =>
      dut.io.regY.data.poke(u64(-1))
      dut.io.regZ.data.poke(u64(1))
      setOp(dut, CMP, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(-1))
    }
  }

  "Comp" should "CMPU: 부호없는 비교 — (-1의 비트패턴)이 1보다 커서 1" in {
    test(new Comp) { dut =>
      dut.io.regY.data.poke(u64(-1))
      dut.io.regZ.data.poke(u64(1))
      setOp(dut, CMPU, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(1))
    }
  }

  // 경계값 — 큰 양수 vs 큰 음수: 부호있음/없음에서 결과가 정반대로 갈린다
  val I64_MAX: BigInt = (BigInt(1) << 63) - 1   // 0x7FFF...FFFF
  val I64_MIN: BigInt = -(BigInt(1) << 63)      // 0x8000...0000 비트패턴

  "Comp" should "CMP: MAX > MIN (부호있는 크기로는 큰 양수가 큼) → 1" in {
    test(new Comp) { dut =>
      dut.io.regY.data.poke(u64(I64_MAX))
      dut.io.regZ.data.poke(u64(I64_MIN))
      setOp(dut, CMP, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(1))
    }
  }

  "Comp" should "CMP: MIN < MAX → -1" in {
    test(new Comp) { dut =>
      dut.io.regY.data.poke(u64(I64_MIN))
      dut.io.regZ.data.poke(u64(I64_MAX))
      setOp(dut, CMP, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(-1))
    }
  }

  "Comp" should "CMP: MIN == MIN → 0" in {
    test(new Comp) { dut =>
      dut.io.regY.data.poke(u64(I64_MIN))
      dut.io.regZ.data.poke(u64(I64_MIN))
      setOp(dut, CMP, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(0))
    }
  }

  "Comp" should "CMP: 큰 음수끼리 — -2 < -1 → -1" in {
    test(new Comp) { dut =>
      dut.io.regY.data.poke(u64(-2))
      dut.io.regZ.data.poke(u64(-1))
      setOp(dut, CMP, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(-1))
    }
  }

  "Comp" should "CMPU: MAX < MIN의 비트패턴(최상위 비트가 서서 unsigned로는 더 큼) → -1" in {
    test(new Comp) { dut =>
      dut.io.regY.data.poke(u64(I64_MAX))
      dut.io.regZ.data.poke(u64(I64_MIN))
      setOp(dut, CMPU, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(-1)) // CMP와 정반대 — 부호 해석 차이
    }
  }

  "Comp" should "CMPU: MIN의 비트패턴 > MAX (unsigned) → 1" in {
    test(new Comp) { dut =>
      dut.io.regY.data.poke(u64(I64_MIN))
      dut.io.regZ.data.poke(u64(I64_MAX))
      setOp(dut, CMPU, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(1))
    }
  }

  "Comp" should "CMPU: 최대 unsigned값(-1의 비트패턴) vs MIN → 여전히 더 큼 → 1" in {
    test(new Comp) { dut =>
      dut.io.regY.data.poke(u64(-1))
      dut.io.regZ.data.poke(u64(I64_MIN))
      setOp(dut, CMPU, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(1))
    }
  }

  "Comp" should "CMPI: 즉치 — 10 > imm(3) → 1" in {
    test(new Comp) { dut =>
      dut.io.regY.data.poke(u64(10))
      setOp(dut, CMPI, 1, 2, 3)
      dut.clock.step()
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
      test(new Comp) { dut =>
        dut.io.regY.data.poke(u64(c.yTrue))
        dut.io.regZ.data.poke(u64(42))
        setOp(dut, c.csFlag, 1, 2, 3)
        dut.clock.step()
        dut.io.result.res.expect(u64(42))
        dut.io.result.write.expect(true.B)
      }
    }

    "Comp" should s"CS${c.name}: 조건 불만족(y=${c.yFalse}) → write=false (레지스터 쓰기 생략)" in {
      test(new Comp) { dut =>
        dut.io.regY.data.poke(u64(c.yFalse))
        dut.io.regZ.data.poke(u64(42))
        setOp(dut, c.csFlag, 1, 2, 3)
        dut.clock.step()
        dut.io.result.write.expect(false.B)
      }
    }

    "Comp" should s"ZS${c.name}: 조건 만족(y=${c.yTrue}) → res=Z, write=true" in {
      test(new Comp) { dut =>
        dut.io.regY.data.poke(u64(c.yTrue))
        dut.io.regZ.data.poke(u64(42))
        setOp(dut, c.zsFlag, 1, 2, 3)
        dut.clock.step()
        dut.io.result.res.expect(u64(42))
        dut.io.result.write.expect(true.B)
      }
    }

    "Comp" should s"ZS${c.name}: 조건 불만족(y=${c.yFalse}) → res=0, write=true" in {
      test(new Comp) { dut =>
        dut.io.regY.data.poke(u64(c.yFalse))
        dut.io.regZ.data.poke(u64(42))
        setOp(dut, c.zsFlag, 1, 2, 3)
        dut.clock.step()
        dut.io.result.res.expect(u64(0))
        dut.io.result.write.expect(true.B)
      }
    }
  }

  "Comp" should "pause=1이면 이전 값 유지" in {
    test(new Comp) { dut =>
      dut.io.regY.data.poke(u64(5))
      dut.io.regZ.data.poke(u64(3))
      setOp(dut, CMP, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(1))

      dut.io.pause.poke(true.B)
      dut.io.regY.data.poke(u64(1))
      dut.io.regZ.data.poke(u64(9))
      dut.clock.step()
      dut.io.result.res.expect(u64(1))
    }
  }
}
