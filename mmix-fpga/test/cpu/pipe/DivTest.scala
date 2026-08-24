package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/*
 * flag[1]=unsign(0=I64,1=U64), flag[0]=imm.
 * DIV=0x00 DIVI=0x01 DIVU=0x02 DIVUI=0x03
 * rD는 DIVU가 128비트 피제수(rD:Y)를 만들 때 쓰는 특수 레지스터 입력.
 */
class DivTest extends AnyFlatSpec with ChiselScalatestTester {

  val DIV = 0x00; val DIVI = 0x01; val DIVU = 0x02; val DIVUI = 0x03

  def setOp(dut: Div, f: Int, x: Int, y: Int, z: Int): Unit = {
    dut.io.op.flag.poke(f.U)
    dut.io.op.x.poke(x.U)
    dut.io.op.y.poke(y.U)
    dut.io.op.z.poke(z.U)
    dut.io.pause.poke(false.B)
    dut.io.rD.data.poke(0.U)
  }

  val MASK64: BigInt  = (BigInt(1) << 64) - 1
  val I64_MIN: BigInt = -(BigInt(1) << 63)

  def u64(v: BigInt): UInt = (v & MASK64).U(64.W)

  // golden model — 설계와 동일: 절삭 나눗셈을 구한 뒤 floor로 보정
  def divSigned(y: BigInt, z: BigInt): (BigInt, BigInt, Boolean, Boolean) = {
    if (z == 0) {
      (BigInt(0), y & MASK64, false, true)
    } else {
      val tq = y / z
      val tr = y - tq * z
      val needAdjust = tr != 0 && ((tr < 0) != (z < 0))
      val q = if (needAdjust) tq - 1 else tq
      val r = if (needAdjust) tr + z else tr
      val ovf = y == I64_MIN && z == BigInt(-1)
      (q & MASK64, r & MASK64, ovf, false)
    }
  }

  def divUnsigned(rD: BigInt, y: BigInt, z: BigInt): (BigInt, BigInt) = {
    if (rD >= z) {
      (rD & MASK64, y & MASK64)
    } else {
      val dividend = (rD << 64) | y
      ((dividend / z) & MASK64, (dividend % z) & MASK64)
    }
  }

  "Div" should "DIV: 7 / 2 = 3 나머지 1" in {
    test(new Div) { dut =>
      dut.io.regY.data.poke(u64(7))
      dut.io.regZ.data.poke(u64(2))
      setOp(dut, DIV, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(3))
      dut.io.result.remainder.expect(u64(1))
      dut.io.result.dest.expect(1.U)
      dut.io.result.ovf.expect(false.B)
      dut.io.result.divCheck.expect(false.B)
    }
  }

  "Div" should "DIV: -7 / 2 → 내림 몫 -4, 나머지 +1 (절삭이었으면 -3/-1)" in {
    test(new Div) { dut =>
      val (q, r, _, _) = divSigned(-7, 2)
      dut.io.regY.data.poke(u64(-7))
      dut.io.regZ.data.poke(u64(2))
      setOp(dut, DIV, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(q)) // -4
      dut.io.result.remainder.expect(u64(r)) // 1
    }
  }

  "Div" should "DIV: 7 / -2 → 몫 -4, 나머지 -1 (나머지가 제수 부호를 따름)" in {
    test(new Div) { dut =>
      val (q, r, _, _) = divSigned(7, -2)
      dut.io.regY.data.poke(u64(7))
      dut.io.regZ.data.poke(u64(-2))
      setOp(dut, DIV, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(q))
      dut.io.result.remainder.expect(u64(r))
    }
  }

  "Div" should "DIV: -7 / -2 → 몫 3, 나머지 -1 (같은 부호끼리는 절삭/내림이 같음)" in {
    test(new Div) { dut =>
      val (q, r, _, _) = divSigned(-7, -2)
      dut.io.regY.data.poke(u64(-7))
      dut.io.regZ.data.poke(u64(-2))
      setOp(dut, DIV, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(q))
      dut.io.result.remainder.expect(u64(r))
    }
  }

