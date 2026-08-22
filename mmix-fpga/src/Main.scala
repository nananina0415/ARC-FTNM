import chisel3._
import _root_.circt.stage.ChiselStage

class MMIX extends Module {
}

object Main extends App {
  ChiselStage.emitSystemVerilogFile(
    new MMIX,
    Array("--target-dir", "build"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
