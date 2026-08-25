package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/*
 * flag[1]=unsign(0=I64,1=U64), flag[0]=imm.
 * MUL=0x00 MULI=0x01 MULU=0x02 MULUI=0x03
 */

/** Mul을 감싸서 레지스터버스 쪽 신호(ack/data)를 평평한 IO로 노출하는 테스트용 하네스.
 * reg는 이제 Mul 자신의 io 필드라 한 단계만 통과하면 되므로 여기서 그냥 := 로 이어준다.
 * RegArbiter가 아직 목업이라 실제 버스 대신 여기서 직접 ack/data를 준다.
 */
class MulHarness extends Module {
  val io = IO(new Bundle {
    val op        = Input(MulOp())
    val pause     = Input(Bool())
    val regYData  = Input(UInt(64.W))
    val regYAck   = Input(Bool())
    val regZData  = Input(UInt(64.W))
    val regZAck   = Input(Bool())
    val result    = Output(MulResult())
  })

  val arbiter = Module(new RegArbiter)
  val factory = new RegReadPortFactory(arbiter)
  val mul     = Module(new Mul(factory))

  mul.io.op          := io.op
  mul.io.pause       := io.pause
  mul.io.reg.x.data  := 0.U    // Mul은 X(목적지)를 읽지 않음 — req가 항상 0이라 값은 안 쓰임
  mul.io.reg.x.ack   := false.B
  mul.io.reg.y.data  := io.regYData
  mul.io.reg.y.ack   := io.regYAck
  mul.io.reg.z.data  := io.regZData
  mul.io.reg.z.ack   := io.regZAck
  io.result := mul.io.result
}

class MulTest extends AnyFlatSpec with ChiselScalatestTester {

  val MUL = 0x00; val MULI = 0x01; val MULU = 0x02; val MULUI = 0x03

  def setOp(dut: MulHarness, f: Int, x: Int, y: Int, z: Int): Unit = {
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

  /** 실제 버스 왕복을 2단계로 흉내낸다 — 1클럭째는 op(주소)만 보이고 버스는 아직 응답 안 함,
   * 2클럭째에야 버스가 ack+data로 응답한다. 그래서 답은 2번째 step 이후에나 준비된다.
   */
  def issue(dut: MulHarness, f: Int, x: Int, y: Int, z: Int, yData: BigInt, zData: BigInt): Unit = {
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

  "Mul" should "MUL: 6 * 7 = 42, ovf=false, writeH=false" in {
    test(new MulHarness) { dut =>
      issue(dut, MUL, 1, 2, 3, 6, 7)
      dut.io.result.res.expect(u64(42))
      dut.io.result.ovf.expect(false.B)
      dut.io.result.writeH.expect(false.B)
      dut.io.result.dest.expect(1.U)
    }
  }

  "Mul" should "MUL: -3 * 4 = -12 (부호있는 작은 음수, 오버플로우 아님)" in {
    test(new MulHarness) { dut =>
      val (res, himult, ovf) = mulSigned(-3, 4)
      issue(dut, MUL, 1, 2, 3, -3, 4)
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B)
    }
  }

  "Mul" should "MUL: MAX * 1 = MAX → ovf=false (경계)" in {
    test(new MulHarness) { dut =>
      val (res, _, ovf) = mulSigned(I64_MAX, 1)
      issue(dut, MUL, 1, 2, 3, I64_MAX, 1)
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B) // false
    }
  }

