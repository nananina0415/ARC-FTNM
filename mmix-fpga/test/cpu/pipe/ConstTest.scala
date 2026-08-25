package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/*
 * flag[3:2]=op(0:INC 1:SET 2:OR 3:ANDN), flag[1:0]=pos(0:HIGHEST 1:HIGHER 2:LOWER 3:LOWEST)
 * INCH=0x0 INCMH=0x1 INCML=0x2 INCL=0x3
 * SETH=0x4 SETMH=0x5 SETML=0x6 SETL=0x7
 * ORH=0x8  ORMH=0x9  ORML=0xA  ORL=0xB
 * ANDNH=0xC ANDNMH=0xD ANDNML=0xE ANDNL=0xF
 */

/** Const를 감싸서 레지스터버스 쪽 신호(ack/data)를 평평한 IO로 노출하는 테스트용 하네스.
 * RegArbiter가 아직 목업이라 실제 버스 대신 여기서 직접 ack/data를 준다.
 */
class ConstHarness extends Module {
  val io = IO(new Bundle {
    val op       = Input(ConstOp())
    val pause    = Input(Bool())
    val accFlag  = Input(Bool())
    val accEnd   = Input(Bool())
    val regXData = Input(UInt(64.W))
    val regXAck  = Input(Bool())
    val regXReq  = Output(Bool())  // X를 실제로 요청 중인지 밖에서 관찰용
    val result   = Output(ConstResult())
  })

  val arbiter = Module(new RegArbiter)
  val factory = new RegReadPortFactory(arbiter)
  val const   = Module(new Const(factory))

  const.io.op         := io.op
  const.io.pause      := io.pause
  const.io.accFlag    := io.accFlag
  const.io.accEnd     := io.accEnd
  const.io.reg.x.data := io.regXData
  const.io.reg.x.ack  := io.regXAck
  const.io.reg.y.data := 0.U   // Const는 Y/Z를 안 씀 — req가 항상 0이라 값은 안 쓰임
  const.io.reg.y.ack  := false.B
  const.io.reg.z.data := 0.U
  const.io.reg.z.ack  := false.B
  io.regXReq := const.io.reg.x.req
  io.result  := const.io.result
}

class ConstTest extends AnyFlatSpec with ChiselScalatestTester {

  val INCH = 0x0; val INCMH = 0x1; val INCML = 0x2; val INCL = 0x3
  val SETH = 0x4; val SETMH = 0x5; val SETML = 0x6; val SETL = 0x7
  val ORH  = 0x8; val ORMH  = 0x9; val ORML  = 0xA; val ORL  = 0xB
  val ANDNH = 0xC; val ANDNMH = 0xD; val ANDNML = 0xE; val ANDNL = 0xF

  def setOp(dut: ConstHarness, f: Int, x: Int, yz: Int, accFlag: Boolean = false, accEnd: Boolean = true): Unit = {
    dut.io.op.flag.poke(f.U)
    dut.io.op.x.poke(x.U)
    dut.io.op.yz.poke(yz.U)
    dut.io.pause.poke(false.B)
    dut.io.accFlag.poke(accFlag.B)
    dut.io.accEnd.poke(accEnd.B)
  }

  val MASK64: BigInt = (BigInt(1) << 64) - 1
  def u64(v: BigInt): UInt = (v & MASK64).U(64.W)
  def h(s: String): BigInt = BigInt(s, 16)

  "Const" should "SETH: yz=0x1234 → 0x1234000000000000, X는 무시" in {
    test(new ConstHarness) { dut =>
      dut.io.regXData.poke(u64(h("FFFFFFFFFFFFFFFF")))
      setOp(dut, SETH, 1, 0x1234)
      dut.clock.step()
      dut.io.result.res.expect(u64(h("1234000000000000")))
      dut.io.result.dest.expect(1.U)
      dut.io.result.accEnd.expect(true.B) // 기본값(단독 명령) — 체이닝의 끝
    }
  }

