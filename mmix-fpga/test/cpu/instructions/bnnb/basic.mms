% bnnb.basic
        LOC 0
Main    JMP Start
        LOC 32
Start   BNN $1,@-16
        ADDU $4,$4,1
        TRAP 0,Halt,0
        LOC 16
        TRAP 0,Halt,0
