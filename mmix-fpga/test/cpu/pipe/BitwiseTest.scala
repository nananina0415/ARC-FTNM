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

/** Bitwise를 감싸서 레지스터버스 쪽 신호(ack/data)를 평평한 IO로 노출하는 테스트용 하네스.
 * RegArbiter가 아직 목업이라 실제 버스 대신 여기서 직접 ack/data를 준다.
 */
class BitwiseHarness extends Module {
  val io = IO(new Bundle {
    val op        = Input(BitwiseOp())
    val pause     = Input(Bool())
    val regYData  = Input(UInt(64.W))
    val regYAck   = Input(Bool())
    val regZData  = Input(UInt(64.W))
    val regZAck   = Input(Bool())
    val result    = Output(BitwiseResult())
  })

  val arbiter = Module(new RegArbiter)
  val factory = new RegReadPortFactory(arbiter)
  val bitwise = Module(new Bitwise(factory))

  bitwise.io.op         := io.op
  bitwise.io.pause      := io.pause
  bitwise.io.reg.x.data := 0.U   // Bitwise는 X(목적지)를 읽지 않음 — req가 항상 0이라 값은 안 쓰임
  bitwise.io.reg.x.ack  := false.B
  bitwise.io.reg.y.data := io.regYData
  bitwise.io.reg.y.ack  := io.regYAck
  bitwise.io.reg.z.data := io.regZData
  bitwise.io.reg.z.ack  := io.regZAck
  io.result := bitwise.io.result
}

class BitwiseTest extends AnyFlatSpec with ChiselScalatestTester {

  val NOR = 0x0; val NORI  = 0x1; val NAND = 0x2; val NANDI = 0x3
  val XOR = 0x4; val XORI  = 0x5; val NXOR = 0x6; val NXORI = 0x7
  val OR  = 0x8; val ORI   = 0x9; val ORN  = 0xA; val ORNI  = 0xB
  val AND = 0xC; val ANDI  = 0xD; val ANDN = 0xE; val ANDNI = 0xF

  def setOp(dut: BitwiseHarness, f: Int, x: Int, y: Int, z: Int): Unit = {
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

  /** 실제 버스 왕복을 2단계로 흉내낸다 — 1클럭째는 op(주소)만 보이고 버스는 아직 응답 안 함,
   * 2클럭째에야 버스가 ack+data로 응답한다. 그래서 답은 2번째 step 이후에나 준비된다.
   * 즉치 모드에선 regZAck/regZData는 무시되니 아무 값이나 넘겨도 무해하다.
   */
  def issue(dut: BitwiseHarness, f: Int, x: Int, y: Int, z: Int, yData: BigInt, zData: BigInt): Unit = {
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

  "Bitwise" should "OR: 12 | 10 = 14" in {
    test(new BitwiseHarness) { dut =>
      issue(dut, OR, 1, 2, 3, Y, Z)
      dut.io.result.res.expect(u64(orExp(Y, Z)))
      dut.io.result.dest.expect(1.U)
    }
  }

  "Bitwise" should "AND: 12 & 10 = 8" in {
    test(new BitwiseHarness) { dut =>
      issue(dut, AND, 1, 2, 3, Y, Z)
      dut.io.result.res.expect(u64(andExp(Y, Z)))
    }
  }

  "Bitwise" should "XOR: 12 ^ 10 = 6" in {
    test(new BitwiseHarness) { dut =>
      issue(dut, XOR, 1, 2, 3, Y, Z)
      dut.io.result.res.expect(u64(xorExp(Y, Z)))
    }
  }

  "Bitwise" should "NOR: ~(12 | 10)" in {
    test(new BitwiseHarness) { dut =>
      issue(dut, NOR, 1, 2, 3, Y, Z)
      dut.io.result.res.expect(u64(norExp(Y, Z)))
    }
  }

  "Bitwise" should "NAND: ~(12 & 10)" in {
    test(new BitwiseHarness) { dut =>
      issue(dut, NAND, 1, 2, 3, Y, Z)
      dut.io.result.res.expect(u64(nandExp(Y, Z)))
    }
  }

  "Bitwise" should "NXOR: ~(12 ^ 10)" in {
    test(new BitwiseHarness) { dut =>
      issue(dut, NXOR, 1, 2, 3, Y, Z)
      dut.io.result.res.expect(u64(nxorExp(Y, Z)))
    }
  }

  "Bitwise" should "ORN: 12 | ~10" in {
    test(new BitwiseHarness) { dut =>
      issue(dut, ORN, 1, 2, 3, Y, Z)
      dut.io.result.res.expect(u64(ornExp(Y, Z)))
    }
  }

  "Bitwise" should "ANDN: 12 & ~10 (Y\\Z 차집합)" in {
    test(new BitwiseHarness) { dut =>
      issue(dut, ANDN, 1, 2, 3, Y, Z)
      dut.io.result.res.expect(u64(andnExp(Y, Z)))
    }
  }

  // ORI $X,$Y,Z — Z 즉치. 레지스터 모드와 같은 결과가 나와야 한다.
  "Bitwise" should "ORI: 12 | imm(10) = OR 레지스터 모드와 동일" in {
    test(new BitwiseHarness) { dut =>
      issue(dut, ORI, 1, 2, 10, Y, 0)
      dut.io.result.res.expect(u64(orExp(Y, Z)))
    }
  }

  // OR $X,$Y,0 은 SET $X,$Y의 축약형 — $X ← $Y 그대로
  "Bitwise" should "ORI: Z=0(즉치)이면 SET처럼 Y를 그대로 복사" in {
    test(new BitwiseHarness) { dut =>
      val y = BigInt("123456789ABCDEF0", 16)
      issue(dut, ORI, 1, 2, 0, y, 0)
      dut.io.result.res.expect(u64(y))
    }
  }

  "Bitwise" should "pause=1이면 이전 값 유지" in {
    test(new BitwiseHarness) { dut =>
      issue(dut, OR, 1, 2, 3, Y, Z)
      dut.io.result.res.expect(u64(orExp(Y, Z)))

      dut.io.pause.poke(true.B)
      dut.io.regYData.poke(u64(99))
      dut.io.regZData.poke(u64(1))
      dut.clock.step()
      dut.io.result.res.expect(u64(orExp(Y, Z))) // 이전 값 유지
    }
  }
}
