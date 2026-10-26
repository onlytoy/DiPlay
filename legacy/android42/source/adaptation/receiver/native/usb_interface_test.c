#include <assert.h>
#include <stdio.h>
static int calls, outcome;
int test_ioctl(int fd, unsigned int request, void *argument) {
    unsigned int *values = argument;
    assert(fd == 17);
    if (request == 0x80085504u) assert(values[0] == 4 && values[1] == 1);
    else { assert(request == 0x80045505u); assert(values[0] == 6); }
    calls++;
    return outcome;
}
extern int Java_local_airuize_receiver_LegacyUsbNative_setInterface(void *, void *, int, int, int);
extern int Java_local_airuize_receiver_LegacyUsbNative_setConfiguration(void *, void *, int, int);
#define call(fd, iface, alt) Java_local_airuize_receiver_LegacyUsbNative_setInterface(0, 0, fd, iface, alt)
int main(void) {
    assert(call(17, 4, 1) == 0 && calls == 1);
    outcome = -16; /* EBUSY */
    assert(call(17, 4, 1) == -16 && calls == 2);
    assert(call(-1, 4, 1) == -22);
    assert(call(17, -1, 1) == -22);
    assert(call(17, 256, 1) == -22);
    assert(call(17, 4, -1) == -22);
    assert(call(17, 4, 256) == -22);
    assert(calls == 2);
    outcome = 0;
    assert(Java_local_airuize_receiver_LegacyUsbNative_setConfiguration(0,0,17,6) == 0);
    outcome = -16;
    assert(Java_local_airuize_receiver_LegacyUsbNative_setConfiguration(0,0,17,6) == -16);
    assert(Java_local_airuize_receiver_LegacyUsbNative_setConfiguration(0,0,-1,6) == -22);
    assert(Java_local_airuize_receiver_LegacyUsbNative_setConfiguration(0,0,17,0) == -22);
    assert(Java_local_airuize_receiver_LegacyUsbNative_setConfiguration(0,0,17,256) == -22);
    assert(calls == 4);
    puts("PASS: USB ioctl code, two-field layout, errno preservation and invalid argument rejection");
}
