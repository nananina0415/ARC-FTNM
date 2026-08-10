#ifndef _DEVICE_UART_H
#define _DEVICE_UART_H

#include "types.h"

extern void uart_init(void);
extern void uart_putc(char c);
extern int  uart_getc(void);   // 수신 없으면 -1

#endif
