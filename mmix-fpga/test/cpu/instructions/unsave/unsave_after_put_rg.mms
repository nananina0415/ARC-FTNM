% unsave.unsave_after_put_rg
        LOC 0
Main    SAVE $255,0
        PUT rG,100
        UNSAVE $255
        TRAP 0,Halt,0
