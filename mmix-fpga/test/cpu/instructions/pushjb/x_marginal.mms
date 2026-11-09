% pushjb.x_marginal
        LOC 0
Main    JMP Start
Sub     POP 0,0
Start   PUSHJ $7,Sub
        TRAP 0,Halt,0
