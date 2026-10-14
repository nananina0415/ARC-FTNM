# 의사코드 문법

회로 구조를 기술하기 위한 의사언어. 파일 확장자는 `.pseudo`.

## 1. 모듈과 가시성

- 모듈 = 폴더. 같은 폴더의 파일들은 같은 모듈이며 서로의 요소를 이름만으로 쓴다.
- 가시성은 모듈에 의해서만 정해진다.
- 다른 모듈 요소 참조:

| 대상 | 표기 | 예 (위치) |
|---|---|---|
| 같은 모듈 | `Name` | — |
| 하위 모듈 | `::child::Name` | `::alu::Alu` (cpu 모듈에서) |
| 형제 모듈 | `sibling::Name` | `alu::AluDirectIO` (reg 모듈에서) |
| 상위 모듈 | 불가 | — |

- 글로벌(`global.pseudo`)은 어디서든 접근 가능하고, 글로벌에서는 어느 요소든 접근 가능하다.
  → 글로벌에 alias를 두는 것이 곧 export.

## 2. alias

이름 축약. import가 아니다 (가시성은 1장 규칙을 따른다).

```
alias Alu = ::alu::Alu           // 축약
alias WORD_SIZE = 64             // 상수 선언 겸용
alias WORD_MSB = WORD_SIZE - 1   // 상수 연산 가능
alias TriState = TriState<64>    // 파라미터 고정 축약

alias {                          // 블록 형태
    rB = 0
    rD = 1
}

alias is_sub = or(a, b)          // wire 안 중간 신호에 이름 붙이기
```

## 3. 타입

| 표기 | 의미 |
|---|---|
| `bit` | 1비트 |
| `In<T,N>` | 입력, 너비 N |
| `Out<T,N>` | 출력, 너비 N. 언어 내장이며 `*In<T,N>`과 같다 |
| `InOut<T,N>` | 양방향, 너비 N |
| `*X` | 번들 X의 인아웃 반전 |
| `[T; N]` | T를 N개 갖는 배열 |

## 4. bundle

포트 묶음. 방향은 번들을 **가지고 있는 쪽** 입장에서의 인아웃.

```
bundle WordIn: In<bit,WORD_SIZE>     // 단일형
bundle WordOut: *WordIn              // 반전

bundle GeneralRegIO {                // 복합형
    sel: In<bit,8>
    rw: In<bit,1>
    data: WordInOut
}
```

- 번들은 번들만 포함 가능.
- `*번들`은 포인터처럼 밖에 있는 번들을 가리키는(연결되는) 용도.
  예: `Alu`의 `pub spec_direct: AluDirectIO` ↔ `Registers`의 `pub alu_direct: *AluDirectIO`

## 5. comb / seq

```
comb Alu { ... }    // 조합회로
seq  Registers { ... }  // 순차회로
```

- 내부에 순차회로를 포함하면 순차회로(`seq`)여야 한다.
- 내부 요소 수식어:

| 수식어 | 붙이는 곳 |
|---|---|
| `pub` | 외부에 노출되는 번들(포트) |
| `mut` | 내부에 삽입한 순차회로 |

## 6. 제네릭

```
seq DirectReg<N> { ... }
wire DirectReg<N> { ... }
```

- 선언과 wire 양쪽에 파라미터를 적는다.
- 제네릭에 영향받는 곳은 모두 파라미터가 드러나야 한다. 범위도 `[..]` 대신 `[0..N]`처럼 명시.

## 7. wire

순차회로·조합회로의 내부 배선. 같은 이름의 `comb`/`seq` 블록과 짝을 이룬다.

### 연결 연산자

| 연산자 | 의미 |
|---|---|
| `->` / `<-` | 한 비트씩 배선. 양쪽 비트 수가 같아야 한다 |
| `=>` / `<=` | 같은 신호를 여러 곳에 각각 배선. 묶이지 않은 쪽과 `{ }` 안 각 요소의 너비가 모두 같다 |
| `<=>` | 양방향 각각 배선 |

화살표 방향이 신호 방향이다.

### 묶음 괄호

| 괄호 | 의미 |
|---|---|
| `[ a, b, ... ]` | 비트 수가 작은 것들을 묶어 하나로 본다. 예: `[ buf[0..N].out ]` = 1비트 N개 → N비트 |
| `{ a, b, ... }` | 각각 배선될 대상 목록 (`=>`, `<=`, `<=>`와 함께) |

```
w_mux.selected -> [ buf[0..N].in ]          // N비트 → 1비트 N개
clock => { buf[0..N].clock }                // 1비트를 N곳에
general.data <=> { generals[0..256].data }  // 64비트 버스를 256곳에
```

상수와 섞을 때는 상수 부분과 나머지를 나눠 쓴다.

```
{ a[0,1,2] } <= 0           // a의 0,1,2비트에 각각 0
a[3..64] <- [ b[0..61] ]    // 나머지 비트
```

### 인덱싱

| 표기 | 의미 |
|---|---|
| `x[i]` | i번째 |
| `x[a,b,c]` | 나열 |
| `x[a..b]` | a부터 b-1까지 |
| `x[..]` | 전체 |
| `x[0,2,7..21]` | 나열과 범위 혼합 |
| `x[rA]` | alias 상수로 인덱싱 |
| `x[k][i]` | k번째 요소의 i번째 비트 |
| `x[0..WORD_SIZE-1]` | 인덱스에 상수 연산 사용 |

### 게이트와 상수

```
w_mux.select <- and(enable, not(rw))
exc.v <- or(a, b, c, d)    // 입력 여러 개 가능
rA_pad.in <- 0             // 상수
```

게이트: `and`, `or`, `not`, `xor`

- 여러 비트 입력이면 비트별로 적용한다. 입력 너비가 모두 같아야 한다. 예: `not(arg2)` = 64비트 반전

| 상수 | 의미 |
|---|---|
| `0`, `1` | 논리값 |
| `OPENED` | 열린 상태 (연결 없음). 쓰지 않는 출력 등에 연결 |

```
sel_alu_op.out[6,7] -> OPENED   // 쓰지 않는 디코더 출력
```

## 8. 내장 소자

| 소자 | 포트 | 비고 |
|---|---|---|
| `D_FlipFlop` | `clock`, `in`, `out` | |
| `Mux<n,m>` | `select`, `option[0..n]`, `selected` | 경우의 수 n, 각 경우 너비 m |
| `Decoder<i,o>` | `in`, `out` | 입력 i비트, 출력 o개 |
| `TriState<N>` | `enable`, `in`, `out` | `TriState` = `TriState<64>` (글로벌 alias) |
| `Adder<N>` | `a`, `b`, `c_in`, `sum`, `c_out` | N비트 덧셈 |

## 9. 명명 규칙

- 직접 와이어로 접근하는 포트·레지스터·번들에는 `direct`를 넣는다. 예: `direct_rA`, `direct_out`, `AluDirectIO`
- 쓰기 신호는 `w_en`.
- 레지스터 `rw`: r=1, w=0.

## 10. 미정 사항

- `DirectReg`에서 버스 쓰기와 직접 쓰기가 동시에 들어오는 경우 (`w_mux.select` = 3)
