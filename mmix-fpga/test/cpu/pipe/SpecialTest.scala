package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/** Special을 감싸서 레지스터버스/특수레지스터 그룹포트 쪽 신호를 평평한 IO로 노출하는
 * 테스트용 하네스. RegArbiter가 아직 목업이라 실제 버스 대신 여기서 직접 ack/data를
 * 준다. 테스트에서 실제로 건드리는 건 A 그룹(rA, 읽기+쓰기 다 가능)과 else 그룹의 쓰기
 * 금지 레지스터(rN)뿐이라, 나머지 읽기 포트는 전부 고정값(ack=false, data=0)으로
 * 묶어두고, else 그룹 write와 그 외 그룹(Div/Mul/Jump/Mem/Mask) write는 "혹시 엉뚱한
 * 데 write가 새는지"만 관찰한다.
 */
class SpecialHarness extends Module {
  val io = IO(new Bundle {
    val op    = Input(SpecialOp())
    val pause = Input(Bool())

    val regZAddr = Output(UInt(8.W))
    val regZReq  = Output(Bool())
    val regZData = Input(UInt(64.W))
    val regZAck  = Input(Bool())

    val pcData = Input(UInt(64.W))

    val aData = Input(UInt(64.W))
    val aAck  = Input(Bool())
    val aReq  = Output(Bool())

    val aWData  = Output(UInt(64.W))
    val aWWrite = Output(Bool())

    val elseWWrite = Output(Bool())  // else 그룹 write — rN/rC가 걸리면 안 됨

    val anyOtherWrite = Output(Bool())  // A/else를 뺀 나머지(Div/Mul/Jump/Mem/Mask) write의 OR

    val result = Output(SpecialResult())
  })

  val arbiter        = Module(new RegArbiter)
  val regFactory      = new RegReadPortFactory(arbiter)
  val specialFactory   = new SpecialRegReadPortFactory
  val special = Module(new Special(regFactory, specialFactory))

  special.io.op    := io.op
  special.io.pause := io.pause

  special.io.reg.x.data := 0.U; special.io.reg.x.ack := false.B
  special.io.reg.y.data := 0.U; special.io.reg.y.ack := false.B
  special.io.reg.z.data := io.regZData
  special.io.reg.z.ack  := io.regZAck
  io.regZAddr := special.io.reg.z.addr
  io.regZReq  := special.io.reg.z.req

  special.io.pc.data := io.pcData

  special.io.aR.data := io.aData
  special.io.aR.ack  := io.aAck
  io.aReq := special.io.aR.req
  io.aWData  := special.io.aW.data
  io.aWWrite := special.io.aW.write

  // 나머지 그룹 읽기 포트 — 아무도 요청 안 하니 값은 무의미, 고정값으로만 채운다.
  special.io.divR.data  := 0.U; special.io.divR.ack  := false.B
  special.io.mulR.data  := 0.U; special.io.mulR.ack  := false.B
  special.io.jumpR.data := 0.U; special.io.jumpR.ack := false.B
  special.io.memR.data  := 0.U; special.io.memR.ack  := false.B
  special.io.maskR.data := 0.U; special.io.maskR.ack := false.B
  special.io.elseR.data := 0.U; special.io.elseR.ack := false.B

  io.elseWWrite := special.io.elseW.write

  io.anyOtherWrite := Seq(
    special.io.divW.write, special.io.mulW.write, special.io.jumpW.write,
    special.io.memW.write, special.io.maskW.write
  ).reduce(_ || _)

  io.result := special.io.result
}

class SpecialTest extends AnyFlatSpec with ChiselScalatestTester {

  // package.scala의 SPECIAL 연산 상수(NO_OP/GET_REG/SET_REG/IMM)와 같은 값 —
  // 포크가 UInt 리터럴끼리 |로 합성하는 걸 elaboration 밖(테스트 본문)에서 하면 안 되니
  // 여기선 그냥 숫자로 둔다.
  val NoOpFlag       = 0x0
  val RelAddrFlag    = 0x2  // GETA
  val RelAddrBackFlag = 0x3 // GETAB
  val GetRegFlag     = 0x4
  val SetRegFlag     = 0x6
  val SetRegImmFlag  = 0x7  // PUTI

  def setOp(dut: SpecialHarness, flag: Int, x: Int, z: Int): Unit = {
    dut.io.op.flag.poke(flag.U)
    dut.io.op.xyz.poke(((x << 16) | z).U(24.W))
    dut.io.pause.poke(false.B)
  }

  val MASK64: BigInt = (BigInt(1) << 64) - 1
  def u64(v: BigInt): UInt = (v & MASK64).U(64.W)

  "Special" should "SWYM: 부수효과 없음(writeX=false)" in {
    test(new SpecialHarness) { dut =>
      setOp(dut, NoOpFlag, x = 0, z = 0)
      dut.clock.step()
      dut.io.result.writeX.expect(false.B)
      dut.io.aWWrite.expect(false.B)
      dut.io.anyOtherWrite.expect(false.B)
    }
  }

