package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/*
 * flag(bit2=방향, bit1=unsign, bit0=imm): SL=0x00 SLI=0x01 SLU=0x02 SLUI=0x03
 *                                          SR=0x04 SRI=0x05 SRU=0x06 SRUI=0x07
 */

/** Shift를 감싸서 레지스터버스 쪽 신호(ack/data)를 평평한 IO로 노출하는 테스트용 하네스.
 * RegArbiter가 아직 목업이라 실제 버스 대신 여기서 직접 ack/data를 준다.
 */
class ShiftHarness extends Module {
  val io = IO(new Bundle {
    val op        = Input(ShiftOp())
    val pause     = Input(Bool())
    val regYData  = Input(UInt(64.W))
    val regYAck   = Input(Bool())
    val regZData  = Input(UInt(64.W))
    val regZAck   = Input(Bool())
    val result    = Output(ShiftResult())
  })

  val arbiter = Module(new RegArbiter)
  val factory = new RegReadPortFactory(arbiter)
  val shift   = Module(new Shift(factory))

  shift.io.op          := io.op
  shift.io.pause       := io.pause
  shift.io.reg.x.data  := 0.U   // Shift는 X(목적지)를 읽지 않음 — req가 항상 0이라 값은 안 쓰임
  shift.io.reg.x.ack   := false.B
  shift.io.reg.y.data  := io.regYData
  shift.io.reg.y.ack   := io.regYAck
  shift.io.reg.z.data  := io.regZData
  shift.io.reg.z.ack   := io.regZAck
  io.result := shift.io.result
}

class ShiftTest extends AnyFlatSpec with ChiselScalatestTester {

  val SL   = 0x00; val SLI  = 0x01; val SLU  = 0x02; val SLUI = 0x03
  val SR   = 0x04; val SRI  = 0x05; val SRU  = 0x06; val SRUI = 0x07

  def setOp(dut: ShiftHarness, f: Int, x: Int, y: Int, z: Int): Unit = {
    dut.io.op.flag.poke(f.U)
    dut.io.op.x.poke(x.U)
    dut.io.op.y.poke(y.U)
    dut.io.op.z.poke(z.U)
    dut.io.pause.poke(false.B)
  }

  // 64비트 부호있는 값을 U(64.W)로 포크하기 위한 헬퍼 (음수는 2의 보수 비트패턴으로)
  def u64(v: BigInt): UInt = (v & ((BigInt(1) << 64) - 1)).U(64.W)

  val TWO64: BigInt = BigInt(1) << 64
  val I64_MAX: BigInt = (BigInt(1) << 63) - 1
  val I64_MIN: BigInt = -(BigInt(1) << 63)

  // SL 기대값: (2의 보수 64비트 결과, 오버플로우 여부) — sh>=64 특수 케이스도 자동으로 맞음
  def slExpected(y: BigInt, sh: Int): (BigInt, Boolean) = {
    val shifted = y << sh
    val fits = shifted >= I64_MIN && shifted <= I64_MAX
    var trunc = shifted & (TWO64 - 1)
    if (trunc >= (BigInt(1) << 63)) trunc -= TWO64
    (trunc, !fits)
  }

  def sluExpected(y: BigInt, sh: Int): BigInt = slExpected(y, sh)._1

  def srExpected(y: BigInt, sh: Int): BigInt =
    if (sh >= 64) { if (y >= 0) BigInt(0) else BigInt(-1) } else y >> sh

  def sruExpected(y: BigInt, sh: Int): BigInt =
    if (sh >= 64) BigInt(0) else ((y & (TWO64 - 1)) >> sh)

