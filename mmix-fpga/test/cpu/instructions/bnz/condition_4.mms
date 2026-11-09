% bnz.condition_4
        LOC 0
Main    BNZ $1,@+8
        ADDU $4,$4,1
        TRAP 0,Halt,0
