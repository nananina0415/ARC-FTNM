% pbnpb.offset_near
        LOC 0
Main    JMP Start
        LOC 32
Start   PBNP $1,@-4
        ADDU $4,$4,1
        TRAP 0,Halt,0
        LOC 28
        TRAP 0,Halt,0
