% pop.pop_two
        LOC 0
Main    PUSHJ $2,Sub
        TRAP 0,Halt,0
Sub     SETL $0,#005a
        SETL $1,#006b
        POP 2,0
