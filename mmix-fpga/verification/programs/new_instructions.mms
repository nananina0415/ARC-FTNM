% Runs address generation, narrow loads, partial and constant stores,
% special register access and branches as one connected program.
% Code occupies 0..#CF and data occupies #D0..#FF, so the whole program
% stays inside the 256-byte window shown by the cycle viewer.
        LOC 0
Main    GETA $1,Data
        GETA $2,Out
        LDB $3,$1,0
        LDBU $4,$1,0
        LDW $5,$1,1
        LDWU $6,$1,1
        LDT $7,$1,5
        LDTU $8,$1,5
        SETL $9,8
        LDB $10,$1,$9
        LDBU $11,$1,$9
        LDW $12,$1,$9
        LDWU $13,$1,$9
        LDT $14,$1,$9
        LDTU $15,$1,$9
        SETL $16,20
        SETL $17,12
        SETL $21,24
        STBU $3,$2,1
        STWU $5,$2,10
        STBU $11,$2,$9
        STWU $13,$2,$17
        STCO 200,$2,16
        STTU $7,$2,$16
        LDOU $18,$2,0
        LDOU $19,$2,8
        LDOU $20,$2,16
        STCO 127,$2,$21
        STTU $15,$2,24
        LDOU $22,$2,$21
        PUT rD,$6
        GET $23,rD
        PUT rM,255
        GET $24,rM
        SETL $25,#40
        PUT rA,$25
        GET $26,rA
        SETL $27,3
        SETL $28,0
Loop    ADDU $28,$28,1
        SUBU $27,$27,1
        BNZ $27,Loop
        PBP $28,Skip
        SETL $29,#DEAD
Skip    BN $28,Wrong
        JMP Fwd
Wrong   SETL $29,#DEAD
Back    SETL $29,1
        JMP End
Fwd     JMP Back
End     GETA $30,Loop
        TRAP 0,Halt,0

        LOC #D0
Data    OCTA #80FF7F01A5B6C7D8
        OCTA #817E800192345678
Out     OCTA #A1A2A3A4A5A6A7A8
        OCTA #B1B2B3B4B5B6B7B8
        OCTA #C1C2C3C4C5C6C7C8
        OCTA #D1D2D3D4D5D6D7D8
