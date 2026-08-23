// 각 플래그들은 package.scala에 있음

package cpu

import chisel3._
import chisel3.util._

class Decoder extends Module 
{
  val io = IO(new Bundle {
    val op      = Input(UInt(32.W))
    val illegal = Output(Bool())
    val mul     = Valid(MulOp())
    val div     = Valid(DivOp())
    val addsub  = Valid(AddSubOp())
    val shift   = Valid(ShiftOp())
    val comp    = Valid(CompOp())
    val branch  = Valid(BranchOp())
    val mem     = Valid(MemOp())
    val jump    = Valid(JumpOp())
    val special = Valid(SpecialOp())
    val bitwise = Valid(BitwiseOp())
    val const   = Valid(ConstOp())
  })

  val op  = io.op(31, 24)
  val x   = io.op(23, 16)
  val y   = io.op(15,  8)
  val z   = io.op( 7,  0)
  val yz  = io.op(15,  0)
  val xyz = io.op(23,  0)

  io.illegal      := false.B
  io.mul.valid    := false.B
  io.mul.bits     := DontCare
  io.div.valid    := false.B
  io.div.bits     := DontCare
  io.addsub.valid := false.B
  io.addsub.bits  := DontCare
  io.shift.valid  := false.B
  io.shift.bits   := DontCare
  io.comp.valid   := false.B
  io.comp.bits    := DontCare
  io.branch.valid := false.B
  io.branch.bits  := DontCare
  io.mem.valid     := false.B
  io.mem.bits      := DontCare
  io.jump.valid    := false.B
  io.jump.bits     := DontCare
  io.special.valid  := false.B
  io.special.bits   := DontCare
  io.bitwise.valid  := false.B
  io.bitwise.bits   := DontCare
  io.const.valid    := false.B
  io.const.bits     := DontCare

  def mulOut(flag: UInt): Unit = {
    io.mul.valid      := true.B
    io.mul.bits.flag  := (flag)(4, 0)
    io.mul.bits.x     := x
    io.mul.bits.y     := y
    io.mul.bits.z     := z
  }

  def divOut(flag: UInt): Unit = {
    io.div.valid      := true.B
    io.div.bits.flag  := (flag)(4, 0)
    io.div.bits.x     := x
    io.div.bits.y     := y
    io.div.bits.z     := z
  }

  def shiftOut(flag: UInt): Unit = {
    io.shift.valid      := true.B
    io.shift.bits.flag  := (flag)(4, 0)
    io.shift.bits.x     := x
    io.shift.bits.y     := y
    io.shift.bits.z     := z
  }

  def compOut(flag: UInt): Unit = {
    io.comp.valid      := true.B
    io.comp.bits.flag  := (flag)(5, 0)
    io.comp.bits.x     := x
    io.comp.bits.y     := y
    io.comp.bits.z     := z
  }

  def bitwiseOut(flag: UInt): Unit = {
    io.bitwise.valid      := true.B
    io.bitwise.bits.flag  := (flag)(3, 0)
    io.bitwise.bits.x     := x
    io.bitwise.bits.y     := y
    io.bitwise.bits.z     := z
  }

  def constOut(flag: UInt): Unit = {
    io.const.valid      := true.B
    io.const.bits.flag  := (flag)(3, 0)
    io.const.bits.x     := x
    io.const.bits.yz    := yz
  }

  def jumpOut(flag: UInt): Unit = {
    io.jump.valid      := true.B
    io.jump.bits.flag  := (flag)(3, 0)
    io.jump.bits.xyz   := xyz
  }

  def specialOut(flag: UInt): Unit = {
    io.special.valid      := true.B
    io.special.bits.flag  := (flag)(3, 0)
    io.special.bits.xyz   := xyz
  }

  def branchOut(flag: UInt): Unit = {
    io.branch.valid      := true.B
    io.branch.bits.flag  := (flag)(4, 0)
    io.branch.bits.x     := x
    io.branch.bits.yz    := yz
  }

