package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/*
 * flag(bit2=방향, bit1=unsign, bit0=imm): SL=0x00 SLI=0x01 SLU=0x02 SLUI=0x03
 *                                          SR=0x04 SRI=0x05 SRU=0x06 SRUI=0x07
 */
class ShiftTest extends AnyFlatSpec with ChiselScalatestTester {

  val SL   = 0x00; val SLI  = 0x01; val SLU  = 0x02; val SLUI = 0x03
  val SR   = 0x04; val SRI  = 0x05; val SRU  = 0x06; val SRUI = 0x07

  def setOp(dut: Shift, f: Int, x: Int, y: Int, z: Int): Unit = {
    dut.io.op.flag.poke(f.U)
    dut.io.op.x.poke(x.U)
    dut.io.op.y.poke(y.U)
    dut.io.op.z.poke(z.U)
    dut.io.pause.poke(false.B)
  }

  // 64비트 부호있는 값을 U(64.W)로 포크하기 위한 헬퍼 (음수는 2의 보수 비트패턴으로)
  def u64(v: Long): UInt = (BigInt(v) & ((BigInt(1) << 64) - 1)).U(64.W)

  val TWO64: BigInt = BigInt(1) << 64
  val I64_MAX: BigInt = (BigInt(1) << 63) - 1
  val I64_MIN: BigInt = -(BigInt(1) << 63)

  // SL 기대값: (2의 보수 64비트 결과, 오버플로우 여부) — sh>=64 특수 케이스도 자동으로 맞음
  def slExpected(y: Long, sh: Int): (Long, Boolean) = {
    val shifted = BigInt(y) << sh
    val fits = shifted >= I64_MIN && shifted <= I64_MAX
    var trunc = shifted & (TWO64 - 1)
    if (trunc >= (BigInt(1) << 63)) trunc -= TWO64
    (trunc.toLong, !fits)
  }

  def sluExpected(y: Long, sh: Int): Long = slExpected(y, sh)._1

  def srExpected(y: Long, sh: Int): Long =
    if (sh >= 64) { if (y >= 0) 0L else -1L } else y >> sh

  def sruExpected(y: Long, sh: Int): Long =
    if (sh >= 64) 0L else y >>> sh

  // ── A. SL — 정상값 ──
  "Shift" should "SL: 3 << 2 = 12, ovf=false" in {
    test(new Shift) { dut =>
      dut.io.regY.data.poke(u64(3))
      setOp(dut, SL, 1, 2, 5) // 레지스터 모드, z=5(시프트량 레지스터 번호)
      dut.io.regZ.data.poke(u64(2))
      dut.clock.step()
      dut.io.result.res.expect(u64(12))
      dut.io.result.ovf.expect(false.B)
      dut.io.result.dest.expect(1.U)
    }
  }

