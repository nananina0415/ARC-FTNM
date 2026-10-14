# MMIX 명령어 체크리스트

취소선: 구현범위 밖 (부동소수점, 가상주소)

| | 번호 | 니모닉 | 설명 |
|---|---|---|---|
| [ ] | #00 | TRAP | 운영체제 트랩 |
| [X] | ~~#01~~ | ~~FCMP~~ | ~~부동소수점 비교 (-1/0/1)~~ |
| [X] | ~~#02~~ | ~~FUN~~ | ~~부동소수점 unordered 판정~~ |
| [X] | ~~#03~~ | ~~FEQL~~ | ~~부동소수점 같음 판정~~ |
| [X] | ~~#04~~ | ~~FADD~~ | ~~부동소수점 덧셈~~ |
| [X] | ~~#05~~ | ~~FIX~~ | ~~부동소수점 → 정수~~ |
| [X] | ~~#06~~ | ~~FSUB~~ | ~~부동소수점 뺄셈~~ |
| [X] | ~~#07~~ | ~~FIXU~~ | ~~부동소수점 → unsigned 정수~~ |
| [X] | ~~#08~~ | ~~FLOT~~ | ~~정수 → 부동소수점~~ |
| [X] | ~~#09~~ | ~~FLOTI~~ | ~~FLOT 즉치~~ |
| [X] | ~~#0A~~ | ~~FLOTU~~ | ~~unsigned 정수 → 부동소수점~~ |
| [X] | ~~#0B~~ | ~~FLOTUI~~ | ~~FLOTU 즉치~~ |
| [X] | ~~#0C~~ | ~~SFLOT~~ | ~~정수 → 단정도로 반올림한 부동소수점~~ |
| [X] | ~~#0D~~ | ~~SFLOTI~~ | ~~SFLOT 즉치~~ |
| [X] | ~~#0E~~ | ~~SFLOTU~~ | ~~unsigned 정수 → 단정도로 반올림한 부동소수점~~ |
| [X] | ~~#0F~~ | ~~SFLOTUI~~ | ~~SFLOTU 즉치~~ |
| [X] | ~~#10~~ | ~~FMUL~~ | ~~부동소수점 곱셈~~ |
| [X] | ~~#11~~ | ~~FCMPE~~ | ~~rE 기준 부동소수점 비교~~ |
| [X] | ~~#12~~ | ~~FUNE~~ | ~~rE 기준 unordered 판정~~ |
| [X] | ~~#13~~ | ~~FEQLE~~ | ~~rE 기준 같음 판정~~ |
| [X] | ~~#14~~ | ~~FDIV~~ | ~~부동소수점 나눗셈~~ |
| [X] | ~~#15~~ | ~~FSQRT~~ | ~~부동소수점 제곱근~~ |
| [X] | ~~#16~~ | ~~FREM~~ | ~~부동소수점 나머지~~ |
| [X] | ~~#17~~ | ~~FINT~~ | ~~부동소수점을 정수값으로 반올림~~ |
| [ ] | #18 | MUL | $X = $Y × $Z (signed, 오버플로 시 V) |
| [ ] | #19 | MULI | MUL 즉치 |
| [ ] | #1A | MULU | 128비트 곱. 하위 $X, 상위 rH |
| [ ] | #1B | MULUI | MULU 즉치 |
| [ ] | #1C | DIV | 몫 $X, 나머지 rR (signed, 0나눗셈 D, 오버플로 V) |
| [ ] | #1D | DIVI | DIV 즉치 |
| [ ] | #1E | DIVU | (rD:$Y) ÷ $Z. 몫 $X, 나머지 rR (예외 없음) |
| [ ] | #1F | DIVUI | DIVU 즉치 |
| [ ] | #20 | ADD | $X = $Y + $Z (signed, 오버플로 시 V) |
| [ ] | #21 | ADDI | ADD 즉치 |
| [ ] | #22 | ADDU | $X = $Y + $Z (unsigned) |
| [ ] | #23 | ADDUI | ADDU 즉치 |
| [ ] | #24 | SUB | $X = $Y − $Z (signed, 오버플로 시 V) |
| [ ] | #25 | SUBI | SUB 즉치 |
| [ ] | #26 | SUBU | $X = $Y − $Z (unsigned) |
| [ ] | #27 | SUBUI | SUBU 즉치 |
| [ ] | #28 | 2ADDU | $X = $Y × 2 + $Z (unsigned) |
| [ ] | #29 | 2ADDUI | 2ADDU 즉치 |
| [ ] | #2A | 4ADDU | $X = $Y × 4 + $Z (unsigned) |
| [ ] | #2B | 4ADDUI | 4ADDU 즉치 |
| [ ] | #2C | 8ADDU | $X = $Y × 8 + $Z (unsigned) |
| [ ] | #2D | 8ADDUI | 8ADDU 즉치 |
| [ ] | #2E | 16ADDU | $X = $Y × 16 + $Z (unsigned) |
| [ ] | #2F | 16ADDUI | 16ADDU 즉치 |
| [ ] | #30 | CMP | signed 비교. $X = -1/0/1 |
| [ ] | #31 | CMPI | CMP 즉치 |
| [ ] | #32 | CMPU | unsigned 비교. $X = -1/0/1 |
| [ ] | #33 | CMPUI | CMPU 즉치 |
| [ ] | #34 | NEG | $X = Y − $Z (signed, 오버플로 시 V) |
| [ ] | #35 | NEGI | NEG 즉치 |
| [ ] | #36 | NEGU | $X = Y − $Z (unsigned) |
| [ ] | #37 | NEGUI | NEGU 즉치 |
| [ ] | #38 | SL | 왼쪽 시프트 (signed, 오버플로 시 V) |
| [ ] | #39 | SLI | SL 즉치 |
| [ ] | #3A | SLU | 왼쪽 시프트 (unsigned) |
| [ ] | #3B | SLUI | SLU 즉치 |
| [ ] | #3C | SR | 산술 오른쪽 시프트 |
| [ ] | #3D | SRI | SR 즉치 |
| [ ] | #3E | SRU | 논리 오른쪽 시프트 |
| [ ] | #3F | SRUI | SRU 즉치 |
| [ ] | #40 | BN | $X < 0 이면 분기 (앞으로) |
| [ ] | #41 | BNB | BN (뒤로) |
| [ ] | #42 | BZ | $X = 0 이면 분기 (앞으로) |
| [ ] | #43 | BZB | BZ (뒤로) |
| [ ] | #44 | BP | $X > 0 이면 분기 (앞으로) |
| [ ] | #45 | BPB | BP (뒤로) |
| [ ] | #46 | BOD | $X 홀수면 분기 (앞으로) |
| [ ] | #47 | BODB | BOD (뒤로) |
| [ ] | #48 | BNN | $X ≥ 0 이면 분기 (앞으로) |
| [ ] | #49 | BNNB | BNN (뒤로) |
| [ ] | #4A | BNZ | $X ≠ 0 이면 분기 (앞으로) |
| [ ] | #4B | BNZB | BNZ (뒤로) |
| [ ] | #4C | BNP | $X ≤ 0 이면 분기 (앞으로) |
| [ ] | #4D | BNPB | BNP (뒤로) |
| [ ] | #4E | BEV | $X 짝수면 분기 (앞으로) |
| [ ] | #4F | BEVB | BEV (뒤로) |
| [ ] | #50 | PBN | BN, 분기 예측 taken (앞으로) |
| [ ] | #51 | PBNB | PBN (뒤로) |
| [ ] | #52 | PBZ | BZ, 분기 예측 taken (앞으로) |
| [ ] | #53 | PBZB | PBZ (뒤로) |
| [ ] | #54 | PBP | BP, 분기 예측 taken (앞으로) |
| [ ] | #55 | PBPB | PBP (뒤로) |
| [ ] | #56 | PBOD | BOD, 분기 예측 taken (앞으로) |
| [ ] | #57 | PBODB | PBOD (뒤로) |
| [ ] | #58 | PBNN | BNN, 분기 예측 taken (앞으로) |
| [ ] | #59 | PBNNB | PBNN (뒤로) |
| [ ] | #5A | PBNZ | BNZ, 분기 예측 taken (앞으로) |
| [ ] | #5B | PBNZB | PBNZ (뒤로) |
| [ ] | #5C | PBNP | BNP, 분기 예측 taken (앞으로) |
| [ ] | #5D | PBNPB | PBNP (뒤로) |
| [ ] | #5E | PBEV | BEV, 분기 예측 taken (앞으로) |
| [ ] | #5F | PBEVB | PBEV (뒤로) |
| [ ] | #60 | CSN | $Y < 0 이면 $X = $Z |
| [ ] | #61 | CSNI | CSN 즉치 |
| [ ] | #62 | CSZ | $Y = 0 이면 $X = $Z |
| [ ] | #63 | CSZI | CSZ 즉치 |
| [ ] | #64 | CSP | $Y > 0 이면 $X = $Z |
| [ ] | #65 | CSPI | CSP 즉치 |
| [ ] | #66 | CSOD | $Y 홀수면 $X = $Z |
| [ ] | #67 | CSODI | CSOD 즉치 |
| [ ] | #68 | CSNN | $Y ≥ 0 이면 $X = $Z |
| [ ] | #69 | CSNNI | CSNN 즉치 |
| [ ] | #6A | CSNZ | $Y ≠ 0 이면 $X = $Z |
| [ ] | #6B | CSNZI | CSNZ 즉치 |
| [ ] | #6C | CSNP | $Y ≤ 0 이면 $X = $Z |
| [ ] | #6D | CSNPI | CSNP 즉치 |
| [ ] | #6E | CSEV | $Y 짝수면 $X = $Z |
| [ ] | #6F | CSEVI | CSEV 즉치 |
| [ ] | #70 | ZSN | $Y < 0 이면 $X = $Z, 아니면 0 |
| [ ] | #71 | ZSNI | ZSN 즉치 |
| [ ] | #72 | ZSZ | $Y = 0 이면 $X = $Z, 아니면 0 |
| [ ] | #73 | ZSZI | ZSZ 즉치 |
| [ ] | #74 | ZSP | $Y > 0 이면 $X = $Z, 아니면 0 |
| [ ] | #75 | ZSPI | ZSP 즉치 |
| [ ] | #76 | ZSOD | $Y 홀수면 $X = $Z, 아니면 0 |
| [ ] | #77 | ZSODI | ZSOD 즉치 |
| [ ] | #78 | ZSNN | $Y ≥ 0 이면 $X = $Z, 아니면 0 |
| [ ] | #79 | ZSNNI | ZSNN 즉치 |
| [ ] | #7A | ZSNZ | $Y ≠ 0 이면 $X = $Z, 아니면 0 |
| [ ] | #7B | ZSNZI | ZSNZ 즉치 |
| [ ] | #7C | ZSNP | $Y ≤ 0 이면 $X = $Z, 아니면 0 |
| [ ] | #7D | ZSNPI | ZSNP 즉치 |
| [ ] | #7E | ZSEV | $Y 짝수면 $X = $Z, 아니면 0 |
| [ ] | #7F | ZSEVI | ZSEV 즉치 |
| [ ] | #80 | LDB | 바이트 로드, 부호 확장. $X = M1[$Y+$Z] |
| [ ] | #81 | LDBI | LDB 즉치 |
| [ ] | #82 | LDBU | 바이트 로드, 0 확장 |
| [ ] | #83 | LDBUI | LDBU 즉치 |
| [ ] | #84 | LDW | wyde(2바이트) 로드, 부호 확장 |
| [ ] | #85 | LDWI | LDW 즉치 |
| [ ] | #86 | LDWU | wyde 로드, 0 확장 |
| [ ] | #87 | LDWUI | LDWU 즉치 |
| [ ] | #88 | LDT | tetra(4바이트) 로드, 부호 확장 |
| [ ] | #89 | LDTI | LDT 즉치 |
| [ ] | #8A | LDTU | tetra 로드, 0 확장 |
| [ ] | #8B | LDTUI | LDTU 즉치 |
| [ ] | #8C | LDO | octa(8바이트) 로드 |
| [ ] | #8D | LDOI | LDO 즉치 |
| [ ] | #8E | LDOU | octa 로드 (LDO와 동일) |
| [ ] | #8F | LDOUI | LDOU 즉치 |
| [ ] | ~~#90~~ | ~~LDSF~~ | ~~단정도 부동소수점 로드 → 배정도~~ |
| [ ] | ~~#91~~ | ~~LDSFI~~ | ~~LDSF 즉치~~ |
| [ ] | #92 | LDHT | tetra를 상위 32비트로 로드, 하위 0 |
| [ ] | #93 | LDHTI | LDHT 즉치 |
| [ ] | #94 | CSWAP | compare and swap (rP와 비교) |
| [ ] | #95 | CSWAPI | CSWAP 즉치 |
| [ ] | #96 | LDUNC | LDO와 같음 + 곧 재사용하지 않는다는 힌트 |
| [ ] | #97 | LDUNCI | LDUNC 즉치 |
| [ ] | ~~#98~~ | ~~LDVTS~~ | ~~가상 주소 변환 캐시 조회~~ |
| [ ] | ~~#99~~ | ~~LDVTSI~~ | ~~LDVTS 즉치~~ |
| [ ] | #9A | PRELD | 데이터 프리로드 힌트 |
| [ ] | #9B | PRELDI | PRELD 즉치 |
| [ ] | #9C | PREGO | 명령어 프리페치 힌트 |
| [ ] | #9D | PREGOI | PREGO 즉치 |
| [ ] | #9E | GO | $X = @+4, @ = $Y+$Z |
| [ ] | #9F | GOI | GO 즉치 |
| [ ] | #A0 | STB | 바이트 저장 (signed, 범위 초과 시 V) |
| [ ] | #A1 | STBI | STB 즉치 |
| [ ] | #A2 | STBU | 바이트 저장 (unsigned) |
| [ ] | #A3 | STBUI | STBU 즉치 |
| [ ] | #A4 | STW | wyde 저장 (signed, 범위 초과 시 V) |
| [ ] | #A5 | STWI | STW 즉치 |
| [ ] | #A6 | STWU | wyde 저장 (unsigned) |
| [ ] | #A7 | STWUI | STWU 즉치 |
| [ ] | #A8 | STT | tetra 저장 (signed, 범위 초과 시 V) |
| [ ] | #A9 | STTI | STT 즉치 |
| [ ] | #AA | STTU | tetra 저장 (unsigned) |
| [ ] | #AB | STTUI | STTU 즉치 |
| [ ] | #AC | STO | octa 저장 |
| [ ] | #AD | STOI | STO 즉치 |
| [ ] | #AE | STOU | octa 저장 (STO와 동일) |
| [ ] | #AF | STOUI | STOU 즉치 |
| [ ] | ~~#B0~~ | ~~STSF~~ | ~~배정도 → 단정도 부동소수점 저장~~ |
| [ ] | ~~#B1~~ | ~~STSFI~~ | ~~STSF 즉치~~ |
| [ ] | #B2 | STHT | $X 상위 32비트를 tetra로 저장 |
| [ ] | #B3 | STHTI | STHT 즉치 |
| [ ] | #B4 | STCO | 상수 X를 octa로 저장 |
| [ ] | #B5 | STCOI | STCO 즉치 |
| [ ] | #B6 | STUNC | STO와 같음 + 곧 재사용하지 않는다는 힌트 |
| [ ] | #B7 | STUNCI | STUNC 즉치 |
| [ ] | #B8 | SYNCD | 데이터 캐시 → 메모리 동기화 |
| [ ] | #B9 | SYNCDI | SYNCD 즉치 |
| [ ] | #BA | PREST | 저장 예정 힌트 |
| [ ] | #BB | PRESTI | PREST 즉치 |
| [ ] | #BC | SYNCID | 명령어/데이터 캐시 동기화 |
| [ ] | #BD | SYNCIDI | SYNCID 즉치 |
| [ ] | #BE | PUSHGO | 서브루틴 호출 (레지스터 스택 push, 주소 $Y+$Z) |
| [ ] | #BF | PUSHGOI | PUSHGO 즉치 |
| [ ] | #C0 | OR | $X = $Y \| $Z |
| [ ] | #C1 | ORI | OR 즉치 |
| [ ] | #C2 | ORN | $X = $Y \| ~$Z |
| [ ] | #C3 | ORNI | ORN 즉치 |
| [ ] | #C4 | NOR | $X = ~($Y \| $Z) |
| [ ] | #C5 | NORI | NOR 즉치 |
| [ ] | #C6 | XOR | $X = $Y ^ $Z |
| [ ] | #C7 | XORI | XOR 즉치 |
| [ ] | #C8 | AND | $X = $Y & $Z |
| [ ] | #C9 | ANDI | AND 즉치 |
| [ ] | #CA | ANDN | $X = $Y & ~$Z |
| [ ] | #CB | ANDNI | ANDN 즉치 |
| [ ] | #CC | NAND | $X = ~($Y & $Z) |
| [ ] | #CD | NANDI | NAND 즉치 |
| [ ] | #CE | NXOR | $X = ~($Y ^ $Z) |
| [ ] | #CF | NXORI | NXOR 즉치 |
| [ ] | #D0 | BDIF | 바이트별 포화 뺄셈 |
| [ ] | #D1 | BDIFI | BDIF 즉치 |
| [ ] | #D2 | WDIF | wyde별 포화 뺄셈 |
| [ ] | #D3 | WDIFI | WDIF 즉치 |
| [ ] | #D4 | TDIF | tetra별 포화 뺄셈 |
| [ ] | #D5 | TDIFI | TDIF 즉치 |
| [ ] | #D6 | ODIF | octa 포화 뺄셈 |
| [ ] | #D7 | ODIFI | ODIF 즉치 |
| [ ] | #D8 | MUX | rM 마스크로 비트 선택. ($Y & rM) \| ($Z & ~rM) |
| [ ] | #D9 | MUXI | MUX 즉치 |
| [ ] | #DA | SADD | $Y & ~$Z 의 1비트 개수 |
| [ ] | #DB | SADDI | SADD 즉치 |
| [ ] | #DC | MOR | 8×8 비트 행렬 곱 (OR) |
| [ ] | #DD | MORI | MOR 즉치 |
| [ ] | #DE | MXOR | 8×8 비트 행렬 곱 (XOR) |
| [ ] | #DF | MXORI | MXOR 즉치 |
| [ ] | #E0 | SETH | $X = YZ << 48 |
| [ ] | #E1 | SETMH | $X = YZ << 32 |
| [ ] | #E2 | SETML | $X = YZ << 16 |
| [ ] | #E3 | SETL | $X = YZ |
| [ ] | #E4 | INCH | $X += YZ << 48 |
| [ ] | #E5 | INCMH | $X += YZ << 32 |
| [ ] | #E6 | INCML | $X += YZ << 16 |
| [ ] | #E7 | INCL | $X += YZ |
| [ ] | #E8 | ORH | $X \|= YZ << 48 |
| [ ] | #E9 | ORMH | $X \|= YZ << 32 |
| [ ] | #EA | ORML | $X \|= YZ << 16 |
| [ ] | #EB | ORL | $X \|= YZ |
| [ ] | #EC | ANDNH | $X &= ~(YZ << 48) |
| [ ] | #ED | ANDNMH | $X &= ~(YZ << 32) |
| [ ] | #EE | ANDNML | $X &= ~(YZ << 16) |
| [ ] | #EF | ANDNL | $X &= ~YZ |
| [ ] | #F0 | JMP | 상대 점프 (앞으로) |
| [ ] | #F1 | JMPB | JMP (뒤로) |
| [ ] | #F2 | PUSHJ | 상대 주소 서브루틴 호출 (앞으로) |
| [ ] | #F3 | PUSHJB | PUSHJ (뒤로) |
| [ ] | #F4 | GETA | $X = 상대 주소 (앞으로) |
| [ ] | #F5 | GETAB | GETA (뒤로) |
| [ ] | #F6 | PUT | 특수 레지스터 X = $Z |
| [ ] | #F7 | PUTI | 특수 레지스터 X = Z |
| [ ] | #F8 | POP | 서브루틴 복귀 (레지스터 스택 pop) |
| [ ] | #F9 | RESUME | 인터럽트 처리 후 복귀 |
| [ ] | #FA | SAVE | 프로세스 상태(레지스터) 저장 |
| [ ] | #FB | UNSAVE | 프로세스 상태 복원 |
| [ ] | #FC | SYNC | 파이프라인/메모리 동기화 |
| [ ] | #FD | SWYM | 아무것도 하지 않음 |
| [ ] | #FE | GET | $X = 특수 레지스터 Z |
| [ ] | #FF | TRIP | 트립 (사용자 수준 예외 처리기 호출) |
