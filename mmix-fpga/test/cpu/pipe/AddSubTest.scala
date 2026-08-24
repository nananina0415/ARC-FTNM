package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class AddSubTest extends AnyFlatSpec with ChiselScalatestTester {

  // 플래그 헬퍼 — package.scala 상수와 동일한 규칙
  def flag(f: Int): UInt = f.U(5.W)

  // 파이프 입력 설정 헬퍼
  def setOp(dut: AddSub, f: Int, x: Int, y: Int, z: Int): Unit = {
    dut.io.op.flag.poke(f.U)
    dut.io.op.x.poke(x.U)
    dut.io.op.y.poke(y.U)
    dut.io.op.z.poke(z.U)
    dut.io.pause.poke(false.B)
  }

  // ADD $X,$Y,$Z — 부호있는 덧셈
  "AddSub" should "ADD: 3 + 5 = 8" in {
    test(new AddSub) { dut =>
      dut.io.regY.data.poke(3.U)
      dut.io.regZ.data.poke(5.U)
      setOp(dut, 0x00, 1, 2, 3)  // ADD|I64, x=$1, y=$2, z=$3
      dut.clock.step()            // y_buf, acc 래치
      dut.io.result.res.expect(8.U)
      dut.io.result.ovf.expect(false.B)
      dut.io.result.dest.expect(1.U)
    }
  }

  "AddSub" should "ADD: 오버플로우 감지 (max + 1)" in {
    test(new AddSub) { dut =>
      val maxI64 = "h7fffffffffffffff".U(64.W)
      dut.io.regY.data.poke(maxI64)
      dut.io.regZ.data.poke(1.U)
      setOp(dut, 0x00, 0, 1, 2)
      dut.clock.step()
      dut.io.result.ovf.expect(true.B)
      dut.io.result.ovf_trap.expect(true.B)  // ADD는 부호있음 → 트랩
    }
  }

  // ADDU $X,$Y,$Z — 부호없는 덧셈 (flag[1]=1)
  "AddSub" should "ADDU: 오버플로우 트랩 없음" in {
    test(new AddSub) { dut =>
      val maxU64 = "hffffffffffffffff".U(64.W)
      dut.io.regY.data.poke(maxU64)
      dut.io.regZ.data.poke(1.U)
      setOp(dut, 0x02, 0, 1, 2)  // ADD|U64
      dut.clock.step()
      dut.io.result.carry.expect(true.B)
      dut.io.result.ovf_trap.expect(false.B)  // ADDU → 트랩 없음
    }
  }

  // ADDUI $X,$Y,Z — 부호없는 덧셈 + Z 즉치
  "AddSub" should "ADDUI: 10 + imm(5) = 15, 트랩 없음" in {
    test(new AddSub) { dut =>
      dut.io.regY.data.poke(10.U)
      setOp(dut, 0x03, 0, 1, 5)  // ADD|U64|IMM
      dut.clock.step()
      dut.io.result.res.expect(15.U)
      dut.io.result.ovf_trap.expect(false.B)
    }
  }

  // SUB $X,$Y,$Z — 부호있는 뺄셈 (flag[2]=1)
  "AddSub" should "SUB: 10 - 3 = 7" in {
    test(new AddSub) { dut =>
      dut.io.regY.data.poke(10.U)
      dut.io.regZ.data.poke(3.U)
      setOp(dut, 0x04, 0, 1, 2)  // SUB|I64
      dut.clock.step()
      dut.io.result.res.expect(7.U)
      dut.io.result.ovf.expect(false.B)
    }
  }

  "AddSub" should "SUB: MIN - 1 → 오버플로우 (부호 다른 두 수, 결과 부호가 Y와 달라짐)" in {
    test(new AddSub) { dut =>
      val minI64 = "h8000000000000000".U(64.W)
      val maxI64 = "h7fffffffffffffff".U(64.W)
      dut.io.regY.data.poke(minI64)
      dut.io.regZ.data.poke(1.U)
      setOp(dut, 0x04, 0, 1, 2)
      dut.clock.step()
      dut.io.result.res.expect(maxI64)
      dut.io.result.ovf.expect(true.B)
      dut.io.result.ovf_trap.expect(true.B)
    }
  }

  "AddSub" should "SUB: Y≥Z(부호없음 기준) → carry=true(borrow 없음)" in {
    test(new AddSub) { dut =>
      dut.io.regY.data.poke(10.U)
      dut.io.regZ.data.poke(3.U)
      setOp(dut, 0x04, 0, 1, 2)
      dut.clock.step()
      dut.io.result.carry.expect(true.B)
    }
  }

  "AddSub" should "SUB: Y<Z(부호없음 기준) → carry=false(borrow 발생), 3-5 = -2" in {
    test(new AddSub) { dut =>
      dut.io.regY.data.poke(3.U)
      dut.io.regZ.data.poke(5.U)
      setOp(dut, 0x04, 0, 1, 2)
      dut.clock.step()
      dut.io.result.res.expect("hfffffffffffffffe".U(64.W))
      dut.io.result.carry.expect(false.B)
      dut.io.result.ovf.expect(false.B)  // 둘 다 양수라 오버플로우 아님
    }
  }

  // SUBI $X,$Y,Z — Z 즉치 (flag[0]=1)
  "AddSub" should "SUBI: 10 - imm(3) = 7" in {
    test(new AddSub) { dut =>
      dut.io.regY.data.poke(10.U)
      setOp(dut, 0x05, 0, 1, 3)  // SUB|I64|IMM
      dut.clock.step()
      dut.io.result.res.expect(7.U)
    }
  }

  // SUBU $X,$Y,$Z — 부호없는 뺄셈 (flag[1]=1) — 오버플로우 조건이어도 트랩 없음
  "AddSub" should "SUBU: MIN - 1 → 결과는 SUB와 동일하지만 트랩 없음" in {
    test(new AddSub) { dut =>
      val minI64 = "h8000000000000000".U(64.W)
      val maxI64 = "h7fffffffffffffff".U(64.W)
      dut.io.regY.data.poke(minI64)
      dut.io.regZ.data.poke(1.U)
      setOp(dut, 0x06, 0, 1, 2)  // SUB|U64
      dut.clock.step()
      dut.io.result.res.expect(maxI64)
      dut.io.result.ovf_trap.expect(false.B)
    }
  }

  // SUBUI $X,$Y,Z — 부호없는 뺄셈 + Z 즉치
  "AddSub" should "SUBUI: 10 - imm(3) = 7, 트랩 없음" in {
    test(new AddSub) { dut =>
      dut.io.regY.data.poke(10.U)
      setOp(dut, 0x07, 0, 1, 3)  // SUB|U64|IMM
      dut.clock.step()
      dut.io.result.res.expect(7.U)
      dut.io.result.ovf_trap.expect(false.B)
    }
  }

  // NEG $X,Y,$Z — Y는 즉치 (flag[3]=1 → y_imm, flag[2]=0 → c0도 1)
  "AddSub" should "NEG: 5 - $Z(3) = 2" in {
    test(new AddSub) { dut =>
      dut.io.regZ.data.poke(3.U)
      // NEG|I64: flag = 0b01000 = 0x08
      setOp(dut, 0x08, 0, 5, 3)  // x=$0, y=5(즉치), z=$3
      dut.clock.step()
      dut.io.result.res.expect(2.U)
    }
  }

  "AddSub" should "NEG: Y=0, $Z=MIN → 오버플로우 (문서에 명시된 특수 조건)" in {
    test(new AddSub) { dut =>
      val minI64 = "h8000000000000000".U(64.W)
      dut.io.regZ.data.poke(minI64)
      setOp(dut, 0x08, 0, 0, 3)  // NEG|I64, Y=0
      dut.clock.step()
      dut.io.result.res.expect(minI64)
      dut.io.result.ovf.expect(true.B)
      dut.io.result.ovf_trap.expect(true.B)
    }
  }

  // NEGI $X,Y,Z — Y,Z 둘 다 즉치 (flag[3]=1, flag[0]=1)
  "AddSub" should "NEGI: 5 - imm(3) = 2" in {
    test(new AddSub) { dut =>
      setOp(dut, 0x09, 0, 5, 3)  // NEG|I64|IMM
      dut.clock.step()
      dut.io.result.res.expect(2.U)
    }
  }

  // NEGU $X,Y,$Z — 부호없는 negate, 오버플로우 조건이어도 트랩 없음
  "AddSub" should "NEGU: Y=0, $Z=MIN → 결과는 NEG와 동일하지만 트랩 없음" in {
    test(new AddSub) { dut =>
      val minI64 = "h8000000000000000".U(64.W)
      dut.io.regZ.data.poke(minI64)
      setOp(dut, 0x0A, 0, 0, 3)  // NEG|U64
      dut.clock.step()
      dut.io.result.res.expect(minI64)
      dut.io.result.ovf_trap.expect(false.B)
    }
  }

  // NEGUI $X,Y,Z — 부호없는 negate + Z 즉치
  "AddSub" should "NEGUI: 5 - imm(3) = 2, 트랩 없음" in {
    test(new AddSub) { dut =>
      setOp(dut, 0x0B, 0, 5, 3)  // NEG|U64|IMM
      dut.clock.step()
      dut.io.result.res.expect(2.U)
      dut.io.result.ovf_trap.expect(false.B)
    }
  }

  // 2ADDU $X,$Y,$Z — 2*$Y + $Z (flag = 0x12)
  "AddSub" should "2ADDU: 2*4 + 3 = 11" in {
    test(new AddSub) { dut =>
      dut.io.regY.data.poke(4.U)
      dut.io.regZ.data.poke(3.U)
      setOp(dut, 0x12, 0, 1, 2)  // SHIFT2ADDU bits[4:0] = 10010
      dut.clock.step()
      dut.io.result.res.expect(11.U)
      dut.io.result.ovf_trap.expect(false.B)
    }
  }

  // 4ADDU: flag bits[4:0] = 10110 = 0x16
  "AddSub" should "4ADDU: 4*3 + 2 = 14" in {
    test(new AddSub) { dut =>
      dut.io.regY.data.poke(3.U)
      dut.io.regZ.data.poke(2.U)
      setOp(dut, 0x16, 0, 1, 2)
      dut.clock.step()
      dut.io.result.res.expect(14.U)
    }
  }

  // 2ADDUI/4ADDUI: Z 즉치 버전 (flag[0]=1)
  "AddSub" should "2ADDUI: 2*4 + imm(3) = 11" in {
    test(new AddSub) { dut =>
      dut.io.regY.data.poke(4.U)
      setOp(dut, 0x13, 0, 1, 3)
      dut.clock.step()
      dut.io.result.res.expect(11.U)
    }
  }

  "AddSub" should "4ADDUI: 4*3 + imm(2) = 14" in {
    test(new AddSub) { dut =>
      dut.io.regY.data.poke(3.U)
      setOp(dut, 0x17, 0, 1, 2)
      dut.clock.step()
      dut.io.result.res.expect(14.U)
    }
  }

  // 8ADDU: flag bits[4:0] = 11010 = 0x1A
  "AddSub" should "8ADDU: 8*3 + 2 = 26" in {
    test(new AddSub) { dut =>
      dut.io.regY.data.poke(3.U)
      dut.io.regZ.data.poke(2.U)
      setOp(dut, 0x1A, 0, 1, 2)
      dut.clock.step()
      dut.io.result.res.expect(26.U)
    }
  }

  "AddSub" should "8ADDUI: 8*3 + imm(2) = 26" in {
    test(new AddSub) { dut =>
      dut.io.regY.data.poke(3.U)
      setOp(dut, 0x1B, 0, 1, 2)
      dut.clock.step()
      dut.io.result.res.expect(26.U)
    }
  }

  // 16ADDU: flag bits[4:0] = 11110 = 0x1E
  "AddSub" should "16ADDU: 16*2 + 5 = 37" in {
    test(new AddSub) { dut =>
      dut.io.regY.data.poke(2.U)
      dut.io.regZ.data.poke(5.U)
      setOp(dut, 0x1E, 0, 1, 2)
      dut.clock.step()
      dut.io.result.res.expect(37.U)
    }
  }

  "AddSub" should "16ADDUI: 16*2 + imm(5) = 37" in {
    test(new AddSub) { dut =>
      dut.io.regY.data.poke(2.U)
      setOp(dut, 0x1F, 0, 1, 5)
      dut.clock.step()
      dut.io.result.res.expect(37.U)
    }
  }

  "AddSub" should "16ADDU: 큰 값이 mod 2^64로 랩어라운드해도 트랩 없음 (flag[1]=1 항상)" in {
    test(new AddSub) { dut =>
      val maxI64 = "h7fffffffffffffff".U(64.W)
      dut.io.regY.data.poke(maxI64)
      dut.io.regZ.data.poke(0.U)
      setOp(dut, 0x1E, 0, 1, 2)
      dut.clock.step()
      dut.io.result.res.expect("hfffffffffffffff0".U(64.W)) // 16*MAX mod 2^64
      dut.io.result.ovf_trap.expect(false.B)
    }
  }

  // Z 즉치 (flag[0]=1)
  "AddSub" should "ADDI: $Y + imm(10) = 15" in {
    test(new AddSub) { dut =>
      dut.io.regY.data.poke(5.U)
      setOp(dut, 0x01, 0, 1, 10)  // ADD|I64|IMM, z=10(즉치)
      dut.clock.step()
      dut.io.result.res.expect(15.U)
    }
  }

  // pause=1이면 버퍼 홀드
  "AddSub" should "pause=1이면 이전 값 유지" in {
    test(new AddSub) { dut =>
      dut.io.regY.data.poke(7.U)
      dut.io.regZ.data.poke(3.U)
      setOp(dut, 0x00, 0, 1, 2)
      dut.clock.step()                     // 7+3=10 래치
      dut.io.result.res.expect(10.U)

      // pause=1, 입력 바꿔도 버퍼 유지
      dut.io.pause.poke(true.B)
      dut.io.regY.data.poke(100.U)
      dut.io.regZ.data.poke(200.U)
      dut.clock.step()
      dut.io.result.res.expect(10.U)       // 이전 값 유지
    }
  }
}
