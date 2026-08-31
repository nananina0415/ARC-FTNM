package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// reasons(i) :=로 넣은 조합 신호는 지연 없이 그 사이클에 바로 pause에 반영돼야 한다.
class ReasonsDelayHarness extends Module {
  val io = IO(new Bundle {
    val w     = Input(Bool())
    val pause = Output(Bool())
  })
  val pb = new PauseBox(1)
  pb.reasons(0) := io.w
  io.pause := pb.pause
}

// RegPortFactory의 plz(= !received && valid)도 조합 와이어라 valid가 바뀌는 즉시
// pause에 반영돼야 한다.
class RegPortDelayHarness extends Module {
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val addr  = Input(UInt(8.W))
    val ack   = Input(Bool())
    val data  = Input(UInt(64.W))

    val req   = Output(Bool())
    val buf   = Output(UInt(64.W))
    val pause = Output(Bool())
  })

  val port = Wire(new RegPort)
  port.data := io.data
  port.ack  := io.ack

  val rpf = new RegPortFactory
  val (buf, plz) = rpf(port, io.valid, io.addr)

  val pb = new PauseBox(1)
  pb.reasons(0) := plz

  io.buf   := buf
  io.req   := port.req
  io.pause := pb.pause
}

class PauseBoxTest extends AnyFlatSpec with ChiselScalatestTester {

  "PauseBox" should "reasons(i) :=: 조합 신호라 같은 사이클에 바로 반영된다(지연 없음)" in {
    test(new ReasonsDelayHarness) { dut =>
      dut.io.w.poke(false.B)
      dut.io.pause.expect(false.B)

      dut.io.w.poke(true.B)
      dut.io.pause.expect(true.B) // step 없이 poke만 해도 즉시 반영

      dut.io.w.poke(false.B)
      dut.io.pause.expect(false.B) // 다시 false로도 즉시 반영
    }
  }

  "RegPortFactory" should "plz: valid가 서는 즉시(조합적으로) req/pause가 반영된다" in {
    test(new RegPortDelayHarness) { dut =>
      dut.io.valid.poke(false.B)
      dut.io.addr.poke(5.U)
      dut.io.ack.poke(false.B)
      dut.io.req.expect(false.B)
      dut.io.pause.expect(false.B)

      dut.io.valid.poke(true.B) // step 없이 poke만
      dut.io.req.expect(true.B)   // 즉시 요청 걸림
      dut.io.pause.expect(true.B) // 즉시 파이프 정지
    }
  }

  "RegPortFactory" should "plz: ack로 받으면 그 다음 사이클에 pause가 풀리고 buf에 값이 담긴다" in {
    test(new RegPortDelayHarness) { dut =>
      dut.io.valid.poke(true.B)
      dut.io.addr.poke(5.U)
      dut.io.ack.poke(false.B)
      dut.io.data.poke(0.U)
      dut.clock.step()
      dut.io.pause.expect(true.B) // 아직 응답 안 옴 — 계속 정지

      dut.io.ack.poke(true.B)
      dut.io.data.poke(0x1234.U)
      dut.clock.step() // 이 사이클에 buf/received가 래치됨

      dut.io.ack.poke(false.B)
      dut.io.pause.expect(false.B)       // 받았으니 더는 정지 안 함
      dut.io.buf.expect(0x1234.U)
      dut.io.req.expect(false.B)         // 재요청 안 함(valid가 계속 걸려있어도)
    }
  }
}
