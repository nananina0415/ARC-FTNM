package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/*
 * flag[1]=unsign(0=I64,1=U64), flag[0]=imm.
 * DIV=0x00 DIVI=0x01 DIVU=0x02 DIVUI=0x03
 * rD는 DIVU가 128비트 피제수(rD:Y)를 만들 때 쓰는 특수 레지스터 입력.
 */

/** Div를 감싸서 레지스터버스 쪽 신호(ack/data)를 평평한 IO로 노출하는 테스트용 하네스.
 * RegArbiter가 아직 목업이라 실제 버스 대신 여기서 직접 ack/data를 준다.
 */
class DivHarness extends Module {
  val io = IO(new Bundle {
    val op        = Input(DivOp())
    val pause     = Input(Bool())
    val rDData    = Input(UInt(64.W))
    val rDAck     = Input(Bool())
    val regYData  = Input(UInt(64.W))
    val regYAck   = Input(Bool())
    val regZData  = Input(UInt(64.W))
    val regZAck   = Input(Bool())
    val result    = Output(DivResult())
  })

  val arbiter        = Module(new RegArbiter)
  val factory         = new RegReadPortFactory(arbiter)
  val specialFactory   = new SpecialRegReadPortFactory
  val div             = Module(new Div(factory, specialFactory))

  div.io.op         := io.op
  div.io.pause      := io.pause
  div.io.rD.data    := io.rDData
  div.io.rD.ack     := io.rDAck
  div.io.reg.x.data := 0.U   // Div는 X(목적지)를 읽지 않음 — req가 항상 0이라 값은 안 쓰임
  div.io.reg.x.ack  := false.B
  div.io.reg.y.data := io.regYData
  div.io.reg.y.ack  := io.regYAck
  div.io.reg.z.data := io.regZData
  div.io.reg.z.ack  := io.regZAck
  io.result := div.io.result
}

class DivTest extends AnyFlatSpec with ChiselScalatestTester {

  val DIV = 0x00; val DIVI = 0x01; val DIVU = 0x02; val DIVUI = 0x03

