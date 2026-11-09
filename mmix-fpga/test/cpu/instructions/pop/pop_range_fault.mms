% pop.pop_range_fault
        LOC 0
Main    PUSHJ $126,Sub
        TRAP 0,Halt,0
Sub     SETL $1,#0001
        POP 2,0
