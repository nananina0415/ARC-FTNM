% pbzb.destination_register255
        LOC 0
Main    JMP Start
        LOC 32
Start   PBZ $255,@-16
        ADDU $4,$4,1
        TRAP 0,Halt,0
        LOC 16
        TRAP 0,Halt,0
