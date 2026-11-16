% Project word = 64-bit octa. MMIX itself calls a 16-bit value a wyde.
        LOC 0
Main    LDO $1,$0,80
        LDO $2,$0,88
        ADD $3,$1,$2
        STO $3,$0,96
        TRAP 0,Halt,0

        LOC 80
First   OCTA 123
Second  OCTA 456
Result  OCTA 0
