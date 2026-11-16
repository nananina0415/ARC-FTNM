% bev.next_instruction
        LOC 0
Main    BEV $1,@+4
        ADDU $4,$4,1
        TRAP 0,Halt,0
