% pushj.spill_fill_unaligned
        LOC 0
Main    SETL $255,#0018
        PUSHJ $3,Rec
        TRAP 0,Halt,0
Rec     GET $2,rJ
        SUBU $255,$255,1
        BZ $255,Base
        PUSHJ $3,Rec
Base    PUT rJ,$2
        POP 0,0
