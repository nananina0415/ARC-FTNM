import chisel3._

package object cpu {
  // 공통
  val IMM   = "b000001".U(6.W)
  val BACK  = "b000001".U(6.W)

  // 정수 너비
  val I64   = "b000000".U(6.W)
  val U64   = "b000010".U(6.W)
  val I32   = "b000100".U(6.W)
  val U32   = "b000110".U(6.W)
  val I16   = "b001000".U(6.W)
  val U16   = "b001010".U(6.W)
  val I8    = "b001100".U(6.W)
  val U8    = "b001110".U(6.W)

  // ADDSUB 연산
  val ADD         = "b000000".U(6.W)
  val SUB         = "b000100".U(6.W)
  val NEG         = "b001000".U(6.W)
  val SHIFT2ADDU  = "b010010".U(6.W)
  val SHIFT4ADDU  = "b010110".U(6.W)
  val SHIFT8ADDU  = "b011010".U(6.W)
  val SHIFT16ADDU = "b011110".U(6.W)

  // COMP 모드
  val COMP        = "b000000".U(6.W)
  val ATOMIC_SET  = "b010000".U(6.W)
  val SET         = "b100000".U(6.W)
  val ELSE_0      = "b010000".U(6.W)

  // 조건 코드 (BRANCH / COMP SET)
  val NOTNEG   = "b000000".U(6.W)
  val NOTZERO  = "b000010".U(6.W)
  val NOTPOS   = "b000100".U(6.W)
  val EVEN     = "b000110".U(6.W)
  val ZERO     = "b001010".U(6.W)
  val POS      = "b001100".U(6.W)
  val ODD      = "b001110".U(6.W)

  // BRANCH 수식어
  val PROB     = "b010000".U(6.W)

  // SHIFT 방향
  val LEFT     = "b000000".U(6.W)
  val RIGHT    = "b000100".U(6.W)

  // MEM 연산
  val LOAD     = "b000000".U(6.W)
  val STORE    = "b010000".U(6.W)
  val HIGH32   = "b100000".U(6.W)
  val PRELOAD  = "b100010".U(6.W)
  val PRESTORE = "b110010".U(6.W)
  val PREGO    = "b101110".U(6.W)
  val NOCACHE  = "b101000".U(6.W)
  val CONST    = "b101100".U(6.W)
  val SYNCD    = "b110000".U(6.W)
  val SAVE     = "b111000".U(6.W)
  val UNSAVE   = "b111100".U(6.W)

  // BITWISE 연산
  val NOR      = "b000000".U(6.W)
  val NAND     = "b000010".U(6.W)
  val XOR      = "b000100".U(6.W)
  val NXOR     = "b000110".U(6.W)
  val OR       = "b001000".U(6.W)
  val AND      = "b001100".U(6.W)
  val NOT_Z    = "b000010".U(6.W)

  // CONST 연산
  val INIT     = "b000100".U(6.W)
  val HIGHEST  = "b000000".U(6.W)
  val HIGHER   = "b000001".U(6.W)
  val LOWER    = "b000010".U(6.W)
  val LOWEST   = "b000011".U(6.W)

  // JUMP 연산
  val TRAP     = "b000000".U(6.W)
  val TRIP     = "b000010".U(6.W)
  val GOTO     = "b001000".U(6.W)
  val JUMP     = "b001010".U(6.W)
  val SYSCALL  = "b000110".U(6.W)
  val FN       = "b000100".U(6.W)
  val ABS      = "b001000".U(6.W)
  val REL      = "b001010".U(6.W)
  val RET      = "b000000".U(6.W)

  // SPECIAL 연산
  val NO_OP    = "b000000".U(6.W)
  val WAIT     = "b000001".U(6.W)
  val REL_ADDR = "b000010".U(6.W)
  val GET_REG  = "b000100".U(6.W)
  val SET_REG  = "b000110".U(6.W)

  // 특수 레지스터 코드번호 (GET/PUT의 X/Z 필드가 가리키는 값, mmix-doc.pdf §39/§43 기준)
  // 이름 알파벳 오름차순
  val REG_CODE_A  = 21.U(8.W)
  val REG_CODE_B  = 0.U(8.W)
  val REG_CODE_BB = 7.U(8.W)
  val REG_CODE_C  = 8.U(8.W)
  val REG_CODE_D  = 1.U(8.W)
  val REG_CODE_E  = 2.U(8.W)
  val REG_CODE_F  = 22.U(8.W)
  val REG_CODE_G  = 19.U(8.W)
  val REG_CODE_H  = 3.U(8.W)
  val REG_CODE_I  = 12.U(8.W)
  val REG_CODE_J  = 4.U(8.W)
  val REG_CODE_K  = 15.U(8.W)
  val REG_CODE_L  = 20.U(8.W)
  val REG_CODE_M  = 5.U(8.W)
  val REG_CODE_N  = 9.U(8.W)
  val REG_CODE_O  = 10.U(8.W)
  val REG_CODE_P  = 23.U(8.W)
  val REG_CODE_Q  = 16.U(8.W)
  val REG_CODE_R  = 6.U(8.W)
  val REG_CODE_S  = 11.U(8.W)
  val REG_CODE_T  = 13.U(8.W)
  val REG_CODE_TT = 14.U(8.W)
  val REG_CODE_U  = 17.U(8.W)
  val REG_CODE_V  = 18.U(8.W)
  val REG_CODE_W  = 24.U(8.W)
  val REG_CODE_WW = 28.U(8.W)
  val REG_CODE_X  = 25.U(8.W)
  val REG_CODE_XX = 29.U(8.W)
  val REG_CODE_Y  = 26.U(8.W)
  val REG_CODE_YY = 30.U(8.W)
  val REG_CODE_Z  = 27.U(8.W)
  val REG_CODE_ZZ = 31.U(8.W)
}
