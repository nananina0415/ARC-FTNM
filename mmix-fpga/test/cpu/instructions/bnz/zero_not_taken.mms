% bnz.zero_not_taken
        LOC 0
Main    BNZ $1,@+0
        ADDU $4,$4,1
        TRAP 0,Halt,0
