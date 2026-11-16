% unsave.unsave_ring_change
        LOC 0
Main    SAVE $255,0
        PUT rG,100
        UNSAVE $255
        TRAP 0,Halt,0
