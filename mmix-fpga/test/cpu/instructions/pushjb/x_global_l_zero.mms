% pushjb.x_global_l_zero
        LOC 0
Main    JMP Start
Sub     POP 0,0
Start   PUSHJ $250,Sub
        TRAP 0,Halt,0
