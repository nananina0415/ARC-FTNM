% bnnb.offset_extreme
        LOC 0
Main    JMP Start
        LOC 32
Start   BNN $1,@-262144
        ADDU $4,$4,1
        TRAP 0,Halt,0
