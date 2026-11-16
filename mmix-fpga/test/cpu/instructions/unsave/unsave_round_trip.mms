% unsave.unsave_round_trip
        LOC 0
Main    SAVE $255,0
        SETL $1,#1234
        SETL $201,#0077
        UNSAVE $255
        TRAP 0,Halt,0
