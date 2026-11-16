% pbod.cache_conflict
        LOC 0
Main    PBOD $1,@+256
        ADDU $4,$4,1
        TRAP 0,Halt,0
        LOC 256
        TRAP 0,Halt,0