  def memOut(flag: UInt): Unit = {
    io.mem.valid      := true.B
    io.mem.bits.flag  := (flag)(5, 0)
    io.mem.bits.x     := x
    io.mem.bits.y     := y
    io.mem.bits.z     := z
  }

  def addsubOut(flag: UInt): Unit = {
    io.addsub.valid      := true.B
    io.addsub.bits.flag  := (flag)(4, 0)
    io.addsub.bits.x     := x
    io.addsub.bits.y     := y
    io.addsub.bits.z     := z
  }

  switch(op)
  {
    is(0x00.U) { jumpOut(TRAP) }       // TRAP
    is(                               // 0x01~0x17: 부동소수점 관련 (미구현)
      0x01.U, 0x02.U, 0x03.U, 0x04.U, 0x05.U, 0x06.U, 0x07.U,
      0x08.U, 0x09.U, 0x0A.U, 0x0B.U, 0x0C.U, 0x0D.U, 0x0E.U, 0x0F.U,
      0x10.U, 0x11.U, 0x12.U, 0x13.U, 0x14.U, 0x15.U, 0x16.U, 0x17.U
    ) { io.illegal := true.B }
    is(0x18.U) { mulOut(I64)       }  // MUL
    is(0x19.U) { mulOut(I64 | IMM) }  // MULI
    is(0x1A.U) { mulOut(U64)       }  // MULU
    is(0x1B.U) { mulOut(U64 | IMM) }  // MULUI
    is(0x1C.U) { divOut(I64)       }  // DIV
    is(0x1D.U) { divOut(I64 | IMM) }  // DIVI
    is(0x1E.U) { divOut(U64)       }  // DIVU
    is(0x1F.U) { divOut(U64 | IMM) }  // DIVUI
    is(0x20.U) { addsubOut(ADD | I64)         }  // ADD
    is(0x21.U) { addsubOut(ADD | I64 | IMM)   }  // ADDI
    is(0x22.U) { addsubOut(ADD | U64)         }  // ADDU
    is(0x23.U) { addsubOut(ADD | U64 | IMM)   }  // ADDUI
    is(0x24.U) { addsubOut(SUB | I64)         }  // SUB
    is(0x25.U) { addsubOut(SUB | I64 | IMM)   }  // SUBI
    is(0x26.U) { addsubOut(SUB | U64)         }  // SUBU
    is(0x27.U) { addsubOut(SUB | U64 | IMM)   }  // SUBUI
    is(0x28.U) { addsubOut(SHIFT2ADDU)        }  // 2ADDU
    is(0x29.U) { addsubOut(SHIFT2ADDU  | IMM) }  // 2ADDUI
    is(0x2A.U) { addsubOut(SHIFT4ADDU)        }  // 4ADDU
    is(0x2B.U) { addsubOut(SHIFT4ADDU  | IMM) }  // 4ADDUI
    is(0x2C.U) { addsubOut(SHIFT8ADDU)        }  // 8ADDU
    is(0x2D.U) { addsubOut(SHIFT8ADDU  | IMM) }  // 8ADDUI
    is(0x2E.U) { addsubOut(SHIFT16ADDU)       }  // 16ADDU
    is(0x2F.U) { addsubOut(SHIFT16ADDU | IMM) }  // 16ADDUI
    is(0x30.U) { compOut(COMP | I64)          }  // CMP
    is(0x31.U) { compOut(COMP | I64 | IMM)    }  // CMPI
    is(0x32.U) { compOut(COMP | U64)          }  // CMPU
    is(0x33.U) { compOut(COMP | U64 | IMM)    }  // CMPUI
    is(0x34.U) { addsubOut(NEG | I64)         }  // NEG
    is(0x35.U) { addsubOut(NEG | I64 | IMM)   }  // NEGI
    is(0x36.U) { addsubOut(NEG | U64)         }  // NEGU
    is(0x37.U) { addsubOut(NEG | U64 | IMM)   }  // NEGUI
    is(0x38.U) { shiftOut(LEFT  | I64)        }  // SL
    is(0x39.U) { shiftOut(LEFT  | I64 | IMM)  }  // SLI
    is(0x3A.U) { shiftOut(LEFT  | U64)        }  // SLU
    is(0x3B.U) { shiftOut(LEFT  | U64 | IMM)  }  // SLUI
    is(0x3C.U) { shiftOut(RIGHT | I64)        }  // SR
    is(0x3D.U) { shiftOut(RIGHT | I64 | IMM)  }  // SRI
    is(0x3E.U) { shiftOut(RIGHT | U64)        }  // SRU
    is(0x3F.U) { shiftOut(RIGHT | U64 | IMM)  }  // SRUI
    is(0x40.U) { branchOut(NEG)                    }  // BN
    is(0x41.U) { branchOut(NEG    | BACK)          }  // BNB
    is(0x42.U) { branchOut(ZERO)                   }  // BZ
    is(0x43.U) { branchOut(ZERO   | BACK)          }  // BZB
    is(0x44.U) { branchOut(POS)                    }  // BP
    is(0x45.U) { branchOut(POS    | BACK)          }  // BPB
    is(0x46.U) { branchOut(ODD)                    }  // BOD
    is(0x47.U) { branchOut(ODD    | BACK)          }  // BODB
    is(0x48.U) { branchOut(NOTNEG)                 }  // BNN
    is(0x49.U) { branchOut(NOTNEG | BACK)          }  // BNNB
    is(0x4A.U) { branchOut(NOTZERO)                }  // BNZ
    is(0x4B.U) { branchOut(NOTZERO| BACK)          }  // BNZB
    is(0x4C.U) { branchOut(NOTPOS)                 }  // BNP
    is(0x4D.U) { branchOut(NOTPOS | BACK)          }  // BNPB
    is(0x4E.U) { branchOut(EVEN)                   }  // BEV
    is(0x4F.U) { branchOut(EVEN   | BACK)          }  // BEVB
    is(0x50.U) { branchOut(PROB | NEG)             }  // PBN
    is(0x51.U) { branchOut(PROB | NEG    | BACK)   }  // PBNB
    is(0x52.U) { branchOut(PROB | ZERO)            }  // PBZ
    is(0x53.U) { branchOut(PROB | ZERO   | BACK)   }  // PBZB
    is(0x54.U) { branchOut(PROB | POS)             }  // PBP
    is(0x55.U) { branchOut(PROB | POS    | BACK)   }  // PBPB
    is(0x56.U) { branchOut(PROB | ODD)             }  // PBOD
    is(0x57.U) { branchOut(PROB | ODD    | BACK)   }  // PBODB
    is(0x58.U) { branchOut(PROB | NOTNEG)          }  // PBNN
    is(0x59.U) { branchOut(PROB | NOTNEG | BACK)   }  // PBNNB
    is(0x5A.U) { branchOut(PROB | NOTZERO)         }  // PBNZ
    is(0x5B.U) { branchOut(PROB | NOTZERO| BACK)   }  // PBNZB
    is(0x5C.U) { branchOut(PROB | NOTPOS)          }  // PBNP
    is(0x5D.U) { branchOut(PROB | NOTPOS | BACK)   }  // PBNPB
    is(0x5E.U) { branchOut(PROB | EVEN)            }  // PBEV
    is(0x5F.U) { branchOut(PROB | EVEN   | BACK)   }  // PBEVB
    is(0x60.U) { compOut(SET | NEG)           }  // CSN
    is(0x61.U) { compOut(SET | NEG    | IMM)  }  // CSNI
    is(0x62.U) { compOut(SET | ZERO)          }  // CSZ
    is(0x63.U) { compOut(SET | ZERO   | IMM)  }  // CSZI
    is(0x64.U) { compOut(SET | POS)           }  // CSP
    is(0x65.U) { compOut(SET | POS    | IMM)  }  // CSPI
    is(0x66.U) { compOut(SET | ODD)           }  // CSOD
    is(0x67.U) { compOut(SET | ODD    | IMM)  }  // CSODI
    is(0x68.U) { compOut(SET | NOTNEG)        }  // CSNN
    is(0x69.U) { compOut(SET | NOTNEG | IMM)  }  // CSNNI
    is(0x6A.U) { compOut(SET | NOTZERO)       }  // CSNZ
    is(0x6B.U) { compOut(SET | NOTZERO | IMM) }  // CSNZI
    is(0x6C.U) { compOut(SET | NOTPOS)        }  // CSNP
    is(0x6D.U) { compOut(SET | NOTPOS  | IMM) }  // CSNPI
    is(0x6E.U) { compOut(SET | EVEN)          }  // CSEV
    is(0x6F.U) { compOut(SET | EVEN    | IMM) }  // CSEVI
    is(0x70.U) { compOut(SET | NEG     | ELSE_0)       }  // ZSN
    is(0x71.U) { compOut(SET | NEG     | ELSE_0 | IMM) }  // ZSNI
    is(0x72.U) { compOut(SET | ZERO    | ELSE_0)       }  // ZSZ
    is(0x73.U) { compOut(SET | ZERO    | ELSE_0 | IMM) }  // ZSZI
    is(0x74.U) { compOut(SET | POS     | ELSE_0)       }  // ZSP
    is(0x75.U) { compOut(SET | POS     | ELSE_0 | IMM) }  // ZSPI
    is(0x76.U) { compOut(SET | ODD     | ELSE_0)       }  // ZSOD
    is(0x77.U) { compOut(SET | ODD     | ELSE_0 | IMM) }  // ZSODI
    is(0x78.U) { compOut(SET | NOTNEG  | ELSE_0)       }  // ZSNN
    is(0x79.U) { compOut(SET | NOTNEG  | ELSE_0 | IMM) }  // ZSNNI
    is(0x7A.U) { compOut(SET | NOTZERO | ELSE_0)       }  // ZSNZ
    is(0x7B.U) { compOut(SET | NOTZERO | ELSE_0 | IMM) }  // ZSNZI
    is(0x7C.U) { compOut(SET | NOTPOS  | ELSE_0)       }  // ZSNP
    is(0x7D.U) { compOut(SET | NOTPOS  | ELSE_0 | IMM) }  // ZSNPI
    is(0x7E.U) { compOut(SET | EVEN    | ELSE_0)       }  // ZSEV
    is(0x7F.U) { compOut(SET | EVEN    | ELSE_0 | IMM) }  // ZSEVI
    is(0x80.U) { memOut(LOAD | I8)            }  // LDB
    is(0x81.U) { memOut(LOAD | I8  | IMM)     }  // LDBI
    is(0x82.U) { memOut(LOAD | U8)            }  // LDBU
    is(0x83.U) { memOut(LOAD | U8  | IMM)     }  // LDBUI
    is(0x84.U) { memOut(LOAD | I16)           }  // LDW
    is(0x85.U) { memOut(LOAD | I16 | IMM)     }  // LDWI
    is(0x86.U) { memOut(LOAD | U16)           }  // LDWU
    is(0x87.U) { memOut(LOAD | U16 | IMM)     }  // LDWUI
    is(0x88.U) { memOut(LOAD | I32)           }  // LDT
    is(0x89.U) { memOut(LOAD | I32 | IMM)     }  // LDTI
    is(0x8A.U) { memOut(LOAD | U32)           }  // LDTU
    is(0x8B.U) { memOut(LOAD | U32 | IMM)     }  // LDTUI
    is(0x8C.U) { memOut(LOAD | I64)           }  // LDO
    is(0x8D.U) { memOut(LOAD | I64 | IMM)     }  // LDOI
    is(0x8E.U) { memOut(LOAD | U64)           }  // LDOU
    is(0x8F.U) { memOut(LOAD | U64 | IMM)     }  // LDOUI
    is(0x90.U, 0x91.U) { io.illegal := true.B }  // 0x90~0x91: 단정밀도 실수 로드 (미구현)
    is(0x92.U) { memOut(LOAD | HIGH32 | U32)        }  // LDHT
    is(0x93.U) { memOut(LOAD | HIGH32 | U32 | IMM)  }  // LDHTI
    is(0x94.U) { compOut(ATOMIC_SET)                }  // CSWAP
    is(0x95.U) { compOut(ATOMIC_SET | IMM)          }  // CSWAPI
    is(0x96.U) { memOut(LOAD | NOCACHE | U64)       }  // LDUNC
    is(0x97.U) { memOut(LOAD | NOCACHE | U64 | IMM) }  // LDUNCI
    is(0x98.U, 0x99.U) { io.illegal := true.B       }  // 0x98~0x99: 가상주소 관련 (미구현)
    is(0x9A.U) { memOut(PRELOAD)                    }  // PRELD
    is(0x9B.U) { memOut(PRELOAD | IMM)              }  // PRELDI
    is(0x9C.U) { memOut(PREGO)                      }  // PREGO
    is(0x9D.U) { memOut(PREGO  | IMM)               }  // PREGOI
    is(0x9E.U) { jumpOut(GOTO)                      }  // GO
    is(0x9F.U) { jumpOut(GOTO | IMM)                }  // GOI
    is(0xA0.U) { memOut(STORE | I8)           }  // STB
    is(0xA1.U) { memOut(STORE | I8  | IMM)    }  // STBI
    is(0xA2.U) { memOut(STORE | U8)           }  // STBU
    is(0xA3.U) { memOut(STORE | U8  | IMM)    }  // STBUI
    is(0xA4.U) { memOut(STORE | I16)          }  // STW
    is(0xA5.U) { memOut(STORE | I16 | IMM)    }  // STWI
    is(0xA6.U) { memOut(STORE | U16)          }  // STWU
    is(0xA7.U) { memOut(STORE | U16 | IMM)    }  // STWUI
    is(0xA8.U) { memOut(STORE | I32)          }  // STT
    is(0xA9.U) { memOut(STORE | I32 | IMM)    }  // STTI
    is(0xAA.U) { memOut(STORE | U32)          }  // STTU
    is(0xAB.U) { memOut(STORE | U32 | IMM)    }  // STTUI
    is(0xAC.U) { memOut(STORE | I64)          }  // STO
    is(0xAD.U) { memOut(STORE | I64 | IMM)    }  // STOI
    is(0xAE.U) { memOut(STORE | U64)          }  // STOU
    is(0xAF.U) { memOut(STORE | U64 | IMM)    }  // STOUI
    is(0xB0.U, 0xB1.U) { io.illegal := true.B }  // 0xB0~0xB1: 단정밀도 실수 저장 (미구현)
    is(0xB2.U) { memOut(STORE | HIGH32 | U32)        }  // STHT
    is(0xB3.U) { memOut(STORE | HIGH32 | U32 | IMM)  }  // STHTI
    is(0xB4.U) { memOut(STORE | CONST | U64)         }  // STCO
    is(0xB5.U) { memOut(STORE | CONST | U64 | IMM)   }  // STCOI
    is(0xB6.U) { memOut(STORE | NOCACHE | U64)       }  // STUNC
    is(0xB7.U) { memOut(STORE | NOCACHE | U64 | IMM) }  // STUNCI
    is(0xB8.U) { memOut(SYNC)                        }  // SYNC
    is(0xB9.U) { memOut(SYNC | IMM)                  }  // SYNCI
    is(0xBA.U) { memOut(PRESTORE)                    }  // PREST
    is(0xBB.U) { memOut(PRESTORE | IMM)              }  // PRESTI
    is(0xBC.U, 0xBD.U) { io.illegal := true.B        }  // 0xBC~0xBD: icache 플러시 (미구현)
    is(0xBE.U) { jumpOut(FN | ABS)                   }  // PUSHGO
    is(0xBF.U) { jumpOut(FN | ABS | IMM)             }  // PUSHGOI
    is(0xC0.U) { bitwiseOut(OR)               }  // OR
    is(0xC1.U) { bitwiseOut(OR      | IMM)    }  // ORI
    is(0xC2.U) { bitwiseOut(OR  | NOT_Z)      }  // ORN
    is(0xC3.U) { bitwiseOut(OR  | NOT_Z| IMM) }  // ORNI
    is(0xC4.U) { bitwiseOut(NOR)              }  // NOR
    is(0xC5.U) { bitwiseOut(NOR     | IMM)    }  // NORI
    is(0xC6.U) { bitwiseOut(XOR)              }  // XOR
    is(0xC7.U) { bitwiseOut(XOR     | IMM)    }  // XORI
    is(0xC8.U) { bitwiseOut(AND)              }  // AND
    is(0xC9.U) { bitwiseOut(AND     | IMM)    }  // ANDI
    is(0xCA.U) { bitwiseOut(AND | NOT_Z)      }  // ANDN
    is(0xCB.U) { bitwiseOut(AND | NOT_Z| IMM) }  // ANDNI
    is(0xCC.U) { bitwiseOut(NAND)             }  // NAND
    is(0xCD.U) { bitwiseOut(NAND    | IMM)    }  // NANDI
    is(0xCE.U) { bitwiseOut(NXOR)             }  // NXOR
    is(0xCF.U) { bitwiseOut(NXOR    | IMM)    }  // NXORI
    is(                                                 // 0xD0~0xDF: 특수 명령 (미구현)
      0xD0.U, 0xD1.U, 0xD2.U, 0xD3.U, 0xD4.U, 0xD5.U, 0xD6.U, 0xD7.U,
      0xD8.U, 0xD9.U, 0xDA.U, 0xDB.U, 0xDC.U, 0xDD.U, 0xDE.U, 0xDF.U
    ) { io.illegal := true.B }
    is(0xE0.U) { constOut(INIT | HIGHEST) }  // SETH
    is(0xE1.U) { constOut(INIT | HIGHER)  }  // SETMH
    is(0xE2.U) { constOut(INIT | LOWER)   }  // SETML
    is(0xE3.U) { constOut(INIT | LOWEST)  }  // SETL
    is(0xE4.U) { constOut(HIGHEST)        }  // INCH
    is(0xE5.U) { constOut(HIGHER)         }  // INCMH
    is(0xE6.U) { constOut(LOWER)          }  // INCML
    is(0xE7.U) { constOut(LOWEST)         }  // INCL
    is(0xE8.U) { constOut(OR  | HIGHEST)  }  // ORH
    is(0xE9.U) { constOut(OR  | HIGHER)   }  // ORMH
    is(0xEA.U) { constOut(OR  | LOWER)    }  // ORML
    is(0xEB.U) { constOut(OR  | LOWEST)   }  // ORL
    is(0xEC.U) { constOut(AND | HIGHEST)  }  // ANDNH
    is(0xED.U) { constOut(AND | HIGHER)   }  // ANDNMH
    is(0xEE.U) { constOut(AND | LOWER)    }  // ANDNML
    is(0xEF.U) { constOut(AND | LOWEST)   }  // ANDNL
    is(0xF0.U) { jumpOut(JUMP)               }  // JMP
    is(0xF1.U) { jumpOut(JUMP | BACK)        }  // JMPB
    is(0xF2.U) { jumpOut(FN | REL)           }  // PUSHJ
    is(0xF3.U) { jumpOut(FN | REL | BACK)    }  // PUSHJB
    is(0xF4.U) { specialOut(REL_ADDR)        }  // GETA
    is(0xF5.U) { specialOut(REL_ADDR | BACK) }  // GETAB
    is(0xF6.U) { specialOut(SET_REG)         }  // PUT
    is(0xF7.U) { specialOut(SET_REG  | IMM)  }  // PUTI
    is(0xF8.U) { jumpOut(FN | RET)           }  // POP
    is(0xF9.U) { jumpOut(SYSCALL | RET)      }  // RESUME
    is(0xFA.U) { memOut(SAVE)                }  // SAVE
    is(0xFB.U) { memOut(UNSAVE)              }  // UNSAVE
    is(0xFC.U) { specialOut(WAIT)            }  // SWYM
    is(0xFD.U) { specialOut(NO_OP)           }  // SWYM (NO_OP)
    is(0xFE.U) { specialOut(GET_REG)         }  // GET
    is(0xFF.U) { jumpOut(TRIP)               }  // TRIP
  }
}
