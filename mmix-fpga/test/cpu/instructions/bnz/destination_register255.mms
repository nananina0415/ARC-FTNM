% bnz.destination_register255
        LOC 0
Main    BNZ $255,@+8
        ADDU $4,$4,1
        TRAP 0,Halt,0
