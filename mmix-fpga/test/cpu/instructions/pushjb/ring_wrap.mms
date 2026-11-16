% pushjb.ring_wrap
        LOC 0
Main    JMP Start
Sub     POP 0,0
Start   PUSHJ $2,Sub
        TRAP 0,Halt,0