  "Special" should "GETA: λ + 4·YZ를 $X에 씀(레지스터버스 대기 없이 즉시)" in {
    test(new SpecialHarness) { dut =>
      dut.io.pcData.poke(u64(0x1000))
      setOp(dut, RelAddrFlag, x = 5, z = 0x0003)  // YZ=3
      dut.io.result.res.expect(u64(0x1000 + 4 * 3))
      dut.io.result.dest.expect(5.U)
      dut.io.result.writeX.expect(true.B)
      dut.io.regZReq.expect(false.B)
      dut.io.aWWrite.expect(false.B)
      dut.io.anyOtherWrite.expect(false.B)
    }
  }

  "Special" should "GETAB: λ + 4·(YZ - 2^16)를 $X에 씀" in {
    test(new SpecialHarness) { dut =>
      val pcVal = BigInt("100000", 16)
      dut.io.pcData.poke(u64(pcVal))
      setOp(dut, RelAddrBackFlag, x = 5, z = 0x0001)  // YZ=1
      dut.io.result.res.expect(u64(pcVal + 4 - 262144))
      dut.io.result.writeX.expect(true.B)
    }
  }

  "Special" should "GET rA(A 그룹): ack 오기 전엔 대기, ack+data 오면 다음 클럭에 result.res로 반영" in {
    test(new SpecialHarness) { dut =>
      dut.io.aAck.poke(false.B)
      setOp(dut, GetRegFlag, x = 1, z = 21)  // z=REG_CODE_A
      dut.clock.step()
      dut.io.aReq.expect(true.B)  // 아직 응답 없음 — 요청 걸림

      dut.io.aData.poke(u64(0x1234))
      dut.io.aAck.poke(true.B)    // clear는 비동기라 즉시 반영
      dut.io.aReq.expect(false.B)
      dut.io.result.res.expect(u64(0x1234))
      dut.io.result.dest.expect(1.U)
      dut.io.result.writeX.expect(true.B)
    }
  }

  "Special" should "GET 미정의 코드(32): 대기 없이 즉시 0" in {
    test(new SpecialHarness) { dut =>
      setOp(dut, GetRegFlag, x = 1, z = 32)
      dut.clock.step()
      dut.io.result.res.expect(0.U)
      dut.io.result.writeX.expect(true.B)
      dut.io.aReq.expect(false.B)  // A 그룹은 대상이 아니니 무장 안 됨
    }
  }

  "Special" should "PUT rA(A 그룹), 레지스터 오퍼랜드($Z): 버스 요청 후 ack 오면 aW에 값이 실림" in {
    test(new SpecialHarness) { dut =>
      dut.io.regZAck.poke(false.B)
      setOp(dut, SetRegFlag, x = 21, z = 5)  // x=REG_CODE_A, z=$5(레지스터 번호)
      dut.clock.step()  // set은 다음 클럭에야 regPort.z.q에 반영됨 — 그 전엔 안 걸림
      dut.io.regZReq.expect(true.B)
      dut.io.regZAddr.expect(5.U)
      dut.io.aWWrite.expect(false.B)     // 아직 $Z 값을 못 받음

      dut.io.regZData.poke(u64(0xABCD))
      dut.io.regZAck.poke(true.B)         // clear는 비동기라 즉시 반영
      dut.io.aWWrite.expect(true.B)
      dut.io.aWData.expect(u64(0xABCD))
      dut.io.anyOtherWrite.expect(false.B)
      dut.io.elseWWrite.expect(false.B)
    }
  }

  "Special" should "PUT rA(A 그룹), 즉치 오퍼랜드(PUTI): 버스 요청 없이 바로 write" in {
    test(new SpecialHarness) { dut =>
      setOp(dut, SetRegImmFlag, x = 21, z = 0x77)
      dut.io.aWWrite.expect(true.B)
      dut.io.aWData.expect(u64(0x77))
      dut.io.regZReq.expect(false.B)
    }
  }

  "Special" should "PUT rN(코드9, else 그룹의 쓰기 금지 레지스터): write addr 폭에 rN의 로컬번호가 안 들어가 write가 안 걸림" in {
    test(new SpecialHarness) { dut =>
      setOp(dut, SetRegImmFlag, x = 9, z = 0x11)
      dut.clock.step()
      dut.io.elseWWrite.expect(false.B)
      dut.io.aWWrite.expect(false.B)
      dut.io.anyOtherWrite.expect(false.B)
    }
  }

  "Special" should "PUT rC(코드8, else 그룹의 또 다른 쓰기 금지 레지스터): 마찬가지로 write 안 걸림" in {
    test(new SpecialHarness) { dut =>
      setOp(dut, SetRegImmFlag, x = 8, z = 0x22)
      dut.clock.step()
      dut.io.elseWWrite.expect(false.B)
    }
  }

  "Special" should "pause=1이면 PUT의 write가 안 걸림" in {
    test(new SpecialHarness) { dut =>
      dut.io.op.flag.poke(SetRegImmFlag.U)
      dut.io.op.xyz.poke(((21 << 16) | 0x55).U(24.W))
      dut.io.pause.poke(true.B)
      dut.io.aWWrite.expect(false.B)
      dut.io.anyOtherWrite.expect(false.B)
    }
  }
}