  // ── B. SL — is_z_big 경계 (62,63,64,65,255) ──
  "Shift" should "SL: sh=63 (정상 경로 마지막) — y=1, 결과 = -2^63, ovf=false" in {
    test(new Shift) { dut =>
      val (res, ovf) = slExpected(1L, 63)
      dut.io.regY.data.poke(u64(1))
      setOp(dut, SLI, 1, 2, 63) // 즉치 모드, z=63이 시프트량 그 자체
      dut.clock.step()
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B)
    }
  }

  "Shift" should "SL: sh=64 (빅시프트 시작) — y=1(≠0) → 결과 0, ovf=true" in {
    test(new Shift) { dut =>
      dut.io.regY.data.poke(u64(1))
      setOp(dut, SL, 1, 2, 5)
      dut.io.regZ.data.poke(u64(64))
      dut.clock.step()
      dut.io.result.res.expect(u64(0))
      dut.io.result.ovf.expect(true.B)
    }
  }

  "Shift" should "SL: sh=64, y=0 → 결과 0, ovf=false" in {
    test(new Shift) { dut =>
      dut.io.regY.data.poke(u64(0))
      setOp(dut, SL, 1, 2, 5)
      dut.io.regZ.data.poke(u64(64))
      dut.clock.step()
      dut.io.result.res.expect(u64(0))
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Shift" should "SL: sh=255 (8비트 즉치 최댓값, 이상값) — y=1 → 결과 0, ovf=true" in {
    test(new Shift) { dut =>
      dut.io.regY.data.poke(u64(1))
      setOp(dut, SLI, 1, 2, 255)
      dut.clock.step()
      dut.io.result.res.expect(u64(0))
      dut.io.result.ovf.expect(true.B)
    }
  }

  "Shift" should "SL: sh=2^64-1 (레지스터 모드 전용 이상값) — y=1 → 결과 0, ovf=true" in {
    test(new Shift) { dut =>
      dut.io.regY.data.poke(u64(1))
      setOp(dut, SL, 1, 2, 5)
      dut.io.regZ.data.poke((TWO64 - 1).U(64.W))
      dut.clock.step()
      dut.io.result.res.expect(u64(0))
      dut.io.result.ovf.expect(true.B)
    }
  }

  // ── C. SL — ovf_mask 내부 경계 (오버플로우 검출 정밀 검증) ──
  Seq(0, 1, 32, 63).foreach { sh =>
    "Shift" should s"SL: y=-1(전부 1비트), sh=$sh → ovf=false (부호와 동일한 밀림 비트는 안전)" in {
      test(new Shift) { dut =>
        val (res, ovf) = slExpected(-1L, sh)
        dut.io.regY.data.poke(u64(-1L))
        setOp(dut, SLI, 1, 2, sh)
        dut.clock.step()
        dut.io.result.res.expect(u64(res))
        dut.io.result.ovf.expect(ovf.B) // 항상 false여야 함
      }
    }
  }

  "Shift" should "SL: y=INT64_MAX, sh=0 → ovf=false" in {
    test(new Shift) { dut =>
      dut.io.regY.data.poke(I64_MAX.U(64.W))
      setOp(dut, SLI, 1, 2, 0)
      dut.clock.step()
      dut.io.result.res.expect(I64_MAX.U(64.W))
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Shift" should "SL: y=INT64_MAX, sh=1 → ovf=true" in {
    test(new Shift) { dut =>
      val (res, ovf) = slExpected(Long.MaxValue, 1)
      dut.io.regY.data.poke(I64_MAX.U(64.W))
      setOp(dut, SLI, 1, 2, 1)
      dut.clock.step()
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B) // true
    }
  }

  "Shift" should "SL: y=INT64_MIN, sh=0 → ovf=false" in {
    test(new Shift) { dut =>
      dut.io.regY.data.poke((I64_MIN & (TWO64 - 1)).U(64.W))
      setOp(dut, SLI, 1, 2, 0)
      dut.clock.step()
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Shift" should "SL: y=INT64_MIN, sh=1 → ovf=true" in {
    test(new Shift) { dut =>
      val (res, ovf) = slExpected(Long.MinValue, 1)
      dut.io.regY.data.poke((I64_MIN & (TWO64 - 1)).U(64.W))
      setOp(dut, SLI, 1, 2, 1)
      dut.clock.step()
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B) // true
    }
  }

  "Shift" should "SL: y=1(bit0만 1), sh=62 → ovf=false (마스크는 상위 63비트[1..63]만 봐야 함)" in {
    test(new Shift) { dut =>
      val (res, ovf) = slExpected(1L, 62)
      dut.io.regY.data.poke(u64(1))
      setOp(dut, SLI, 1, 2, 62)
      dut.clock.step()
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B) // false
    }
  }

  // ── D. SLU — 오버플로우 검사 없음 ──
  "Shift" should "SLU: y=0xFFFF...FFFF, sh=4 → 오버플로우 검사 없이 그대로 시프트, ovf=false 고정" in {
    test(new Shift) { dut =>
      val res = sluExpected(-1L, 4)
      dut.io.regY.data.poke(u64(-1L))
      setOp(dut, SLUI, 1, 2, 4)
      dut.clock.step()
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Shift" should "SLU: sh=64 이상이어도 ovf=false 고정 (SL과 달리 오버플로우 개념 자체가 없음)" in {
    test(new Shift) { dut =>
      dut.io.regY.data.poke(u64(1))
      setOp(dut, SLU, 1, 2, 5)
      dut.io.regZ.data.poke(u64(64))
      dut.clock.step()
      dut.io.result.res.expect(u64(0))
      dut.io.result.ovf.expect(false.B)
    }
  }

  // ── E. SR/SRU — 오버플로우 절대 없음 확인 ──
  "Shift" should "SR: y=INT64_MIN, sh=64(빅시프트) → 결과 -1, ovf=false" in {
    test(new Shift) { dut =>
      dut.io.regY.data.poke((I64_MIN & (TWO64 - 1)).U(64.W))
      setOp(dut, SR, 1, 2, 5)
      dut.io.regZ.data.poke(u64(64))
      dut.clock.step()
      dut.io.result.res.expect(u64(-1L))
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Shift" should "SR: y=5(양수), sh=64(빅시프트) → 결과 0, ovf=false" in {
    test(new Shift) { dut =>
      dut.io.regY.data.poke(u64(5))
      setOp(dut, SR, 1, 2, 5)
      dut.io.regZ.data.poke(u64(64))
      dut.clock.step()
      dut.io.result.res.expect(u64(0))
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Shift" should "SR: sh=63(정상 경로 최대) → ovf=false 고정" in {
    test(new Shift) { dut =>
      val res = srExpected(Long.MinValue, 63)
      dut.io.regY.data.poke((I64_MIN & (TWO64 - 1)).U(64.W))
      setOp(dut, SRI, 1, 2, 63)
      dut.clock.step()
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Shift" should "SRU: sh=64(빅시프트) → 부호 무관 항상 결과 0, ovf=false" in {
    test(new Shift) { dut =>
      dut.io.regY.data.poke((I64_MIN & (TWO64 - 1)).U(64.W))
      setOp(dut, SRU, 1, 2, 5)
      dut.io.regZ.data.poke(u64(64))
      dut.clock.step()
      dut.io.result.res.expect(u64(0))
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Shift" should "SRU: sh=63(정상 경로 최대) — 논리 시프트, ovf=false" in {
    test(new Shift) { dut =>
      val res = sruExpected(-1L, 63)
      dut.io.regY.data.poke(u64(-1L))
      setOp(dut, SRUI, 1, 2, 63)
      dut.clock.step()
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(false.B)
    }
  }

  // ── F. IMM vs 레지스터 모드 동등성 ──
  "Shift" should "SL/SLI: 같은 시프트를 즉치/레지스터 모드로 하면 결과가 같다" in {
    val (res, ovf) = slExpected(7L, 5)

    test(new Shift) { dut =>
      dut.io.regY.data.poke(u64(7))
      setOp(dut, SLI, 1, 2, 5) // 즉치
      dut.clock.step()
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B)
    }

    test(new Shift) { dut =>
      dut.io.regY.data.poke(u64(7))
      setOp(dut, SL, 1, 2, 6) // 레지스터, z=6은 임의의 레지스터 번호
      dut.io.regZ.data.poke(u64(5))
      dut.clock.step()
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B)
    }
  }

  // ── G. pause=1 홀드 ──
  "Shift" should "pause=1이면 이전 값 유지" in {
    test(new Shift) { dut =>
      dut.io.regY.data.poke(u64(3))
      setOp(dut, SLI, 1, 2, 2)
      dut.clock.step() // 3<<2=12 래치
      dut.io.result.res.expect(u64(12))

      dut.io.pause.poke(true.B)
      dut.io.regY.data.poke(u64(99))
      setOp(dut, SLI, 1, 2, 3)
      dut.io.pause.poke(true.B)
      dut.clock.step()
      dut.io.result.res.expect(u64(12)) // 이전 값 유지
    }
  }
}
