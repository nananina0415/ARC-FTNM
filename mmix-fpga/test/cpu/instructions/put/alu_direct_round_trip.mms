% put.alu_direct_round_trip
        LOC 0
Main    SETL $1,2
        PUT rD,$1
        SETL $2,3
        SETL $3,4
        DIVU $4,$2,$3
        GET $5,rR
        SETL $1,#00ff
        PUT rM,$1
        MUX $6,$2,$3
        GET $7,rD
        TRAP 0,Halt,0