  /** 실제 버스 왕복을 2단계로 흉내낸다 — 1클럭째는 op(주소)만 보이고 버스는 아직 응답 안 함,
   * 2클럭째에야 버스가 ack+data로 응답한다. 그래서 답은 2번째 step 이후에나 준비된다.
   * 즉치 모드에선 regZAck/regZData는 무시되니 아무 값이나 넘겨도 무해하다.
   */
  def issue(dut: ShiftHarness, f: Int, x: Int, y: Int, z: Int, yData: BigInt, zData: BigInt): Unit = {
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

  // ── A. SL — 정상값 ──
  "Shift" should "SL: 3 << 2 = 12, ovf=false" in {
    test(new ShiftHarness) { dut =>
      issue(dut, SL, 1, 2, 5, 3, 2) // 레지스터 모드, z=5(시프트량 레지스터 번호)
      dut.io.result.res.expect(u64(12))
      dut.io.result.ovf.expect(false.B)
      dut.io.result.dest.expect(1.U)
    }
  }

  // ── B. SL — is_z_big 경계 (62,63,64,65,255) ──
  "Shift" should "SL: sh=63 (정상 경로 마지막) — y=1, 결과 = -2^63, ovf=false" in {
    test(new ShiftHarness) { dut =>
      val (res, ovf) = slExpected(1, 63)
      issue(dut, SLI, 1, 2, 63, 1, 0) // 즉치 모드, z=63이 시프트량 그 자체
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B)
    }
  }

  "Shift" should "SL: sh=64 (빅시프트 시작) — y=1(≠0) → 결과 0, ovf=true" in {
    test(new ShiftHarness) { dut =>
      issue(dut, SL, 1, 2, 5, 1, 64)
      dut.io.result.res.expect(u64(0))
      dut.io.result.ovf.expect(true.B)
    }
  }

  "Shift" should "SL: sh=64, y=0 → 결과 0, ovf=false" in {
    test(new ShiftHarness) { dut =>
      issue(dut, SL, 1, 2, 5, 0, 64)
      dut.io.result.res.expect(u64(0))
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Shift" should "SL: sh=255 (8비트 즉치 최댓값, 이상값) — y=1 → 결과 0, ovf=true" in {
    test(new ShiftHarness) { dut =>
      issue(dut, SLI, 1, 2, 255, 1, 0)
      dut.io.result.res.expect(u64(0))
      dut.io.result.ovf.expect(true.B)
    }
  }

  "Shift" should "SL: sh=2^64-1 (레지스터 모드 전용 이상값) — y=1 → 결과 0, ovf=true" in {
    test(new ShiftHarness) { dut =>
      issue(dut, SL, 1, 2, 5, 1, TWO64 - 1)
      dut.io.result.res.expect(u64(0))
      dut.io.result.ovf.expect(true.B)
    }
  }

  // ── C. SL — ovf_mask 내부 경계 (오버플로우 검출 정밀 검증) ──
  Seq(0, 1, 32, 63).foreach { sh =>
    "Shift" should s"SL: y=-1(전부 1비트), sh=$sh → ovf=false (부호와 동일한 밀림 비트는 안전)" in {
      test(new ShiftHarness) { dut =>
        val (res, ovf) = slExpected(-1, sh)
        issue(dut, SLI, 1, 2, sh, -1, 0)
        dut.io.result.res.expect(u64(res))
        dut.io.result.ovf.expect(ovf.B) // 항상 false여야 함
      }
    }
  }

  "Shift" should "SL: y=INT64_MAX, sh=0 → ovf=false" in {
    test(new ShiftHarness) { dut =>
      issue(dut, SLI, 1, 2, 0, I64_MAX, 0)
      dut.io.result.res.expect(u64(I64_MAX))
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Shift" should "SL: y=INT64_MAX, sh=1 → ovf=true" in {
    test(new ShiftHarness) { dut =>
      val (res, ovf) = slExpected(I64_MAX, 1)
      issue(dut, SLI, 1, 2, 1, I64_MAX, 0)
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B) // true
    }
  }

  "Shift" should "SL: y=INT64_MIN, sh=0 → ovf=false" in {
    test(new ShiftHarness) { dut =>
      issue(dut, SLI, 1, 2, 0, I64_MIN, 0)
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Shift" should "SL: y=INT64_MIN, sh=1 → ovf=true" in {
    test(new ShiftHarness) { dut =>
      val (res, ovf) = slExpected(I64_MIN, 1)
      issue(dut, SLI, 1, 2, 1, I64_MIN, 0)
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B) // true
    }
  }

  "Shift" should "SL: y=1(bit0만 1), sh=62 → ovf=false (마스크는 상위 63비트[1..63]만 봐야 함)" in {
    test(new ShiftHarness) { dut =>
      val (res, ovf) = slExpected(1, 62)
      issue(dut, SLI, 1, 2, 62, 1, 0)
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B) // false
    }
  }

