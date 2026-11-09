% getab.destination_register0
        LOC 0
Main    JMP Start
        LOC 32
Start   GETA $0,@-243504
        TRAP 0,Halt,0
