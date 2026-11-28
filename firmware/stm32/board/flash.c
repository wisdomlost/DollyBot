#include "task_store.h"
#include "stm32f405xx.h"
#include <string.h>
const uint8_t *slot_address(unsigned slot) {
    return (const uint8_t *)(0x080c0000u + slot * SLOT_BYTES);
}
static bool unlock(void) {
    if (FLASH->CR & FLASH_CR_LOCK) {
        FLASH->KEYR = 0x45670123;
        FLASH->KEYR = 0xcdef89ab;
    }
    FLASH->SR = 0xf3;
    return !(FLASH->CR & FLASH_CR_LOCK);
}
static bool done(void) {
    while (FLASH->SR & FLASH_SR_BSY) {
    }
    return !(FLASH->SR &
             ((1u << 1) | FLASH_SR_WRPERR | FLASH_SR_PGAERR | FLASH_SR_PGPERR | FLASH_SR_PGSERR));
}
static void lock(void) {
    FLASH->CR = FLASH_CR_LOCK;
    /* Invalidate the F405 Flash data cache before verifying programmed words. */
    uint32_t acr = FLASH->ACR;
    FLASH->ACR = acr & ~FLASH_ACR_DCEN;
    FLASH->ACR = (acr & ~FLASH_ACR_DCEN) | FLASH_ACR_DCRST;
    FLASH->ACR = acr;
}
bool slot_erase(unsigned slot) {
    if (slot > 1 || !unlock())
        return false;
    FLASH->CR = (2u << 8) | ((10u + slot) << 3) | FLASH_CR_SER;
    FLASH->CR |= FLASH_CR_STRT;
    bool ok = done();
    lock();
    return ok;
}
bool slot_program(unsigned slot, unsigned offset, const void *data, unsigned bytes) {
    if (slot > 1 || offset > SLOT_BYTES || bytes > SLOT_BYTES - offset || ((bytes | offset) & 3) ||
        !unlock())
        return false;
    volatile uint32_t *dst = (volatile uint32_t *)(slot_address(slot) + offset);
    const uint8_t *p = data;
    bool ok = true;
    FLASH->CR = (2u << 8) | FLASH_CR_PG;
    for (unsigned i = 0; ok && i < bytes; i += 4) {
        uint32_t word;
        memcpy(&word, p + i, 4);
        *dst++ = word;
        __DSB();
        ok = done();
    }
    lock();
    return ok;
}
