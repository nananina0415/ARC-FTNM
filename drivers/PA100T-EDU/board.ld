/* PA100T-EDU 보드 상수 (링커 스크립트 조각). BIOS ROM 링크(nnix-os/rom/rom.ld)가 INCLUDE 한다.
 * RAM: DDR3 x2, 1GB (회로도 PA-EDU-BLOCK).
 * PROVIDE라서 링크 시 --defsym MEM_SIZE=...가 이 값보다 우선한다. */
PROVIDE(MEM_SIZE = 0x40000000);
