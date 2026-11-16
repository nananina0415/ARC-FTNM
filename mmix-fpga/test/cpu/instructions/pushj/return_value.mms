% pushj.return_value
        LOC 0
Main    PUSHJ $2,Sub
        TRAP 0,Halt,0
Sub     SETL $0,#005a
        POP 1,0
