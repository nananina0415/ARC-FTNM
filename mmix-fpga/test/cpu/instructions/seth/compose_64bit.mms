% Construct all four wydes through ordinary instructions.
        LOC 0
Main    SETH $3,#0123
        ORMH $3,#4567
        ORML $3,#89ab
        ORL $3,#cdef
        TRAP 0,Halt,0
