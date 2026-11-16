% go.backward
        LOC 0
Main    JMP Start
        LOC 32
Start   GO $3,$1,$2
        TRAP 0,Halt,0
        LOC 8
        TRAP 0,Halt,0
