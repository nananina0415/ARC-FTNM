% jmp.next_line
        LOC 0
Main    JMP @+32
        ADDU $4,$4,1
        TRAP 0,Halt,0
        LOC 32
        TRAP 0,Halt,0