  def setOp(dut: DivHarness, f: Int, x: Int, y: Int, z: Int): Unit = {
    dut.io.op.flag.poke(f.U)
    dut.io.op.x.poke(x.U)
    dut.io.op.y.poke(y.U)
    dut.io.op.z.poke(z.U)
    dut.io.pause.poke(false.B)
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

  /** 실제 버스 왕복을 2단계로 흉내낸다 — 1클럭째는 op(주소)만 보이고 버스는 아직 응답 안 함,
   * 2클럭째에야 버스가 ack+data로 응답한다. 그래서 답은 2번째 step 이후에나 준비된다.
   * rD도 이제 자기만의 작은 중재기를 거치는 요청-대기 구조라 Y/Z와 똑같이 ack를 걸어줘야
   * 한다(부호있는 DIV는 애초에 rD를 요청 안 해서 이 ack가 있으나 없으나 무해하다).
   */
  def issue(dut: DivHarness, f: Int, x: Int, y: Int, z: Int, yData: BigInt, zData: BigInt, rD: BigInt = 0): Unit = {
    dut.io.rDAck.poke(false.B)
    dut.io.regYAck.poke(false.B)
    dut.io.regZAck.poke(false.B)
    setOp(dut, f, x, y, z)
    dut.clock.step()

    dut.io.rDData.poke(u64(rD))
    dut.io.regYData.poke(u64(yData))
    dut.io.regZData.poke(u64(zData))
    dut.io.rDAck.poke(true.B)
    dut.io.regYAck.poke(true.B)
    dut.io.regZAck.poke(true.B)
    dut.clock.step()
  }

  "Div" should "DIV: 7 / 2 = 3 나머지 1" in {
    test(new DivHarness) { dut =>
      issue(dut, DIV, 1, 2, 3, 7, 2)
      dut.io.result.res.expect(u64(3))
      dut.io.result.remainder.expect(u64(1))
      dut.io.result.dest.expect(1.U)
      dut.io.result.ovf.expect(false.B)
      dut.io.result.divCheck.expect(false.B)
    }
  }

  "Div" should "DIV: -7 / 2 → 내림 몫 -4, 나머지 +1 (절삭이었으면 -3/-1)" in {
    test(new DivHarness) { dut =>
      val (q, r, _, _) = divSigned(-7, 2)
      issue(dut, DIV, 1, 2, 3, -7, 2)
      dut.io.result.res.expect(u64(q)) // -4
      dut.io.result.remainder.expect(u64(r)) // 1
    }
  }

  "Div" should "DIV: 7 / -2 → 몫 -4, 나머지 -1 (나머지가 제수 부호를 따름)" in {
    test(new DivHarness) { dut =>
      val (q, r, _, _) = divSigned(7, -2)
      issue(dut, DIV, 1, 2, 3, 7, -2)
      dut.io.result.res.expect(u64(q))
      dut.io.result.remainder.expect(u64(r))
    }
  }

  "Div" should "DIV: -7 / -2 → 몫 3, 나머지 -1 (같은 부호끼리는 절삭/내림이 같음)" in {
    test(new DivHarness) { dut =>
      val (q, r, _, _) = divSigned(-7, -2)
      issue(dut, DIV, 1, 2, 3, -7, -2)
      dut.io.result.res.expect(u64(q))
      dut.io.result.remainder.expect(u64(r))
    }
  }

  "Div" should "DIV: 5 / 0 → divCheck=true, res=0, remainder=Y" in {
    test(new DivHarness) { dut =>
      issue(dut, DIV, 1, 2, 3, 5, 0)
      dut.io.result.res.expect(u64(0))
      dut.io.result.remainder.expect(u64(5))
      dut.io.result.divCheck.expect(true.B)
    }
  }

  "Div" should "DIV: MIN / -1 → ovf=true (유일한 오버플로우 케이스)" in {
    test(new DivHarness) { dut =>
      val (q, r, ovf, _) = divSigned(I64_MIN, -1)
      issue(dut, DIV, 1, 2, 3, I64_MIN, -1)
      dut.io.result.res.expect(u64(q))
      dut.io.result.remainder.expect(u64(r))
      dut.io.result.ovf.expect(ovf.B) // true
    }
  }

  "Div" should "DIVI: 7 / imm(2) = 3 나머지 1" in {
    test(new DivHarness) { dut =>
      issue(dut, DIVI, 1, 2, 2, 7, 0)
      dut.io.result.res.expect(u64(3))
      dut.io.result.remainder.expect(u64(1))
    }
  }

  "Div" should "DIVU: rD=0(단순 64비트 나눗셈) — 17 / 5 = 3 나머지 2" in {
    test(new DivHarness) { dut =>
      val (q, r) = divUnsigned(0, 17, 5)
      issue(dut, DIVU, 1, 2, 3, 17, 5, rD = 0)
      dut.io.result.res.expect(u64(q))
      dut.io.result.remainder.expect(u64(r))
    }
  }

  "Div" should "DIVU: rD >= Z → 몫이 64비트를 못 담아서 X←rD, rR←Y (오버플로우 아님)" in {
    test(new DivHarness) { dut =>
      issue(dut, DIVU, 1, 2, 3, 42, 3, rD = 5)
      dut.io.result.res.expect(u64(5))
      dut.io.result.remainder.expect(u64(42))
      dut.io.result.ovf.expect(false.B)
      dut.io.result.divCheck.expect(false.B)
    }
  }

  "Div" should "DIVU: 0으로 나누기 — rD(0) >= Z(0)라 예외 없이 X←rD(0), rR←Y" in {
    test(new DivHarness) { dut =>
      issue(dut, DIVU, 1, 2, 3, 99, 0, rD = 0)
      dut.io.result.res.expect(u64(0))
      dut.io.result.remainder.expect(u64(99))
      dut.io.result.divCheck.expect(false.B) // 부호없음은 예외 자체가 없음
    }
  }

  "Div" should "DIVU: rD가 0이 아닌 128비트 피제수 — (1<<64 | 0) / 3" in {
    test(new DivHarness) { dut =>
      val (q, r) = divUnsigned(1, 0, 3)
      issue(dut, DIVU, 1, 2, 3, 0, 3, rD = 1)
      dut.io.result.res.expect(u64(q))
      dut.io.result.remainder.expect(u64(r))
    }
  }

  "Div" should "DIVU: Y/Z가 먼저 응답해도 rD를 기다리는 동안 재요청 없이 버티다가, rD 도착하면 바로 이어진다" in {
    test(new DivHarness) { dut =>
      val (q, r) = divUnsigned(1, 0, 3)

      // 1클럭째: op만 나타남, 아무도 응답 안 함
      dut.io.rDAck.poke(false.B)
      dut.io.regYAck.poke(false.B)
      dut.io.regZAck.poke(false.B)
      setOp(dut, DIVU, 1, 2, 3)
      dut.clock.step()

      // 2클럭째: Y/Z는 응답하지만 rD는 아직 응답 안 함(rD 쪽 중재기가 더 느린 상황을 흉내)
      dut.io.regYData.poke(u64(0))
      dut.io.regZData.poke(u64(3))
      dut.io.regYAck.poke(true.B)
      dut.io.regZAck.poke(true.B)
      dut.clock.step()

      // Y/Z ack는 한 클럭만 유지(실제 버스처럼 펄스)
      dut.io.regYAck.poke(false.B)
      dut.io.regZAck.poke(false.B)

      // rD가 2~3클럭 더 늦게 응답 — 그 동안 Y/Z가 재요청(재무장)하면 안 됨
      dut.clock.step()
      dut.clock.step()

      // 이제서야 rD가 응답
      dut.io.rDData.poke(u64(1))
      dut.io.rDAck.poke(true.B)
      dut.clock.step()

      dut.io.result.res.expect(u64(q))
      dut.io.result.remainder.expect(u64(r))
    }
  }

  "Div" should "pause=1이면 이전 값 유지" in {
    test(new DivHarness) { dut =>
      issue(dut, DIV, 1, 2, 3, 7, 2)
      dut.io.result.res.expect(u64(3))

      dut.io.pause.poke(true.B)
      dut.io.regYData.poke(u64(99))
      dut.io.regZData.poke(u64(1))
      dut.clock.step()
      dut.io.result.res.expect(u64(3))
    }
  }
}
