package cpu

import chisel3._
import chisel3.util._

/** 파이프와 커미터 사이의 단일 항목 결과 버퍼.
 *
 * done 비트가 흐름을 제어한다.
 * - 파이프: free=1일 때 wr_en으로 데이터 기록, done이 1로 세팅됨
 * - 커미터: done=1을 확인 후 rd_data 읽고 clr=1로 done 클리어
 *
 * clr과 wr_en이 같은 클럭에 들어오면 clr(클리어)이 우선된다.
 */
class PipeBuffer[T <: Data](gen: T) extends Module {
  val io = IO(new Bundle {
    val wr_data = Input(gen)      // 파이프가 기록할 결과
    val wr_en   = Input(Bool())   // 파이프가 기록 요청 (free=1일 때만 유효)
    val free    = Output(Bool())  // done=0, 파이프가 확인

    val rd_data = Output(gen)     // 커미터가 읽는 결과
    val done    = Output(Bool())  // 결과 준비됨, 커미터가 확인
    val clr     = Input(Bool())   // 커미터가 커밋 완료 후 done 클리어
  })

  val buf      = Reg(chiselTypeOf(gen))
  val doneBit  = RegInit(false.B)

  io.rd_data := buf
  io.done    := doneBit
  io.free    := !doneBit

  when(io.clr) {
    doneBit := false.B
  }.elsewhen(io.wr_en && !doneBit) {
    buf     := io.wr_data
    doneBit := true.B
  }
}
