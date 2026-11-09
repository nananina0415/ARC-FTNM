% pushgoi.return_value
        LOC 0
Main    GETA $254,Sub
        PUSHGO $2,$254,0
        TRAP 0,Halt,0
Sub     SETL $0,#005a
        POP 1,0
