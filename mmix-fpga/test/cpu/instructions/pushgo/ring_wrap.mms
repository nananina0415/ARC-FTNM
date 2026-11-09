% pushgo.ring_wrap
        LOC 0
Main    GETA $254,Sub
        PUSHGO $2,$254,$253
        TRAP 0,Halt,0
Sub     POP 0,0
