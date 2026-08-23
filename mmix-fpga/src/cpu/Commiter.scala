package cpu

import chisel3._
import chisel3.util._

class Commiter extends Module {
    val io = IO(new Bundle {
        val addsub  = Input(AddSubResult())
        // val scheduler = Valid(Scheduler())  // 미구현
    })
    // let first = self.io.scheduler.get_1st_job()
    // if pipes[first].done {
    //     switch first {
    //         ...
    //         is(Pipe.ADDSUB) {
    //             self.io.reg.set_rA = if pipes[first].{ovf,carry}
    //             self.io.reg.set(pipes[first].dest, pipes[first].res)
    //             if pipes[first].ovf_trap & if pipes[first].ovf {hw_trap_ovf()}
    //         }
    //         ...
    //     }
    // }

}