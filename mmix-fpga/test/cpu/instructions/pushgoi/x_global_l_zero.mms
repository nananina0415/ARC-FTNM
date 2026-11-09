% pushgoi.x_global_l_zero
        LOC 0
Main    GETA $254,Sub
        PUSHGO $250,$254,0
        TRAP 0,Halt,0
Sub     POP 0,0
