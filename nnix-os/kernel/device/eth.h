#ifndef _DEVICE_ETH_H
#define _DEVICE_ETH_H

#include "types.h"
#include <stdint.h>

extern void eth_init(void);
extern int  eth_send(const void* buf, int len);
extern int  eth_recv(void* buf, int maxlen);

#endif