  "Const" should "SETMH: yz=0xABCD → 0x0000ABCD00000000" in {
    test(new ConstHarness) { dut =>
      setOp(dut, SETMH, 1, 0xABCD)
      dut.clock.step()
      dut.io.result.res.expect(u64(h("0000ABCD00000000")))
    }
  }

  "Const" should "SETML: yz=0x5678 → 0x0000000056780000" in {
    test(new ConstHarness) { dut =>
      setOp(dut, SETML, 1, 0x5678)
      dut.clock.step()
      dut.io.result.res.expect(u64(h("0000000056780000")))
    }
  }

  "Const" should "SETL: yz=0x0001 → 1" in {
    test(new ConstHarness) { dut =>
      setOp(dut, SETL, 1, 0x0001)
      dut.clock.step()
      dut.io.result.res.expect(u64(1))
    }
  }

  "Const" should "INCH: X=5, yz=0x0001 → 0x0001000000000005" in {
    test(new ConstHarness) { dut =>
      dut.io.regXData.poke(u64(5))
      setOp(dut, INCH, 1, 0x0001)
      dut.clock.step()
      dut.io.result.res.expect(u64(h("0001000000000005")))
    }
  }

  "Const" should "INCH: 부호비트 반전 트릭 — X=1, yz=0x8000 → 0x8000000000000001" in {
    test(new ConstHarness) { dut =>
      dut.io.regXData.poke(u64(1))
      setOp(dut, INCH, 1, 0x8000)
      dut.clock.step()
      dut.io.result.res.expect(u64(h("8000000000000001")))
    }
  }

  "Const" should "INCMH: X=1, yz=0x0001 → 0x0000000100000001" in {
    test(new ConstHarness) { dut =>
      dut.io.regXData.poke(u64(1))
      setOp(dut, INCMH, 1, 0x0001)
      dut.clock.step()
      dut.io.result.res.expect(u64(h("0000000100000001")))
    }
  }

  "Const" should "INCML: X=1, yz=0x0001 → 0x0000000000010001" in {
    test(new ConstHarness) { dut =>
      dut.io.regXData.poke(u64(1))
      setOp(dut, INCML, 1, 0x0001)
      dut.clock.step()
      dut.io.result.res.expect(u64(h("0000000000010001")))
    }
  }

  "Const" should "INCL: X=1, yz=0x0001 → 2" in {
    test(new ConstHarness) { dut =>
      dut.io.regXData.poke(u64(1))
      setOp(dut, INCL, 1, 0x0001)
      dut.clock.step()
      dut.io.result.res.expect(u64(2))
    }
  }

  "Const" should "ORH: X=0x00FF000000000000, yz=0xFF00 → 0xFFFF000000000000" in {
    test(new ConstHarness) { dut =>
      dut.io.regXData.poke(u64(h("00FF000000000000")))
      setOp(dut, ORH, 1, 0xFF00)
      dut.clock.step()
      dut.io.result.res.expect(u64(h("FFFF000000000000")))
    }
  }

  "Const" should "ANDNH: 절댓값 트릭 — X=0x8000000000000001, yz=0x8000 → 1" in {
    test(new ConstHarness) { dut =>
      dut.io.regXData.poke(u64(h("8000000000000001")))
      setOp(dut, ANDNH, 1, 0x8000)
      dut.clock.step()
      dut.io.result.res.expect(u64(1))
    }
  }

  "Const" should "ANDNL: X=0xFF, yz=0x000F → 0xF0 (하위 니블만 지움)" in {
    test(new ConstHarness) { dut =>
      dut.io.regXData.poke(u64(0xFF))
      setOp(dut, ANDNL, 1, 0x000F)
      dut.clock.step()
      dut.io.result.res.expect(u64(0xF0))
    }
  }

