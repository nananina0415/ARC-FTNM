% unsave.unsave_special_values
        LOC 0
Main    SAVE $255,0
        SETL $1,#00ff
        PUT rM,$1
        UNSAVE $255
        TRAP 0,Halt,0
