% bzb.condition_1
        LOC 0
Main    JMP Start
        LOC 32
Start   BZ $1,@-16
        ADDU $4,$4,1
        TRAP 0,Halt,0
