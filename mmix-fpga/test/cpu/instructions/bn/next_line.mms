% bn.next_line
        LOC 0
Main    BN $1,@+32
        ADDU $4,$4,1
        TRAP 0,Halt,0
        LOC 32
        TRAP 0,Halt,0
