% jmp.enables_preserved
        LOC 0
Main    JMP @+8
        ADDU $4,$4,1
        TRAP 0,Halt,0
