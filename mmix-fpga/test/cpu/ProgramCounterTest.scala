package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class ProgramCounterTest extends AnyFlatSpec with ChiselScalatestTester {

  val STAY  = 0
  val COUNT = 1
  val SET   = 2
  val CLEAR = 3

  "ProgramCounter" should "초기값은 0" in {
    test(new ProgramCounter) { dut =>
      dut.mode.poke(STAY.U)
      dut.count.expect(0.U)
    }
  }

  "ProgramCounter" should "mode=count면 매 클럭 4씩 증가" in {
    test(new ProgramCounter) { dut =>
      dut.mode.poke(COUNT.U)
      dut.count.expect(0.U)
      dut.clock.step()
      dut.count.expect(4.U)
      dut.clock.step()
      dut.count.expect(8.U)
      dut.clock.step()
      dut.count.expect(12.U)
    }
  }

  "ProgramCounter" should "mode=stay면 값을 그대로 유지" in {
    test(new ProgramCounter) { dut =>
      dut.mode.poke(COUNT.U)
      dut.clock.step()
      dut.clock.step()
      dut.count.expect(8.U)

      dut.mode.poke(STAY.U)
      dut.clock.step()
      dut.clock.step()
      dut.clock.step()
      dut.count.expect(8.U) // 계속 유지
    }
  }

  "ProgramCounter" should "mode=set이면 다음 클럭에 load 값을 그대로 싣는다" in {
    test(new ProgramCounter) { dut =>
      dut.mode.poke(COUNT.U)
      dut.clock.step()
      dut.count.expect(4.U)

      dut.mode.poke(SET.U)
      dut.load.poke(0x1000.U)
      dut.count.expect(4.U) // set은 다음 클럭에야 반영됨
      dut.clock.step()
      dut.count.expect(0x1000.U)

      // set 이후 다시 count로 돌아가면 로드된 값에서 이어서 증가
      dut.mode.poke(COUNT.U)
      dut.clock.step()
      dut.count.expect(0x1004.U)
    }
  }

  "ProgramCounter" should "mode=clear면 비동기로 즉시(step 없이) 0으로 리셋" in {
    test(new ProgramCounter) { dut =>
      dut.mode.poke(COUNT.U)
      dut.clock.step()
      dut.clock.step()
      dut.count.expect(8.U)

      dut.mode.poke(CLEAR.U)
      dut.count.expect(0.U) // step 없이 즉시 반영(비동기 리셋)
    }
  }

  "ProgramCounter" should "clear가 풀리고 나면 다시 정상 동작(0부터 count 재개)" in {
    test(new ProgramCounter) { dut =>
      dut.mode.poke(COUNT.U)
      dut.clock.step()
      dut.count.expect(4.U)

      dut.mode.poke(CLEAR.U)
      dut.count.expect(0.U)

      dut.mode.poke(COUNT.U)
      dut.clock.step()
      dut.count.expect(4.U)
    }
  }
}
