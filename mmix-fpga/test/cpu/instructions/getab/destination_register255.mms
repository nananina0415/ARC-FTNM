% getab.destination_register255
        LOC 0
Main    JMP Start
        LOC 32
Start   GETA $255,@-243504
        TRAP 0,Halt,0