  "Mul" should "MUL: MAX * 2 → ovf=true (경계 바로 너머)" in {
    test(new MulHarness) { dut =>
      val (res, _, ovf) = mulSigned(I64_MAX, 2)
      issue(dut, MUL, 1, 2, 3, I64_MAX, 2)
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B) // true
    }
  }

  "Mul" should "MUL: MIN * -1 → ovf=true (부호있는 곱셈의 대표적 오버플로우)" in {
    test(new MulHarness) { dut =>
      val (res, _, ovf) = mulSigned(I64_MIN, -1)
      issue(dut, MUL, 1, 2, 3, I64_MIN, -1)
      dut.io.result.res.expect(u64(res))
      dut.io.result.ovf.expect(ovf.B) // true
    }
  }

  "Mul" should "MULI: 6 * imm(7) = 42, 즉치/레지스터 모드 동일 결과" in {
    test(new MulHarness) { dut =>
      issue(dut, MULI, 1, 2, 7, 6, 0)
      dut.io.result.res.expect(u64(42))
    }
  }

  "Mul" should "MULU: (-1의 비트패턴, 즉 2^64-1) * 2 → himult=1, 오버플로우 개념 없음(항상 ovf=false), writeH=true" in {
    test(new MulHarness) { dut =>
      val (res, himult) = mulUnsigned(MASK64, 2)
      issue(dut, MULU, 1, 2, 3, -1, 2)
      dut.io.result.res.expect(u64(res))
      dut.io.result.himult.expect(u64(himult))
      dut.io.result.writeH.expect(true.B)
      dut.io.result.ovf.expect(false.B)
    }
  }

  "Mul" should "MULUI: 즉치 모드도 MULU와 동일하게 himult/writeH 계산" in {
    test(new MulHarness) { dut =>
      val (res, himult) = mulUnsigned(MASK64, 2)
      issue(dut, MULUI, 1, 2, 2, -1, 0)
      dut.io.result.res.expect(u64(res))
      dut.io.result.himult.expect(u64(himult))
      dut.io.result.writeH.expect(true.B)
    }
  }

  "Mul" should "pause=1이면 이전 값 유지" in {
    test(new MulHarness) { dut =>
      issue(dut, MUL, 1, 2, 3, 6, 7)
      dut.io.result.res.expect(u64(42))

      dut.io.pause.poke(true.B)
      dut.io.regYData.poke(u64(99))
      dut.io.regZData.poke(u64(1))
      dut.clock.step()
      dut.io.result.res.expect(u64(42))
    }
  }

  "Mul" should "레지스터 응답 온 뒤에도 바깥 pause가 몇 클럭 더 유지되면, 풀렸을 때 재요청 없이 바로 이어져야 한다" in {
    test(new MulHarness) { dut =>
      // 1클럭째: 요청만 나감(op 나타남, 버스 응답 없음)
      dut.io.regYAck.poke(false.B)
      dut.io.regZAck.poke(false.B)
      setOp(dut, MUL, 1, 2, 3)
      dut.clock.step()

      // 2클럭째: 버스는 응답(ack+data)하지만, 동시에 바깥 pause도 걸려있음
      dut.io.regYData.poke(u64(6))
      dut.io.regZData.poke(u64(7))
      dut.io.regYAck.poke(true.B)
      dut.io.regZAck.poke(true.B)
      dut.io.pause.poke(true.B)
      dut.clock.step()

      // 버스 ack는 한 클럭만 유지(실제 버스처럼 펄스) — 바깥 pause 때문에 아직 못 받았을 수 있음
      dut.io.regYAck.poke(false.B)
      dut.io.regZAck.poke(false.B)

      // 바깥 pause가 2~3클럭 더 유지된 뒤에 풀림
      dut.clock.step()
      dut.clock.step()
      dut.io.pause.poke(false.B)
      dut.clock.step()

      // 이미 받았던 값(6*7=42)이 재요청 없이 바로 나와야 한다
      dut.io.result.res.expect(u64(42))
    }
  }

  "Mul" should "명령 3개를 순차로 넣으면 경합 없어도 명령당 2클럭(요청+응답)씩 걸린다" in {
    test(new MulHarness) { dut =>
      issue(dut, MUL, 1, 2, 3, 2, 3)
      dut.io.result.res.expect(u64(6))
      dut.io.result.dest.expect(1.U)

      issue(dut, MUL, 4, 5, 6, 5, 6)
      dut.io.result.res.expect(u64(30))
      dut.io.result.dest.expect(4.U)

      issue(dut, MUL, 7, 8, 9, 8, 9)
      dut.io.result.res.expect(u64(72))
      dut.io.result.dest.expect(7.U)
    }
  }
}
