% pop.pop_return_offset
        LOC 0
Main    PUSHJ $2,Sub
        TRAP 0,Halt,0
        TRAP 0,Halt,0
Sub     POP 0,1
