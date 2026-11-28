#include "vehicle.h"
#include "wire.h"
#include "stm32f405xx.h"
int main(void) {
    board_init();
    vehicle_init();
    Decoder decoder = {0};
    uint32_t last = board_now(), last_byte = last;
    for (;;) {
        if (board_rx_lost()) {
            decoder.used = 0;
            vehicle_link_lost();
        }
        int byte;
        /* Bound work per pass so a full serial burst cannot starve odometry. */
        for (unsigned i = 0; i < 64 && (byte = board_receive()) >= 0; ++i) {
            uint32_t now = board_now();
            if (now - last_byte > 50)
                decoder.used = 0;
            last_byte = now;
            wire_feed(&decoder, (uint8_t)byte, vehicle_frame);
        }
        uint32_t now = board_now();
        if (now - last >= 5) {
            vehicle_tick(board_sample(), now - last);
            last = now;
        } else
            __WFI();
    }
}
