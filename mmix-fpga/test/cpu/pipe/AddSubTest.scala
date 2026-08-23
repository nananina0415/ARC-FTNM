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
