% pbnzb.counted_loop
        LOC 0
Main    SETL $1,3
        SETL $2,0
Loop    ADDU $2,$2,1
        SUBU $1,$1,1
        PBNZ $1,Loop
        TRAP 0,Halt,0
