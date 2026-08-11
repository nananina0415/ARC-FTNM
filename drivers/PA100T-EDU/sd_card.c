#include "../../nnix-os/src/device/types.h"
#include "sd_card.h"
#include <stdint.h>

/*
 * PA100T-EDU SPI 마스터 MMIO 레이아웃 (블록 17)
 *
 *   offset 0x00  DATA    write: TX 바이트 → 8비트 전송 시작; read: 직전 RX 바이트
 *   offset 0x08  STATUS  bit0: 전송 중(busy)
 *   offset 0x10  CS      bit0: 1=CS 어서트(low), 0=CS 디어서트(high)
 *   offset 0x18  CLKDIV  SPI 클럭 = 시스템 클럭 / (2*(CLKDIV+1))
 *                        60MHz 기준: 74→약 400kHz(초기화용), 1→15MHz(고속)
 */

typedef volatile struct {
    uint64_t data;
    uint64_t status;
    uint64_t cs;
    uint64_t clkdiv;
} spi_regs_t;

#define CLKDIV_INIT  74u   /* ≤400kHz — SD 초기화 요구사항 */
#define CLKDIV_FAST   1u   /* 15MHz */

#define SD_TOKEN_DATA   0xFEu
#define SD_TOKEN_ERR    0x00u
#define SD_R1_IDLE      0x01u
#define SD_R1_OK        0x00u

static spi_regs_t* _spi    = 0;
static int         _ready  = 0;
static int         _sdhc   = 0;   /* 1: SDHC(섹터 주소), 0: SDSC(바이트 주소) */

/* ── 저수준 SPI ──────────────────────────────────────────── */

static uint8_t spi_byte(uint8_t tx)
{
    _spi->data = tx;
    while (_spi->status & 1);
    return (uint8_t)_spi->data;
}

static void cs_assert(void)
{
    _spi->cs = 1;
}

static void cs_deassert(void)
{
    _spi->cs = 0;
    spi_byte(0xFF);   /* CS 디어서트 후 여분 클럭 */
}

/* ── SD SPI 명령 ─────────────────────────────────────────── */

/* R1 응답 반환. bit7이 0이 될 때까지 최대 8바이트 대기 */
static uint8_t sd_cmd(uint8_t cmd, uint32_t arg, uint8_t crc)
{
    spi_byte(0x40u | cmd);
    spi_byte((uint8_t)(arg >> 24));
    spi_byte((uint8_t)(arg >> 16));
    spi_byte((uint8_t)(arg >>  8));
    spi_byte((uint8_t) arg);
    spi_byte(crc | 0x01u);

    uint8_t r = 0xFF;
    int i;
    for (i = 0; i < 8; i++) {
        r = spi_byte(0xFF);
        if (!(r & 0x80u)) break;
    }
    return r;
}

/* ACMD(앱 명령): CMD55 + cmd */
static uint8_t sd_acmd(uint8_t cmd, uint32_t arg)
{
    cs_assert();
    sd_cmd(55, 0, 0x65);
    cs_deassert();
    cs_assert();
    uint8_t r = sd_cmd(cmd, arg, 0xFF);
    return r;
}

/* 데이터 토큰(0xFE) 대기 */
static int sd_wait_token(void)
{
    int i;
    for (i = 0; i < 200000; i++) {
        if (spi_byte(0xFF) == SD_TOKEN_DATA) return 0;
    }
    return -1;
}

/* busy(0x00 응답) 해제 대기 */
static int sd_wait_ready(void)
{
    int i;
    for (i = 0; i < 500000; i++) {
        if (spi_byte(0xFF) != 0x00u) return 0;
    }
    return -1;
}

/* ── 공개 API ────────────────────────────────────────────── */

