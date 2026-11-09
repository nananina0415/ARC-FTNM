% getab.one
        LOC 0
Main    JMP Start
        LOC 32
Start   GETA $3,@-262140
        TRAP 0,Halt,0
