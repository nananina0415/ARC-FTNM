% bp.next_instruction
        LOC 0
Main    BP $1,@+4
        ADDU $4,$4,1
        TRAP 0,Halt,0