int sd_card_init(void)
{
    _ready = 0;

    const mmio_entry_t* e = mmio_find(DEV_SPI);
    if (!e) return -1;
    _spi = (spi_regs_t*)mmio_base(e->block);

    _spi->clkdiv = CLKDIV_INIT;
    _spi->cs     = 0;

    /* ≥74 클럭 더미 (CS 디어서트 상태) */
    int i;
    for (i = 0; i < 10; i++) spi_byte(0xFF);

    /* CMD0: SPI 모드 진입 */
    cs_assert();
    uint8_t r = sd_cmd(0, 0, 0x95);
    cs_deassert();
    if (r != SD_R1_IDLE) return -1;

    /* CMD8: SD v2 확인 (arg=0x1AA: VHS=1(2.7-3.6V), check=0xAA) */
    cs_assert();
    r = sd_cmd(8, 0x000001AAu, 0x87);
    int sdv2 = 0;
    if (r == SD_R1_IDLE) {
        /* R7 응답 4바이트 읽기 */
        uint8_t b0 = spi_byte(0xFF);
        uint8_t b1 = spi_byte(0xFF);
        uint8_t b2 = spi_byte(0xFF);
        uint8_t b3 = spi_byte(0xFF);
        (void)b0; (void)b1; (void)b2;
        if (b3 == 0xAAu) sdv2 = 1;  /* 에코 패턴 확인 */
    }
    cs_deassert();

    /* ACMD41: 초기화 완료 대기 */
    uint32_t hcs = sdv2 ? 0x40000000u : 0u;
    for (i = 0; i < 10000; i++) {
        cs_assert();
        r = sd_acmd(41, hcs);
        cs_deassert();
        if (r == SD_R1_OK) break;
    }
    if (r != SD_R1_OK) return -1;

    /* CMD58: OCR 읽기 → CCS 비트로 SDHC 판별 */
    _sdhc = 0;
    if (sdv2) {
        cs_assert();
        r = sd_cmd(58, 0, 0xFD);
        if (r == SD_R1_OK) {
            uint8_t ocr0 = spi_byte(0xFF);
            spi_byte(0xFF); spi_byte(0xFF); spi_byte(0xFF);
            if (ocr0 & 0x40u) _sdhc = 1;  /* CCS=1: SDHC */
        }
        cs_deassert();
    }

    /* SDSC: 블록 크기 512바이트로 고정 */
    if (!_sdhc) {
        cs_assert();
        r = sd_cmd(16, 512, 0xFF);
        cs_deassert();
        if (r != SD_R1_OK) return -1;
    }

    _spi->clkdiv = CLKDIV_FAST;
    _ready = 1;
    return 0;
}

int sd_card_read(uint32_t sector, uint8_t* buf, uint32_t count)
{
    if (!_ready) return -1;

    uint32_t s;
    for (s = 0; s < count; s++) {
        uint32_t addr = _sdhc ? (sector + s) : (sector + s) * 512u;

        cs_assert();
        uint8_t r = sd_cmd(17, addr, 0xFF);
        if (r != SD_R1_OK) { cs_deassert(); return -1; }

        if (sd_wait_token() != 0) { cs_deassert(); return -1; }

        int i;
        for (i = 0; i < 512; i++) buf[s * 512 + i] = spi_byte(0xFF);
        spi_byte(0xFF); spi_byte(0xFF);  /* CRC 2바이트 버림 */
        cs_deassert();
    }
    return 0;
}

int sd_card_write(uint32_t sector, const uint8_t* buf, uint32_t count)
{
    if (!_ready) return -1;

    uint32_t s;
    for (s = 0; s < count; s++) {
        uint32_t addr = _sdhc ? (sector + s) : (sector + s) * 512u;

        cs_assert();
        uint8_t r = sd_cmd(24, addr, 0xFF);
        if (r != SD_R1_OK) { cs_deassert(); return -1; }

        spi_byte(0xFF);           /* 더미 바이트 */
        spi_byte(SD_TOKEN_DATA);  /* 데이터 토큰 */

        int i;
        for (i = 0; i < 512; i++) spi_byte(buf[s * 512 + i]);
        spi_byte(0xFF); spi_byte(0xFF);  /* 더미 CRC */

        uint8_t resp = spi_byte(0xFF) & 0x1Fu;
        if (resp != 0x05u) { cs_deassert(); return -1; }  /* 데이터 응답 확인 */

        if (sd_wait_ready() != 0) { cs_deassert(); return -1; }
        cs_deassert();
    }
    return 0;
}

int sd_card_status(void)
{
    return _ready;
}
