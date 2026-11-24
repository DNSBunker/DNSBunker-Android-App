#ifndef DNSBUNKER_BRIDGE_H
#define DNSBUNKER_BRIDGE_H
#include <stdint.h>
int bridge_protect(long long token, int fd);
int bridge_emit(long long token, void* data, int length);
int bridge_dns(long long token, void* data, int length, int udp, int port, void* output);
#endif
