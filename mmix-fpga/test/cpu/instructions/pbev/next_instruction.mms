% pbev.next_instruction
        LOC 0
Main    PBEV $1,@+4
        ADDU $4,$4,1
        TRAP 0,Halt,0
