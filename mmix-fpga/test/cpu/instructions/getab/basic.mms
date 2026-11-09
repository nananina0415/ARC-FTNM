% getab.basic
        LOC 0
Main    JMP Start
        LOC 32
Start   GETA $3,@-262136
        TRAP 0,Halt,0
