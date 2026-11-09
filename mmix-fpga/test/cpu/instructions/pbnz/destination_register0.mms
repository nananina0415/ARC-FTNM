% pbnz.destination_register0
        LOC 0
Main    PBNZ $0,@+8
        ADDU $4,$4,1
        TRAP 0,Halt,0
