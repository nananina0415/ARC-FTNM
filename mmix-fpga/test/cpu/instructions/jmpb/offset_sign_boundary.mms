% jmpb.offset_sign_boundary
        LOC 0
Main    JMP Start
        LOC 32
Start   JMP @-33554432
        ADDU $4,$4,1
        TRAP 0,Halt,0