  "Const" should "accFlag 체이닝 — SETH 결과를 레지스터 왕복 없이 INCMH가 이어받음, accEnd는 마지막에만 true" in {
    test(new ConstHarness) { dut =>
      // 체이닝 중간 — 아직 커밋하면 안 되니 accEnd=false
      setOp(dut, SETH, 1, 0x0001, accFlag = false, accEnd = false)
      dut.io.result.res.expect(u64(h("0001000000000000"))) // res는 조합논리라 step 전에도 확인 가능
      dut.io.result.accEnd.expect(false.B)
      dut.clock.step() // acc에 래치 — 다음 사이클 체이닝을 위해 필요

      // 레지스터 파일엔 전혀 다른 값을 넣어서, accFlag=true면 이게 무시되는지 확인
      dut.io.regXData.poke(u64(h("FFFFFFFFFFFFFFFF")))
      // 체이닝의 마지막 명령 — 이제 커밋해도 되니 accEnd=true
      setOp(dut, INCMH, 1, 0x0002, accFlag = true, accEnd = true)
      // 여기서 다시 step하면 accFlag=true가 그대로 유지된 채라 acc.q가 갱신된 값으로 한 번 더
      // 누적돼버린다 — 이 명령의 결과는 step 전에 조합적으로 확인해야 한다.
      dut.io.result.res.expect(u64(h("0001000200000000")))
      dut.io.result.accEnd.expect(true.B)
    }
  }

  "Const" should "pause=1이면 체이닝 중인 acc가 보호됨 (accFlag=false 계산은 애초에 조합적이라 pause와 무관)" in {
    test(new ConstHarness) { dut =>
      // 1) SETH로 acc 시드
      setOp(dut, SETH, 1, 0x0001, accFlag = false)
      dut.clock.step()
      dut.io.result.res.expect(u64(h("0001000000000000")))

      // 2) pause 중에 완전히 다른 연산을 흘려보내도 acc는 안 바뀌어야 함
      dut.io.pause.poke(true.B)
      setOp(dut, SETL, 1, 0xFFFF, accFlag = false) // setOp가 내부에서 pause를 false로 되돌리므로
      dut.io.pause.poke(true.B)                     // 다시 걸어준다
      dut.clock.step()

      // 3) pause 풀고 체이닝하면 여전히 1)의 acc 값을 이어받아야 함(2)의 영향 없음) — step 전에 확인
      dut.io.pause.poke(false.B)
      setOp(dut, INCMH, 1, 0x0002, accFlag = true)
      dut.io.result.res.expect(u64(h("0001000200000000")))
    }
  }

  // ── 레지스터 요청 조건: !accFlag && !useZeroBase 일 때만 X를 요청해야 한다 ──
  "Const" should "SET 계열은 accFlag와 무관하게 X를 요청하지 않는다" in {
    test(new ConstHarness) { dut =>
      setOp(dut, SETH, 1, 0x1234, accFlag = false)
      dut.io.regXReq.expect(false.B)

      setOp(dut, SETH, 1, 0x1234, accFlag = true)
      dut.io.regXReq.expect(false.B)
    }
  }

  "Const" should "accFlag=true(체이닝 중)면 INC/OR/ANDN 계열도 X를 요청하지 않는다" in {
    test(new ConstHarness) { dut =>
      setOp(dut, INCMH, 1, 0x0002, accFlag = true)
      dut.io.regXReq.expect(false.B)
    }
  }

  "Const" should "accFlag=false(새로 시작)인 INC/OR/ANDN 계열은 X를 요청하고, ack 오면 요청이 풀린다" in {
    test(new ConstHarness) { dut =>
      dut.io.regXAck.poke(false.B)
      setOp(dut, INCMH, 1, 0x0002, accFlag = false)
      dut.io.regXReq.expect(false.B) // set은 다음 클럭에야 q에 반영됨
      dut.clock.step()
      dut.io.regXReq.expect(true.B) // 이제 요청이 걸림 — 아직 응답 없음

      dut.io.regXData.poke(u64(1))
      dut.io.regXAck.poke(true.B)   // clear는 비동기라 즉시 반영 — step 없이도 바로 풀림
      dut.io.regXReq.expect(false.B)
    }
  }
}
