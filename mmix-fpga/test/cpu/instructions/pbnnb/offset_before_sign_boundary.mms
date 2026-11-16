% pbnnb.offset_before_sign_boundary
        LOC 0
Main    JMP Start
        LOC 32
Start   PBNN $1,@-131076
        ADDU $4,$4,1
        TRAP 0,Halt,0
