% stwui.masked_store_then_cached_loads
        LOC 0
Main    SETL $1,#0100
        LDOU $2,$1,0
        SETL $3,#abcd
        STWU $3,$1,2
        LDOU $4,$1,0
        LDB $5,$1,2
        LDWU $6,$1,2
        LDTU $7,$1,0
        TRAP 0,Halt,0
