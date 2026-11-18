% pushjb.spill_partial
        LOC 0
Main    JMP Start
Sub     POP 0,0
Start   PUSHJ $253,Sub
        TRAP 0,Halt,0
