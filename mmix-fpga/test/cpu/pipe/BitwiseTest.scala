package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/*
 * flag[3..1]가 op(OR/AND/XOR/NOR/NAND/NXOR)과 not_z/not_res를 함께 결정하고,
 * flag[0]이 즉치 여부를 결정한다 (설계/파이프/비트와이즈, Decoder.scala의 bitwiseOut(...) 호출과 일치):
 *   NOR=0x0 NORI=0x1 NAND=0x2 NANDI=0x3 XOR=0x4 XORI=0x5 NXOR=0x6 NXORI=0x7
 *   OR=0x8 ORI=0x9 ORN=0xA ORNI=0xB AND=0xC ANDI=0xD ANDN=0xE ANDNI=0xF
 */
class BitwiseTest extends AnyFlatSpec with ChiselScalatestTester {

  val NOR = 0x0; val NORI  = 0x1; val NAND = 0x2; val NANDI = 0x3
  val XOR = 0x4; val XORI  = 0x5; val NXOR = 0x6; val NXORI = 0x7
  val OR  = 0x8; val ORI   = 0x9; val ORN  = 0xA; val ORNI  = 0xB
  val AND = 0xC; val ANDI  = 0xD; val ANDN = 0xE; val ANDNI = 0xF

  def setOp(dut: Bitwise, f: Int, x: Int, y: Int, z: Int): Unit = {
    dut.io.op.flag.poke(f.U)
    dut.io.op.x.poke(x.U)
    dut.io.op.y.poke(y.U)
    dut.io.op.z.poke(z.U)
    dut.io.pause.poke(false.B)
  }

  val MASK64: BigInt = (BigInt(1) << 64) - 1
  def m(v: BigInt): BigInt = v & MASK64
  def u64(v: BigInt): UInt = m(v).U(64.W)

  // LogicUnit과 동일한 골든 모델 — 손으로 16진수를 유도하는 대신 같은 연산을 BigInt로 재현
  def orExp(a: BigInt, b: BigInt): BigInt   = m(a | b)
  def andExp(a: BigInt, b: BigInt): BigInt  = m(a & b)
  def xorExp(a: BigInt, b: BigInt): BigInt  = m(a ^ b)
  def norExp(a: BigInt, b: BigInt): BigInt  = m(~orExp(a, b))
  def nandExp(a: BigInt, b: BigInt): BigInt = m(~andExp(a, b))
  def nxorExp(a: BigInt, b: BigInt): BigInt = m(~xorExp(a, b))
  def ornExp(a: BigInt, b: BigInt): BigInt  = m(a | m(~b))
  def andnExp(a: BigInt, b: BigInt): BigInt = m(a & m(~b))

  val Y: BigInt = 12 // 0b1100
  val Z: BigInt = 10 // 0b1010

  "Bitwise" should "OR: 12 | 10 = 14" in {
    test(new Bitwise) { dut =>
      dut.io.regY.data.poke(u64(Y))
      dut.io.regZ.data.poke(u64(Z))
      setOp(dut, OR, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(orExp(Y, Z)))
      dut.io.result.dest.expect(1.U)
    }
  }

  "Bitwise" should "AND: 12 & 10 = 8" in {
    test(new Bitwise) { dut =>
      dut.io.regY.data.poke(u64(Y))
      dut.io.regZ.data.poke(u64(Z))
      setOp(dut, AND, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(andExp(Y, Z)))
    }
  }

  "Bitwise" should "XOR: 12 ^ 10 = 6" in {
    test(new Bitwise) { dut =>
      dut.io.regY.data.poke(u64(Y))
      dut.io.regZ.data.poke(u64(Z))
      setOp(dut, XOR, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(xorExp(Y, Z)))
    }
  }

  "Bitwise" should "NOR: ~(12 | 10)" in {
    test(new Bitwise) { dut =>
      dut.io.regY.data.poke(u64(Y))
      dut.io.regZ.data.poke(u64(Z))
      setOp(dut, NOR, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(norExp(Y, Z)))
    }
  }

  "Bitwise" should "NAND: ~(12 & 10)" in {
    test(new Bitwise) { dut =>
      dut.io.regY.data.poke(u64(Y))
      dut.io.regZ.data.poke(u64(Z))
      setOp(dut, NAND, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(nandExp(Y, Z)))
    }
  }

  "Bitwise" should "NXOR: ~(12 ^ 10)" in {
    test(new Bitwise) { dut =>
      dut.io.regY.data.poke(u64(Y))
      dut.io.regZ.data.poke(u64(Z))
      setOp(dut, NXOR, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(nxorExp(Y, Z)))
    }
  }

  "Bitwise" should "ORN: 12 | ~10" in {
    test(new Bitwise) { dut =>
      dut.io.regY.data.poke(u64(Y))
      dut.io.regZ.data.poke(u64(Z))
      setOp(dut, ORN, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(ornExp(Y, Z)))
    }
  }

  "Bitwise" should "ANDN: 12 & ~10 (Y\\Z 차집합)" in {
    test(new Bitwise) { dut =>
      dut.io.regY.data.poke(u64(Y))
      dut.io.regZ.data.poke(u64(Z))
      setOp(dut, ANDN, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(andnExp(Y, Z)))
    }
  }

  // ORI $X,$Y,Z — Z 즉치. 레지스터 모드와 같은 결과가 나와야 한다.
  "Bitwise" should "ORI: 12 | imm(10) = OR 레지스터 모드와 동일" in {
    test(new Bitwise) { dut =>
      dut.io.regY.data.poke(u64(Y))
      setOp(dut, ORI, 1, 2, 10)
      dut.clock.step()
      dut.io.result.res.expect(u64(orExp(Y, Z)))
    }
  }

  // OR $X,$Y,0 은 SET $X,$Y의 축약형 — $X ← $Y 그대로
  "Bitwise" should "ORI: Z=0(즉치)이면 SET처럼 Y를 그대로 복사" in {
    test(new Bitwise) { dut =>
      val y = BigInt("123456789ABCDEF0", 16)
      dut.io.regY.data.poke(u64(y))
      setOp(dut, ORI, 1, 2, 0)
      dut.clock.step()
      dut.io.result.res.expect(u64(y))
    }
  }

  "Bitwise" should "pause=1이면 이전 값 유지" in {
    test(new Bitwise) { dut =>
      dut.io.regY.data.poke(u64(Y))
      dut.io.regZ.data.poke(u64(Z))
      setOp(dut, OR, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(orExp(Y, Z)))

      dut.io.pause.poke(true.B)
      dut.io.regY.data.poke(u64(99))
      dut.io.regZ.data.poke(u64(1))
      dut.clock.step()
      dut.io.result.res.expect(u64(orExp(Y, Z))) // 이전 값 유지
    }
  }
}
