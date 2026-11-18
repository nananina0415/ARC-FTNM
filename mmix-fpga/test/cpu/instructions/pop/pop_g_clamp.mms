% pop.pop_g_clamp
        LOC 0
Main    PUSHJ $30,Sub
        TRAP 0,Halt,0
Sub     SETL $4,#0001
        POP 5,0
