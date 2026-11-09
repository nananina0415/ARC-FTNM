% pushj.x_global_l_zero
        LOC 0
Main    PUSHJ $250,Sub
        TRAP 0,Halt,0
Sub     POP 0,0
