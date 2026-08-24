package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/*
 * flag[1]=unsign(0=I64,1=U64), flag[0]=imm.
 * MUL=0x00 MULI=0x01 MULU=0x02 MULUI=0x03
 */
class MulTest extends AnyFlatSpec with ChiselScalatestTester {

  val MUL = 0x00; val MULI = 0x01; val MULU = 0x02; val MULUI = 0x03

  def setOp(dut: Mul, f: Int, x: Int, y: Int, z: Int): Unit = {
    dut.io.op.flag.poke(f.U)
    dut.io.op.x.poke(x.U)
    dut.io.op.y.poke(y.U)
    dut.io.op.z.poke(z.U)
    dut.io.pause.poke(false.B)
  }

  val MASK64: BigInt  = (BigInt(1) << 64) - 1
  val MASK65: BigInt  = (BigInt(1) << 65) - 1
  val MASK128: BigInt = (BigInt(1) << 128) - 1
  val I64_MAX: BigInt = (BigInt(1) << 63) - 1
  val I64_MIN: BigInt = -(BigInt(1) << 63)

  def u64(v: BigInt): UInt = (v & MASK64).U(64.W)

  // golden model — 설계와 동일한 방식(128비트 곱의 상위65비트가 전부 같은지)으로 오버플로우 계산
  def mulSigned(y: BigInt, z: BigInt): (BigInt, BigInt, Boolean) = {
    val prod   = (y * z) & MASK128
    val res    = prod & MASK64
    val himult = (prod >> 64) & MASK64
    val top65  = (prod >> 63) & MASK65
    val ovf    = !(top65 == 0 || top65 == MASK65)
    (res, himult, ovf)
  }

  def mulUnsigned(y: BigInt, z: BigInt): (BigInt, BigInt) = {
    val prod = (y & MASK64) * (z & MASK64)
    (prod & MASK64, (prod >> 64) & MASK64)
  }

  "Mul" should "MUL: 6 * 7 = 42, ovf=false, writeH=false" in {
    test(new Mul) { dut =>
      dut.io.regY.data.poke(u64(6))
      dut.io.regZ.data.poke(u64(7))
      setOp(dut, MUL, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(42))
      dut.io.result.ovf.expect(false.B)
      dut.io.result.writeH.expect(false.B)
      dut.io.result.dest.expect(1.U)
    }
  }

  "Mul" should "MUL: -3 * 4 = -12 (부호있는 작은 음수, 오버플로우 아님)" in {
    test(new Mul) { dut =>
      val (res, himult, ovf) = mulSigned(-3, 4)
      dut.io.regY.data.poke(u64(-3))
      dut.io.regZ.data.poke(u64(4))
      setOp(dut, MUL, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B)
    }
  }

  "Mul" should "MUL: MAX * 1 = MAX → ovf=false (경계)" in {
    test(new Mul) { dut =>
      val (res, _, ovf) = mulSigned(I64_MAX, 1)
      dut.io.regY.data.poke(u64(I64_MAX))
      dut.io.regZ.data.poke(u64(1))
      setOp(dut, MUL, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B) // false
    }
  }

  "Mul" should "MUL: MAX * 2 → ovf=true (경계 바로 너머)" in {
    test(new Mul) { dut =>
      val (res, _, ovf) = mulSigned(I64_MAX, 2)
      dut.io.regY.data.poke(u64(I64_MAX))
      dut.io.regZ.data.poke(u64(2))
      setOp(dut, MUL, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B) // true
    }
  }

  "Mul" should "MUL: MIN * -1 → ovf=true (부호있는 곱셈의 대표적 오버플로우)" in {
    test(new Mul) { dut =>
      val (res, _, ovf) = mulSigned(I64_MIN, -1)
      dut.io.regY.data.poke(u64(I64_MIN))
      dut.io.regZ.data.poke(u64(-1))
      setOp(dut, MUL, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B) // true
    }
  }

  "Mul" should "MULI: 6 * imm(7) = 42, 즉치/레지스터 모드 동일 결과" in {
    test(new Mul) { dut =>
      dut.io.regY.data.poke(u64(6))
      setOp(dut, MULI, 1, 2, 7)
      dut.clock.step()
      dut.io.result.res.expect(u64(42))
    }
  }

  "Mul" should "MULU: (-1의 비트패턴, 즉 2^64-1) * 2 → himult=1, 오버플로우 개념 없음(항상 ovf=false), writeH=true" in {
    test(new Mul) { dut =>
      val (res, himult) = mulUnsigned(MASK64, 2)
      dut.io.regY.data.poke(u64(-1))
      dut.io.regZ.data.poke(u64(2))
      setOp(dut, MULU, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(res))
      dut.io.result.himult.expect(u64(himult))
      dut.io.result.writeH.expect(true.B)
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Mul" should "MULUI: 즉치 모드도 MULU와 동일하게 himult/writeH 계산" in {
    test(new Mul) { dut =>
      val (res, himult) = mulUnsigned(MASK64, 2)
      dut.io.regY.data.poke(u64(-1))
      setOp(dut, MULUI, 1, 2, 2)
      dut.clock.step()
      dut.io.result.res.expect(u64(res))
      dut.io.result.himult.expect(u64(himult))
      dut.io.result.writeH.expect(true.B)
    }
  }

  "Mul" should "pause=1이면 이전 값 유지" in {
    test(new Mul) { dut =>
      dut.io.regY.data.poke(u64(6))
      dut.io.regZ.data.poke(u64(7))
      setOp(dut, MUL, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(42))

      dut.io.pause.poke(true.B)
      dut.io.regY.data.poke(u64(99))
      dut.io.regZ.data.poke(u64(1))
      dut.clock.step()
      dut.io.result.res.expect(u64(42))
    }
  }
}
