% getab.sign_before
        LOC 0
Main    JMP Start
        LOC 32
Start   GETA $3,@-131076
        TRAP 0,Halt,0
