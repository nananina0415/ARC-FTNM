% getab.sign_boundary
        LOC 0
Main    JMP Start
        LOC 32
Start   GETA $3,@-131072
        TRAP 0,Halt,0
