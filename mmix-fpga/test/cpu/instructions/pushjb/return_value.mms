% pushjb.return_value
        LOC 0
Main    JMP Start
Sub     SETL $0,#005a
        POP 1,0
Start   PUSHJ $2,Sub
        TRAP 0,Halt,0