  "Div" should "DIV: 5 / 0 → divCheck=true, res=0, remainder=Y" in {
    test(new Div) { dut =>
      dut.io.regY.data.poke(u64(5))
      dut.io.regZ.data.poke(u64(0))
      setOp(dut, DIV, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(0))
      dut.io.result.remainder.expect(u64(5))
      dut.io.result.divCheck.expect(true.B)
    }
  }

  "Div" should "DIV: MIN / -1 → ovf=true (유일한 오버플로우 케이스)" in {
    test(new Div) { dut =>
      val (q, r, ovf, _) = divSigned(I64_MIN, -1)
      dut.io.regY.data.poke(u64(I64_MIN))
      dut.io.regZ.data.poke(u64(-1))
      setOp(dut, DIV, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(q))
      dut.io.result.remainder.expect(u64(r))
      dut.io.result.ovf.expect(ovf.B) // true
    }
  }

  "Div" should "DIVI: 7 / imm(2) = 3 나머지 1" in {
    test(new Div) { dut =>
      dut.io.regY.data.poke(u64(7))
      setOp(dut, DIVI, 1, 2, 2)
      dut.clock.step()
      dut.io.result.res.expect(u64(3))
      dut.io.result.remainder.expect(u64(1))
    }
  }

  "Div" should "DIVU: rD=0(단순 64비트 나눗셈) — 17 / 5 = 3 나머지 2" in {
    test(new Div) { dut =>
      val (q, r) = divUnsigned(0, 17, 5)
      dut.io.regY.data.poke(u64(17))
      dut.io.regZ.data.poke(u64(5))
      setOp(dut, DIVU, 1, 2, 3)
      dut.io.rD.data.poke(0.U)
      dut.clock.step()
      dut.io.result.res.expect(u64(q))
      dut.io.result.remainder.expect(u64(r))
    }
  }

  "Div" should "DIVU: rD >= Z → 몫이 64비트를 못 담아서 X←rD, rR←Y (오버플로우 아님)" in {
    test(new Div) { dut =>
      dut.io.regY.data.poke(u64(42))
      dut.io.regZ.data.poke(u64(3))
      setOp(dut, DIVU, 1, 2, 3)
      dut.io.rD.data.poke(u64(5))
      dut.clock.step()
      dut.io.result.res.expect(u64(5))
      dut.io.result.remainder.expect(u64(42))
      dut.io.result.ovf.expect(false.B)
      dut.io.result.divCheck.expect(false.B)
    }
  }

  "Div" should "DIVU: 0으로 나누기 — rD(0) >= Z(0)라 예외 없이 X←rD(0), rR←Y" in {
    test(new Div) { dut =>
      dut.io.regY.data.poke(u64(99))
      dut.io.regZ.data.poke(u64(0))
      setOp(dut, DIVU, 1, 2, 3)
      dut.io.rD.data.poke(0.U)
      dut.clock.step()
      dut.io.result.res.expect(u64(0))
      dut.io.result.remainder.expect(u64(99))
      dut.io.result.divCheck.expect(false.B) // 부호없음은 예외 자체가 없음
    }
  }

  "Div" should "DIVU: rD가 0이 아닌 128비트 피제수 — (1<<64 | 0) / 3" in {
    test(new Div) { dut =>
      val (q, r) = divUnsigned(1, 0, 3)
      dut.io.regY.data.poke(u64(0))
      dut.io.regZ.data.poke(u64(3))
      setOp(dut, DIVU, 1, 2, 3)
      dut.io.rD.data.poke(u64(1))
      dut.clock.step()
      dut.io.result.res.expect(u64(q))
      dut.io.result.remainder.expect(u64(r))
    }
  }

  "Div" should "pause=1이면 이전 값 유지" in {
    test(new Div) { dut =>
      dut.io.regY.data.poke(u64(7))
      dut.io.regZ.data.poke(u64(2))
      setOp(dut, DIV, 1, 2, 3)
      dut.clock.step()
      dut.io.result.res.expect(u64(3))

      dut.io.pause.poke(true.B)
      dut.io.regY.data.poke(u64(99))
      dut.io.regZ.data.poke(u64(1))
      dut.clock.step()
      dut.io.result.res.expect(u64(3))
    }
  }
}
