#include "fatfs/ff.h"
#include "fatfs/diskio.h"
#include "../../drivers/PA100T-EDU/sd_card.h"

/* 드라이브 0 = MicroSD (PA100T-EDU SPI) */

DSTATUS disk_status(BYTE pdrv)
{
    if (pdrv != 0) return STA_NOINIT;
    return sd_card_status() ? 0 : STA_NOINIT;
}

DSTATUS disk_initialize(BYTE pdrv)
{
    if (pdrv != 0) return STA_NOINIT;
    return sd_card_init() == 0 ? 0 : STA_NOINIT;
}

DRESULT disk_read(BYTE pdrv, BYTE* buff, LBA_t sector, UINT count)
{
    if (pdrv != 0) return RES_PARERR;
    return sd_card_read((uint32_t)sector, buff, (uint32_t)count) == 0
           ? RES_OK : RES_ERROR;
}

DRESULT disk_write(BYTE pdrv, const BYTE* buff, LBA_t sector, UINT count)
{
    if (pdrv != 0) return RES_PARERR;
    return sd_card_write((uint32_t)sector, buff, (uint32_t)count) == 0
           ? RES_OK : RES_ERROR;
}

DRESULT disk_ioctl(BYTE pdrv, BYTE cmd, void* buff)
{
    if (pdrv != 0) return RES_PARERR;
    switch (cmd) {
    case CTRL_SYNC:       return RES_OK;
    case GET_SECTOR_SIZE: *(WORD*)buff  = 512; return RES_OK;
    case GET_BLOCK_SIZE:  *(DWORD*)buff = 1;   return RES_OK;
    default:              return RES_PARERR;
    }
}
