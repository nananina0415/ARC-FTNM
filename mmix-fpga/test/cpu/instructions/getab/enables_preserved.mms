% getab.enables_preserved
        LOC 0
Main    JMP Start
        LOC 32
Start   GETA $3,@-4
        TRAP 0,Halt,0
