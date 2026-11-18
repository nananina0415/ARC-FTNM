% pushgoi.spill_fill_g200
        LOC 0
Main    SETL $255,#0048
        GETA $254,Rec
        PUSHGO $3,$254,0
        TRAP 0,Halt,0
Rec     GET $2,rJ
        SUBU $255,$255,1
        BZ $255,Base
        PUSHGO $3,$254,0
Base    PUT rJ,$2
        POP 0,0
