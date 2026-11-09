% pushj.x_zero
        LOC 0
Main    PUSHJ $0,Sub
        TRAP 0,Halt,0
Sub     POP 0,0
