package cpu

import chisel3._

/** pause=0 && write=1일 때만 D를 래치하는 레지스터.
 *
 * pause=1: write 무관하게 Q 유지 (파이프 일시정지)
 * write=1: 다음 클럭에 D를 래치
 */
class CompoReg[T <: Data](gen: T) extends Module {
  val pause = IO(Input(Bool()))
  val write = IO(Input(Bool()))
  val d     = IO(Input(gen))
  val q     = IO(Output(gen))

  val reg = RegInit(0.U.asTypeOf(gen))
  when(!pause && write) { reg := d }
  q := reg
}

/** pause를 캡처한 CompoReg 팩토리.
 *
 * val CR = compoRegFactory(pause = done)
 * val y_buf = CR(gen = UInt(64.W), write = ..., d = ...)
 */
object CompoRegFactory {
  def apply(pause: Bool) = new {
    def apply[T <: Data](gen: T, write: Bool, d: T) = {
      val m = Module(new CompoReg(gen))
      m.pause := pause
      m.write := write
      m.d     := d
      m
    }
  }
}