  // ── D. SLU — 오버플로우 검사 없음 ──
  "Shift" should "SLU: y=0xFFFF...FFFF, sh=4 → 오버플로우 검사 없이 그대로 시프트, ovf=false 고정" in {
    test(new ShiftHarness) { dut =>
      val res = sluExpected(-1, 4)
      issue(dut, SLUI, 1, 2, 4, -1, 0)
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Shift" should "SLU: sh=64 이상이어도 ovf=false 고정 (SL과 달리 오버플로우 개념 자체가 없음)" in {
    test(new ShiftHarness) { dut =>
      issue(dut, SLU, 1, 2, 5, 1, 64)
      dut.io.result.res.expect(u64(0))
      dut.io.result.ovf.expect(false.B)
    }
  }

  // ── E. SR/SRU — 오버플로우 절대 없음 확인 ──
  "Shift" should "SR: y=INT64_MIN, sh=64(빅시프트) → 결과 -1, ovf=false" in {
    test(new ShiftHarness) { dut =>
      issue(dut, SR, 1, 2, 5, I64_MIN, 64)
      dut.io.result.res.expect(u64(-1))
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Shift" should "SR: y=5(양수), sh=64(빅시프트) → 결과 0, ovf=false" in {
    test(new ShiftHarness) { dut =>
      issue(dut, SR, 1, 2, 5, 5, 64)
      dut.io.result.res.expect(u64(0))
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Shift" should "SR: sh=63(정상 경로 최대) → ovf=false 고정" in {
    test(new ShiftHarness) { dut =>
      val res = srExpected(I64_MIN, 63)
      issue(dut, SRI, 1, 2, 63, I64_MIN, 0)
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Shift" should "SRU: sh=64(빅시프트) → 부호 무관 항상 결과 0, ovf=false" in {
    test(new ShiftHarness) { dut =>
      issue(dut, SRU, 1, 2, 5, I64_MIN, 64)
      dut.io.result.res.expect(u64(0))
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Shift" should "SRU: sh=63(정상 경로 최대) — 논리 시프트, ovf=false" in {
    test(new ShiftHarness) { dut =>
      val res = sruExpected(-1, 63)
      issue(dut, SRUI, 1, 2, 63, -1, 0)
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(false.B)
    }
  }

  // ── F. IMM vs 레지스터 모드 동등성 ──
  "Shift" should "SL/SLI: 같은 시프트를 즉치/레지스터 모드로 하면 결과가 같다" in {
    val (res, ovf) = slExpected(7, 5)

    test(new ShiftHarness) { dut =>
      issue(dut, SLI, 1, 2, 5, 7, 0) // 즉치
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B)
    }

    test(new ShiftHarness) { dut =>
      issue(dut, SL, 1, 2, 6, 7, 5) // 레지스터, z=6은 임의의 레지스터 번호
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B)
    }
  }

  // ── G. pause=1 홀드 ──
  "Shift" should "pause=1이면 이전 값 유지" in {
    test(new ShiftHarness) { dut =>
      issue(dut, SLI, 1, 2, 2, 3, 0) // 3<<2=12 래치
      dut.io.result.res.expect(u64(12))

      dut.io.pause.poke(true.B)
      dut.io.regYData.poke(u64(99))
      setOp(dut, SLI, 1, 2, 3)
      dut.io.pause.poke(true.B)
      dut.clock.step()
      dut.io.result.res.expect(u64(12)) // 이전 값 유지
    }
  }
}
