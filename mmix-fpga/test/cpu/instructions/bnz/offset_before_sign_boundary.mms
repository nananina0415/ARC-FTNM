% bnz.offset_before_sign_boundary
        LOC 0
Main    BNZ $1,@+131068
        ADDU $4,$4,1
        TRAP 0,Halt,0
