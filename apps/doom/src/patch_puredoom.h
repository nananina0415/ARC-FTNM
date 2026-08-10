/* MMIX 포팅: m_swap.h 직후에 삽입되는 엔디언 패치.
 *
 * m_swap.h는 __BIG_ENDIAN__ 시:
 *   LONG(x) = (long)SwapLONG((unsigned long)(x))
 * 로 정의하는데, MMIX에서 unsigned long은 64비트라 int→unsigned long 변환 시
 * 음수 값이 sign-extend된다. (예: 0xF0040000 → 0xFFFFFFFFF0040000)
 * SwapLONG은 32비트 long을 가정하므로 상위 32비트의 FF가 결과를 오염시킨다.
 *
 * LONG/SHORT를 undef 후 unsigned int로 마스킹하는 올바른 버전으로 재정의. */
#ifdef __BIG_ENDIAN__
#undef LONG
#undef SHORT
static inline int   doom_le32(unsigned int x)
{
    return (int)((x >> 24) | ((x >> 8) & 0xff00) | ((x << 8) & 0xff0000) | (x << 24));
}
static inline short doom_le16(unsigned short x)
{
    return (short)((x >> 8) | (x << 8));
}
#define LONG(x)  doom_le32((unsigned int)(x))
#define SHORT(x) doom_le16((unsigned short)(x))
#endif /* __BIG_ENDIAN__ */
