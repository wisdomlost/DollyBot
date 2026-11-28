#include "platform.h"
#include "chassis_config.h"
#include "control_config.h"
#include "stm32f405xx.h"
#include <math.h>

const Geometry geometry = {惯导系数, 500, 600, WHEEL_SPEED_KP, WHEEL_SPEED_KI, MOTOR_POLARITIES};
static const int encoder_polarity[4] = ENCODER_POLARITIES;
/* Keil Watch: cumulative signed quadrature counts even before calibration. */
volatile int64_t wheel_counts[4];
static volatile uint32_t milliseconds, drive_until, loop_until;
static volatile bool drive_on, rx_overflow;
static unsigned rx_tail;
static volatile unsigned tx_head, tx_tail;
static uint32_t tick_us;
static volatile uint8_t rx[2048];
static uint8_t tx[2048];
static bool imu_ok, spi_error, clock_ok;
uint32_t SystemCoreClock = 16000000;
void SystemInit(void) {
    SCB->CPACR |= 0xfu << 20;
    SCB->VTOR = FLASH_BASE;
    __DSB();
    __ISB();
}
static void pin(GPIO_TypeDef *g, unsigned n, unsigned mode, unsigned af) {
    unsigned shift = n * 2;
    g->MODER = (g->MODER & ~(3u << shift)) | (mode << shift);
    if (mode == 2) {
        unsigned a = n / 8, b = 4 * (n % 8);
        g->AFR[a] = (g->AFR[a] & ~(15u << b)) | (af << b);
        g->OSPEEDR |= 2u << shift;
    }
}
static void gpio(GPIO_TypeDef *g, unsigned n, bool high) { g->BSRR = 1u << (n + (high ? 0 : 16)); }
void board_stop(void) {
    gpio(GPIOA, 10, false);
    TIM8->BDTR &= ~TIM_BDTR_MOE;
    drive_on = false;
}
void SysTick_Handler(void) {
    /* TIM5 keeps running during a single-bank Flash erase. */
    uint32_t elapsed_ms = (TIM5->CNT - tick_us) / 1000u;
    tick_us += elapsed_ms * 1000u;
    milliseconds += elapsed_ms;
    if (drive_on &&
        ((int32_t)(milliseconds - drive_until) >= 0 || (int32_t)(milliseconds - loop_until) >= 0))
        board_stop();
}
uint32_t board_now(void) { return milliseconds; }
static void delay_ms(unsigned n) {
    uint32_t start = board_now();
    while (board_now() - start < n)
        __WFI();
}
static void fatal(void) {
    board_stop();
    gpio(GPIOC, 15, false);
    for (;;)
        __NOP();
}
void HardFault_Handler(void) { fatal(); }
void NMI_Handler(void) { fatal(); }
void USART3_IRQHandler(void) {
    uint32_t status = USART3->SR;
    if (status & (USART_SR_ORE | USART_SR_FE | USART_SR_NE)) {
        (void)USART3->DR;
        rx_overflow = true;
        board_stop();
    }
    if ((status & USART_SR_TXE) && (USART3->CR1 & USART_CR1_TXEIE)) {
        if (tx_tail == tx_head)
            USART3->CR1 &= ~USART_CR1_TXEIE;
        else {
            USART3->DR = tx[tx_tail];
            tx_tail = (tx_tail + 1) % sizeof tx;
        }
    }
}
int board_receive(void) {
    unsigned head = (sizeof rx - DMA1_Stream1->NDTR) % sizeof rx;
    if (rx_tail == head)
        return -1;
    int b = rx[rx_tail];
    rx_tail = (rx_tail + 1) % sizeof rx;
    return b;
}
bool board_rx_lost(void) {
    __disable_irq();
    uint32_t dma = DMA1->LISR;
    uint32_t halves = DMA_LISR_HTIF1 | DMA_LISR_TCIF1;
    bool lost =
        rx_overflow || (dma & halves) == halves || (dma & (DMA_LISR_TEIF1 | DMA_LISR_DMEIF1));
    DMA1->LIFCR = dma & (halves | DMA_LISR_TEIF1 | DMA_LISR_DMEIF1);
    if (lost) {
        rx_tail = (sizeof rx - DMA1_Stream1->NDTR) % sizeof rx;
        rx_overflow = false;
    }
    __enable_irq();
    return lost;
}
static bool queue_uart_tx(const uint8_t *p, unsigned n, unsigned reserve) {
    unsigned available = (tx_tail + sizeof tx - tx_head - 1) % sizeof tx;
    if (n + reserve > available)
        return false;
    for (unsigned i = 0; i < n; ++i) {
        tx[tx_head] = p[i];
        tx_head = (tx_head + 1) % sizeof tx;
    }
    USART3->CR1 |= USART_CR1_TXEIE;
    return true;
}
void board_send(const uint8_t *p, unsigned n) {
    if (!queue_uart_tx(p, n, 0)) {
        rx_overflow = true;
        board_stop();
    }
}
void board_send_tuning(const uint8_t *p, unsigned n) {
    /* Drop debug samples before consuming space reserved for control responses. */
    (void)queue_uart_tx(p, n, 512);
}
uint32_t board_frame_id(void) {
    for (unsigned i = 0; i < 100000; ++i)
        if (RNG->SR & RNG_SR_DRDY) {
            uint32_t id = RNG->DR;
            if (id)
                return id;
        }
    /* Without a unique frame identity a saved route cannot be safely anchored. */
    return 0;
}
static uint8_t spi(uint8_t data) {
    unsigned timeout = 10000;
    while (!(SPI2->SR & SPI_SR_TXE) && --timeout) {
    }
    if (!timeout) {
        spi_error = true;
        return 0;
    }
    SPI2->DR = data;
    timeout = 10000;
    while (!(SPI2->SR & SPI_SR_RXNE) && --timeout) {
    }
    if (!timeout) {
        spi_error = true;
        return 0;
    }
    return (uint8_t)SPI2->DR;
}
static void imu_write(uint8_t reg, uint8_t value) {
    gpio(GPIOC, 12, false);
    spi(reg);
    spi(value);
    gpio(GPIOC, 12, true);
}
static void imu_read(uint8_t reg, uint8_t *p, unsigned n) {
    gpio(GPIOC, 12, false);
    spi(reg | 0x80);
    while (n--)
        *p++ = spi(0);
    gpio(GPIOC, 12, true);
}
static int16_t signed_be(const uint8_t *p) { return (int16_t)(p[0] * 256 + p[1]); }
static void encoder(TIM_TypeDef *t) {
    t->ARR = 0xffff;
    t->SMCR = 3;
    t->CCMR1 = 0x3131;
    t->CCER = 0x11;
    t->CR1 = 1;
}
static void pwm(TIM_TypeDef *t, unsigned prescale, unsigned period) {
    t->PSC = prescale;
    t->ARR = period - 1;
    t->CCMR1 = 0x6868;
    t->CCMR2 = 0x6868;
    t->CCER = 0x1111;
    t->EGR = 1;
    t->CR1 = 0x81;
}
static bool adc_voltage(unsigned channel, float *voltage) {
    ADC1->SR = 0;
    ADC1->SQR3 = channel;
    ADC1->CR2 |= ADC_CR2_SWSTART;
    uint32_t start = TIM5->CNT;
    while (!(ADC1->SR & ADC_SR_EOC)) {
        if (TIM5->CNT - start >= 100) {
            /* Cancel this conversion before selecting a different channel. */
            ADC1->CR2 = 0;
            ADC1->CR2 = ADC_CR2_ADON;
            return false;
        }
    }
    *voltage = ADC1->DR * (3.3f / 4095);
    return true;
}
Sensors board_sample(void) {
    static uint16_t previous[4];
    TIM_TypeDef *timers[4] = {TIM2, TIM3, TIM4, TIM1};
    Sensors s = {0};
    for (unsigned i = 0; i < 4; ++i) {
        uint16_t now = (uint16_t)timers[i]->CNT;
        s.counts[i] = (int16_t)((int16_t)(now - previous[i]) * encoder_polarity[i]);
        previous[i] = now;
        wheel_counts[i] += s.counts[i];
        s.encoder_total[i] = wheel_counts[i];
    }
    float battery_adc, motor_adc, gimbal_adc;
    s.power_valid = adc_voltage(10, &battery_adc) && adc_voltage(5, &motor_adc) &&
                    adc_voltage(11, &gimbal_adc);
    if (s.power_valid) {
        s.battery_v = battery_adc * ((100.0f + 22.0f) / 22.0f);
        s.motor_a = motor_adc / (.0005f * 20); /* PA5, motor INA181A1 */
        s.gimbal_a = gimbal_adc / (.010f * 20); /* PC1, gimbal INA181A1 */
    }
    uint8_t data[12];
    spi_error = false;
    imu_read(0x1f, data, sizeof data);
    float acc2 = 0;
    bool sane = false;
    for (unsigned i = 0; i < 6; ++i) {
        int16_t raw = signed_be(data + 2 * i);
        if (raw <= -32760 || raw >= 32760)
            spi_error = true;
        if (raw != 0 && raw != -1)
            sane = true;
        if (i < 3) {
            float a = raw * (8.0f / 32768);
            acc2 += a * a;
        }
    }
    s.accel_g = sqrtf(acc2);
    s.clockwise_dps = GYRO_CLOCKWISE_SIGN * signed_be(data + 10) * (1000.0f / 32768);
    s.valid = clock_ok && imu_ok && sane && !spi_error;
    return s;
}
void board_drive(const float duty[4], uint32_t deadline) {
    __disable_irq();
    if ((int32_t)(deadline - milliseconds) <= 0 || rx_overflow) {
        board_stop();
        __enable_irq();
        return;
    }
    gpio(GPIOB, 0, duty[0] >= 0);
    gpio(GPIOB, 1, duty[1] >= 0);
    gpio(GPIOC, 4, duty[2] >= 0);
    gpio(GPIOC, 5, duty[3] >= 0);
    TIM8->CCR1 = (unsigned)(fabsf(duty[0]) * 4199);
    TIM8->CCR2 = (unsigned)(fabsf(duty[1]) * 4199);
    TIM8->CCR3 = (unsigned)(fabsf(duty[2]) * 4199);
    TIM8->CCR4 = (unsigned)(fabsf(duty[3]) * 4199);
    drive_until = deadline;
    loop_until = milliseconds + 25;
    drive_on = true;
    TIM8->BDTR |= TIM_BDTR_MOE;
    gpio(GPIOA, 10, true);
    __enable_irq();
}
void board_servo(float *pan, float *tilt, bool enable) {
    static bool prepping;
    static uint32_t since;
    if (!enable) {
        gpio(GPIOC, 15, false);
        TIM12->CCR1 = 0;
        TIM12->CCR2 = 0;
        prepping = false;
        return;
    }
    if (!prepping) {
        since = board_now();
        prepping = true;
    }
    if (board_now() - since < 600) {
        *pan = 0;
        *tilt = 0;
    }
    *pan = fmaxf(-PAN_LIMIT_DEG, fminf(PAN_LIMIT_DEG, *pan));
    *tilt = fmaxf(-TILT_LIMIT_DEG, fminf(TILT_LIMIT_DEG, *tilt));
    TIM12->CCR1 = (unsigned)(PAN_CENTER_US + *pan * PAN_US_PER_DEG);
    TIM12->CCR2 = (unsigned)(TILT_CENTER_US + *tilt * TILT_US_PER_DEG);
    gpio(GPIOC, 15, board_now() - since >= 100);
}
void board_init(void) {
    RCC->AHB1ENR |= 7;
    (void)RCC->AHB1ENR;
    gpio(GPIOA, 10, false);
    pin(GPIOA, 10, 1, 0);
    gpio(GPIOC, 15, false);
    pin(GPIOC, 15, 1, 0);
    RCC->CR |= RCC_CR_HSEON;
    unsigned timeout = 1000000;
    while (!(RCC->CR & RCC_CR_HSERDY) && --timeout) {
    }
    if (!timeout)
        fatal();
    FLASH->ACR = FLASH_ACR_ICEN | FLASH_ACR_DCEN | FLASH_ACR_LATENCY_2WS;
    RCC->PLLCFGR = 8 | (336u << 6) | (1u << 16) | RCC_PLLCFGR_PLLSRC_HSE | (7u << 24);
    RCC->CR |= RCC_CR_PLLON;
    while (!(RCC->CR & RCC_CR_PLLRDY)) {
    }
    RCC->CFGR = RCC_CFGR_PPRE1_DIV2 | RCC_CFGR_SW_PLL;
    while ((RCC->CFGR & RCC_CFGR_SWS) != RCC_CFGR_SWS_PLL) {
    }
    SystemCoreClock = 84000000;
    clock_ok = true;
    RCC->APB1ENR |= RCC_APB1ENR_TIM2EN | RCC_APB1ENR_TIM3EN | RCC_APB1ENR_TIM4EN |
                    RCC_APB1ENR_TIM12EN | RCC_APB1ENR_SPI2EN | RCC_APB1ENR_USART3EN;
    RCC->APB2ENR |= RCC_APB2ENR_TIM1EN | RCC_APB2ENR_TIM8EN | RCC_APB2ENR_ADC1EN;
    RCC->AHB2ENR |= RCC_AHB2ENR_RNGEN;
    RNG->CR = RNG_CR_RNGEN;
    RCC->APB1ENR |= RCC_APB1ENR_TIM5EN;
    TIM5->PSC = 83;
    TIM5->ARR = UINT32_MAX;
    TIM5->EGR = 1;
    TIM5->CR1 = 1;
    SysTick_Config(SystemCoreClock / 1000);
    NVIC_SetPriority(SysTick_IRQn, 0);
    pin(GPIOA, 0, 2, 1);
    pin(GPIOA, 1, 2, 1);
    pin(GPIOA, 6, 2, 2);
    pin(GPIOA, 7, 2, 2);
    pin(GPIOB, 6, 2, 2);
    pin(GPIOB, 7, 2, 2);
    pin(GPIOA, 8, 2, 1);
    pin(GPIOA, 9, 2, 1);
    encoder(TIM1);
    encoder(TIM2);
    encoder(TIM3);
    encoder(TIM4);
    for (unsigned i = 6; i <= 9; ++i)
        pin(GPIOC, i, 2, 3);
    pin(GPIOB, 0, 1, 0);
    pin(GPIOB, 1, 1, 0);
    pin(GPIOC, 4, 1, 0);
    pin(GPIOC, 5, 1, 0);
    pwm(TIM8, 0, 4200);
    board_stop();
    pin(GPIOB, 14, 2, 9);
    pin(GPIOB, 15, 2, 9);
    pwm(TIM12, 83, 20000);
    TIM12->CCR1 = PAN_CENTER_US;
    TIM12->CCR2 = TILT_CENTER_US;
    pin(GPIOC, 0, 3, 0);
    pin(GPIOC, 1, 3, 0);
    pin(GPIOA, 5, 3, 0);
    ADC->CCR = ADC_CCR_ADCPRE_0;
    ADC1->SMPR1 = (6u << 0) | (6u << 3);
    ADC1->SMPR2 = 7u << (5 * 3); /* 480 cycles for the filtered motor channel */
    ADC1->CR2 = ADC_CR2_ADON;
    pin(GPIOB, 13, 2, 5);
    pin(GPIOC, 2, 2, 5);
    pin(GPIOC, 3, 2, 5);
    gpio(GPIOC, 12, true);
    pin(GPIOC, 12, 1, 0);
    pin(GPIOA, 4, 0, 0);
    SPI2->CR1 = SPI_CR1_MSTR | SPI_CR1_SSM | SPI_CR1_SSI | (3u << 3) | SPI_CR1_SPE;
    delay_ms(10);
    imu_write(0x76, 0);
    uint8_t who;
    imu_read(0x75, &who, 1);
    imu_write(0x4f, 0x26);
    imu_write(0x50, 0x26); /* 1 kHz, 1000 dps and 8 g */
    imu_write(0x4e, 0x0f);
    delay_ms(50);
    uint8_t power;
    imu_read(0x4e, &power, 1);
    imu_ok = who == 0x47 && power == 0x0f && !spi_error;
    pin(GPIOB, 10, 2, 7);
    pin(GPIOB, 11, 2, 7);
    USART3->BRR = 365;
    /* USART3 RX, DMA1 stream 1 channel 4. Buffer also receives concurrent
     * heartbeats and retried COMMIT frames while the F405 Flash is busy. */
    RCC->AHB1ENR |= RCC_AHB1ENR_DMA1EN;
    (void)RCC->AHB1ENR;
    DMA1_Stream1->PAR = (uint32_t)&USART3->DR;
    DMA1_Stream1->M0AR = (uint32_t)rx;
    DMA1_Stream1->NDTR = sizeof rx;
    DMA1_Stream1->CR = (4u << 25) | DMA_SxCR_CIRC | DMA_SxCR_MINC | DMA_SxCR_EN;
    USART3->CR3 = USART_CR3_DMAR | USART_CR3_EIE;
    USART3->CR1 = USART_CR1_UE | USART_CR1_TE | USART_CR1_RE;
    NVIC_SetPriority(USART3_IRQn, 2);
    NVIC_EnableIRQ(USART3_IRQn);
}
