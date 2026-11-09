% pbevb.condition_1
        LOC 0
Main    JMP Start
        LOC 32
Start   PBEV $1,@-16
        ADDU $4,$4,1
        TRAP 0,Halt,0
