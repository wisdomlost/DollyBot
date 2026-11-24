# 陀螺仪电路设计（板载集成，ICM-42688-P / SPI2 + INT）

> 本文是 DollyBot_V1 主板的**板载陀螺仪电路**设计。陀螺仪直接画在主板上，不再做独立小板，不使用 8P XH 针座、线束或 J15；此前的“独立小板 + 8P XH”方案作废。
> 旧方案（1×8 排针 + 加热 + 32.768 kHz 晶振）的网表保留作历史：[`陀螺仪.enet`](../netlist/陀螺仪.enet)，不再按它打样。
> 适用对象：`hardware/DollyBot_V1/` 主板（STM32F405RGT6，LQFP-64），以 [`Netlist_mainboard_2026-11-19.enet`](../netlist/Netlist_mainboard_2026-11-19.enet) 的实际网络为准。
> **称呼约定**：正文一律用“参数 + 作用 + 封装”称呼器件（如“10 kΩ INT 上拉电阻（0402）”），位号只在括号里或 §3.1/§4 的“图上位号”列出现；每根线按“从哪个器件的哪个脚 → 到哪个器件的哪个脚（网络名）”列出，不写“其余按图”。

---

## 1. 选型结论

### 1.1 先看 MCU 实际还剩什么

`hardware-plan.md` 的旧分配写的是“扩展 SPI = SPI3（PC10–PC12）”，但 2026-11-19 的主板网表已经把 PC10/PC11 改成了 UART4 的 `HOST_TX`/`HOST_RX`，PC12 空着；保留 UART4 时，SPI3 的 SCK/MISO 只能落在 PB3/PB4（AF6），还要 PA15 做片选，代价是 SWO、PB4 备用 GPIO 和 IMU 排针（J2）4 脚的 `IMU_INT2`。本设计改走 SPI2：PB13=SCK、PC2=MISO、PC3=MOSI（都是 AF5），PC12 做软件片选，PA4 做中断；只放弃 `GIMBAL_LIMIT2` 这一路限位和 3 个 ADC 备用，保住 SWO、PB4、`IMU_INT2`、UART4，而且不用关 JTAG：

| 信号 | 候选/旧计划 | 2026-11-19 主板网表实际 | 本设计 | 代价 |
|---|---|---|---|---|
| SPI2_SCK | 34 脚 PB13 | `GIMBAL_LIMIT2` | PB13（SPI2_SCK，AF5） | 失去 GIMBAL_LIMIT2；限位2 座/RC 去掉或 DNP |
| SPI2_MISO | 10 脚 PC2 | `ADC_SPARE2` | PC2（SPI2_MISO，AF5） | 失去 1 个 ADC 备用 |
| SPI2_MOSI | 11 脚 PC3 | `ADC_SPARE3` | PC3（SPI2_MOSI，AF5） | 失去 1 个 ADC 备用 |
| CS | 53 脚 PC12 | 空（NC） | PC12（GPIO 输出，默认高） | 无（原本空着） |
| INT | 20 脚 PA4 | `ADC_SPARE4` | PA4（GPIO 输入，下降沿/EXTI4） | 失去 1 个 ADC 备用 |
| 保留 | PB3/PB4/PB5/PA15/PC10/PC11 | `SWO`/`SPARE_GPIO`/`IMU_INT2`/`EXP_SPI_CS`/`HOST_TX`/`HOST_RX` | 全部不变 | 不关 JTAG；调试排针（J1）6 脚仍为 SWO，IMU 排针（J2）4 脚仍为 IMU_INT2 |

结论：

- **为什么可以放弃 `GIMBAL_LIMIT2`**：按当前文档，限位座默认不装（[`gimbal-wiring-guide.md:203`](gimbal-wiring-guide.md#L203)），云台标为“未来扩展；PWM/UART/电源/限位预留”（[`AI_WORK_NOTES.md:234`](AI_WORK_NOTES.md#L234)），并建议软限位优先（[`gimbal-power-wiring.md:228`](gimbal-power-wiring.md#L228)）；限位开关本身也是可选项（[`gimbal-power-wiring.md:281`](gimbal-power-wiring.md#L281)）。主板网表里 `GIMBAL_LIMIT2` 只到主控 34 脚 PB13，主板上没有 RC；如果云台转接板/限位座装了 100 Ω 限位串阻（0603）和 1 nF 限位滤波电容（0603），改 SPI2 前要把它们去掉或 DNP，否则 10.5 MHz SCK 会被滤掉；如果还接着限位开关，也要拆/移走。`GIMBAL_LIMIT1`（PB12）保留。
- **为什么不用 SPI3 PB3/PB4/PB5**：那组脚要失去 SWO、PB4 备用 GPIO 和 IMU 排针（J2）4 脚的 `IMU_INT2`，还要关 JTAG；UART4 交互口虽然当前没有连接器，但保留它更便于调试。只有“UART4 和 `GIMBAL_LIMIT2` 都必须保留”时，才退回那组脚或 I2C。
- **不用关 JTAG**：PA13/PA14 仍是 SWDIO/SWCLK；PA15（`EXP_SPI_CS`）、PB3（`SWO`）、PB4（`SPARE_GPIO`）、PB5（`IMU_INT2`）都不动，调试端口不需要重映射。
- **SPI2 是 AF5**：PB13/PC2/PC3 复用为 SPI2；PC12、PA4 是普通 GPIO。板载走线短，SPI 可以先按 5.25 MHz 跑通，再升到 10.5 MHz 或更高（ICM-42688-P 本身支持到 24 MHz）。

### 1.2 接口：SPI2 + 1 根 INT，不做温控

- **板载只要 5 根信号 + 电源/地**：`SCK`、`MISO`、`MOSI`、`CS`、`INT`，加 `+3V3_SYS` 和 `GND`。不再有 8P 连接器、线束和防反插结构。
- **不做加热**：主控 51 个 I/O 已分完，没有空闲 PWM 引脚；旧方案的 120 mA/0.396 W 占掉 3V3_SYS 约 150 mA 预算的 80%，还会把 AMS1117 耗散从 0.26 W 推到约 0.46 W；保留加热又没有过温保护。机器人上更稳的做法是“不加热 + IMU 内部温度传感器做零偏温补 + 电路远离电机/电调/电池”，见 §6。
- **32.768 kHz 晶振默认不装**：ICM-42688-P 内部 RC 的 ODR 精度约 ±8%，对 1 kHz 采样足够；要启用 CLKIN，固件必须写 `INTF_CONFIG5`（bank1 0x7B）bits[2:1]=0b10、`RTC_MODE`（0x4D）bit2=1、清 `INT_CONFIG1`（0x64）bit4，还要按 32.768/30 处理 FIFO 时间戳，否则偏 9.2%。这些代价换 ±20 ppm 的 ODR 精度，本设计默认不付；需要时按 §3.4 装成 DNP 选项。
- **继续用 ICM-42688-P**：已有器件，六轴，SPI 从机，3.3 V，LGA-14 3.0×2.5 mm（立创 C1850418）。IMU 排针（J2）上的原 IMU 模块走 I2C1（PB8/PB9），与本板载 SPI2 不冲突；IMU 排针（J2）的 4 脚仍接 `IMU_INT2`（PB5），原模块可同时用 INT1/INT2。本板载陀螺仪不占用 J2。

### 1.3 为什么直接集成到主板

- **取消的物料**：8P XH 针座、8P XH 双母头线束、独立小板的 2 个 M3 安装孔、连接器防呆/锁扣/线束应力释放。BOM 少 2 个连接器和 1 根线束，装配少一道插拔。
- **信号完整性更好**：SPI2 直接在主板短走线，SCK/MISO 不必经过 20 cm 排线和连接器；没有接触电阻、插拔氧化和振松问题。
- **代价**：不能像模块那样单独更换/返修；IMU 直接承受主板的振动和热；没有连接器防反接，但也就不存在 3.3 V 落到 GND 的插反风险。布局上要按 §6 远离电机驱动、电感和 HSE 晶振，并保留测试点。
- **测试点**：建议在主板边角留 `3V3`、`GND`、`SCK`、`MISO`、`MOSI`、`CS`、`INT` 七个 Ø1.0 mm 测试点（铜焊盘，不装器件），用于 bring-up 和示波器测量；测试点不进入 BOM。

### 1.4 备选：换 SPI 引脚，或者放弃一项功能

**先纠正一句**：不是“没有 SPI”，而是“没有一组完全空闲的 SPI 引脚”。LQFP-64 上能用的 SPI 只有 SPI1/SPI2/SPI3；SPI4/5/6 的引脚没有引出。以 2026-11-19 主板网表为准，三组 SPI 的候选脚和占用如下：

| SPI | 候选脚（AF） | 当前占用 | 要腾出来就得放弃 |
|---|---|---|---|
| SPI1 | PA4 NSS / PA5 SCK / PA6 MISO / PA7 MOSI（AF5） | PA4/PA5 = `ADC_SPARE4`/`ADC_SPARE5`；PA6/PA7 = `ENC_RF_A`/`ENC_RF_B`（TIM3） | 右前编码器 + 2 个 ADC 备用；编码器换定时器很难，**不推荐** |
| SPI1 | PB3 SCK / PB4 MISO / PB5 MOSI（AF5），PA15 NSS | PB3 = `SWO`；PB4 = `SPARE_GPIO`；PB5 = `IMU_INT2`；PA15 = `EXP_SPI_CS` | SWO + PB4 + IMU_INT2；和 PB3/PB4/PB5 备选方案同一组脚，只是 AF 不同 |
| SPI2 | PB12 NSS / PB13 SCK / PB14 MISO / PB15 MOSI（AF5） | PB12/PB13 = `GIMBAL_LIMIT1`/`GIMBAL_LIMIT2`；PB14/PB15 = `GIMBAL_PWM1`/`GIMBAL_PWM2`（TIM12） | 2 路云台限位 + 2 路云台 PWM；**不推荐** |
| SPI2 | PB10 SCK / PB14 MISO / PB15 MOSI（AF5），PB12 NSS | PB10 = `STM_TX`（USART3→ESP32）；PB14/PB15 = 云台 PWM；PB12 = 云台限位1 | ESP32 串口 + 2 路云台 PWM + 1 路限位；**不推荐** |
| SPI2 | PB13 SCK（AF5）/ PC2 MISO / PC3 MOSI（AF5），软件 CS | PB13 = `GIMBAL_LIMIT2`；PC2/PC3 = `ADC_SPARE2`/`ADC_SPARE3` | 云台限位2 + 3 个 ADC 备用（PC2/PC3/PA4）；**本设计**；保住 SWO/PB4/IMU_INT2/UART4，且不碰 JTAG |
| SPI3 | PC10 SCK / PC11 MISO / PC12 MOSI（AF6），PA15 NSS | PC10/PC11 = `HOST_TX`/`HOST_RX`（UART4）；PC12 = 空；PA15 = `EXP_SPI_CS` | UART4 交互口（当前没有连接器）+ PB4 备用 GPIO + 关 JTAG（PA15/PB4）；**保住 SWO/IMU_INT2** |
| SPI3 | PB3 SCK / PB4 MISO / PB5 MOSI（AF6），PA15 NSS | PB3 = `SWO`；PB4 = `SPARE_GPIO`；PB5 = `IMU_INT2`；PA15 = `EXP_SPI_CS` | SWO + PB4 + IMU_INT2（备选：UART4 和 GIMBAL_LIMIT2 都必须保留时） |

怎么选：

1. **云台只留 1 路限位（或限位2 不用）→ 本设计选 SPI2 PB13/PC2/PC3**：CS 用 PC12，INT 用 PA4（`ADC_SPARE4`）。代价是 `GIMBAL_LIMIT2` + 3 个 ADC 备用；SWO/PB4/IMU_INT2/UART4 全保留，而且不用关 JTAG。按当前文档，限位座默认不装（[`gimbal-wiring-guide.md:203`](gimbal-wiring-guide.md#L203)），云台也标为“未来扩展；PWM/UART/电源/限位预留”（[`AI_WORK_NOTES.md:234`](AI_WORK_NOTES.md#L234)），并建议软限位优先（[`gimbal-power-wiring.md:228`](gimbal-power-wiring.md#L228)）。如果 PB13 没有实际接限位、固件也没有读它，这一路本来就是空的，这一方案的实际代价接近零。注意：主板网表里 PB13 只到 U1 的 34 脚，没有 RC；如果云台转接板/限位座装了 100 Ω 限位串阻（0603）和 1 nF 限位滤波电容（0603），改 SPI2 前要把它们去掉或 DNP，否则 10.5 MHz SCK 会被滤掉。
2. **UART4 交互口不需要 → 可改 SPI3 PC10/PC11/PC12**：CS 用 PA15，INT 用 PB4（`SPARE_GPIO`）。代价是 UART4 + PB4 + 关 JTAG；SWO、IMU_INT2、云台、编码器、ESP32 串口全保留。UART4 目前只是预留的 CLI/调试口，没有连接器。
3. **UART4 和 `GIMBAL_LIMIT2` 都必须保留 → 退回 SPI3 PB3/4/5 + PA15 CS + PC12 INT**：代价是 SWO + PB4 + IMU_INT2，并关 JTAG；或者看 §1.5 的 I2C。
4. **1 kHz 够用、不想占 SPI → I2C（§1.5）**：不占 SPI 脚、不用关 JTAG、保留 SWO/PB4/IMU_INT2；代价是 4 kHz 以上不够、I2C1 与 IMU 排针（J2）上的原 IMU 模块共享总线（如果 IMU 排针（J2）还插模块）。
5. 不推荐 SPI1 PA4–PA7（要动右前编码器）和 SPI2 PB10/PB14/PB15（要动 ESP32 串口/云台 PWM）。

### 1.5 备选：I2C（板载直连）

- ICM-42688-P 支持 I2C，器件侧最高 1 MHz（SPI 最高 24 MHz）。板载时可把 U1 的 13 脚（AP_SCL）接主控 PB8（I2C1_SCL）、14 脚（AP_SDA）接主控 PB9（I2C1_SDA）；不需要经过 J2，也不需要 6P XH。
- 接法：U1 的 13 脚 → PB8（`IMU_SCL`）；14 脚 → PB9（`IMU_SDA`）；12 脚 AP_CS 接 3V3（I2C 模式，按数据手册确认）；1 脚 AP_AD0 接地选 0x68；4 脚 INT1 → PD2 或 PB5（`IMU_INT1`/`IMU_INT2`）。SDA/SCL 各用 4.7 kΩ 上拉电阻（0402）上拉到 3V3；INT1 用 10 kΩ 上拉电阻（0402）。主板 I2C1 网表里没有上拉，必须在本电路上加；如果 IMU 排针（J2）上还插着带 4.7 kΩ 上拉的原 IMU 模块，只装一组，避免并联过强。
- 带宽：1 kHz ODR、每次读 12 字节，400 kHz 约 0.33 ms，1 MHz 约 0.13 ms；1 kHz 控制完全够。4 kHz 时 400 kHz 已经占满（1.3 ms > 0.25 ms），1 MHz 也只剩一半余量；2 KB FIFO 突发在 400 kHz 要约 45 ms，SPI 10 MHz 约 1.6 ms。要高 ODR、FIFO 突发、低抖动，还是 SPI。
- 结论：1 kHz 姿态/里程计用 I2C 足够，而且不占任何 SPI 脚、不用关 JTAG、保留 SWO/PB4/IMU_INT2；代价是 I2C1 与原 IMU 模块共享、上拉/地址要管、4 kHz 以上不够。当前设计仍选 SPI2；如果改 I2C，§2–§8 的 SPI 走线要另写一版 I2C 版，不能照抄。

---

## 2. 与主控的连接（板载直连，无连接器）

### 2.1 网络映射

| ICM-42688-P 脚 | 信号 | 主控脚 | 复用/模式 | 网络名 |
|---|---|---|---|---|
| 13 脚 AP_SCL/AP_SCLK | SCK | 34 脚 PB13 | SPI2_SCK（AF5） | `GYRO_SCK` |
| 1 脚 AP_SDO/AP_AD0 | MISO | 10 脚 PC2 | SPI2_MISO（AF5） | `GYRO_MISO` |
| 14 脚 AP_SDA/AP_SDIO/AP_SDI | MOSI | 11 脚 PC3 | SPI2_MOSI（AF5） | `GYRO_MOSI` |
| 12 脚 AP_CS | CS | 53 脚 PC12 | GPIO 输出，默认高 | `GYRO_CS` |
| 4 脚 INT1 | INT | 20 脚 PA4 | GPIO 输入/EXTI4，下降沿 | `GYRO_INT` |
| 8 脚 VDD | 电源 | 主板 `+3V3_SYS` | — | `+3V3_SYS` |
| 5 脚 VDDIO | 电源 | 主板 `+3V3_SYS` | — | `+3V3_SYS` |
| 2/3/6/7/10/11 脚 | GND/RESV | 主板 `GND` | — | `GND` |

- 主控 `+3V3_SYS` 的 19/32/48/64 脚是 VDD，18/63 脚是 VSS；板载电路直接接到这两个网络，不再经过连接器。
- **不再新增 J15**：原“主板 8P XH 针座（J15）”的计划取消。主板原理图上没有 J15，也没有 8P XH 线束。
- **J2 保留**：IMU 排针（J2）仍可插原 IMU 模块；本板载陀螺仪不占用 J2。J2 的 4 脚仍是 `IMU_INT2`（PB5），调试排针（J1）6 脚仍是 `SWO`（PB3），都不要改 NC。

### 2.2 测试点（建议，不进 BOM）

| 测试点 | 接到 | 用途 |
|---|---|---|
| TP1 | `+3V3_SYS` | 量 3.3 V、注入电流 |
| TP2 | `GND` | 地参考 |
| TP3 | `GYRO_SCK`（PB13/U1.13） | 看 SPI 时钟 |
| TP4 | `GYRO_MISO`（PC2/U1.1） | 看 MISO |
| TP5 | `GYRO_MOSI`（PC3/U1.14） | 看 MOSI |
| TP6 | `GYRO_CS`（PC12/U1.12） | 看片选 |
| TP7 | `GYRO_INT`（PA4/U1.4） | 看 data-ready 中断 |

- 测试点用 Ø1.0 mm 裸铜焊盘，放在主板边角或 U1 附近，不装器件；如果板面紧张，至少保留 TP1、TP2、TP7。
- 没有测试点时，只能拿探头点 U1 引脚，容易短路；不建议省掉 TP7（INT）和 TP1（3V3）。

---

## 3. 原理图

### 3.1 器件清单

| 器件（参数 + 作用 + 封装） | 图上位号 | 数量 | 装配 |
|---|---|---|---|
| ICM-42688-P 六轴 IMU（LGA-14 3.0×2.5 mm） | U1 | 1 | 必装 |
| 100 nF ±10% 50 V X7R 高频去耦电容（0402） | C1、C2 | 2 | 必装 |
| 2.2 µF ±10% 6.3 V X5R 储能电容（0402） | C3 | 1 | 必装 |
| 10 kΩ ±1% INT 上拉电阻（0402） | R1 | 1 | 必装 |
| 10 kΩ ±1% CS 上拉电阻（0402） | R2 | 1 | 必装 |
| 470 Ω ±1% LED 限流电阻（0402） | R3 | 1 | 可选，默认不装 |
| 翠绿 LED，Vf 2.5–3.6 V（0805） | LED1 | 1 | 可选，默认不装 |

- 本方案 **6 个必需电气件**；R3/LED1 是可选电源指示，主板上已有电源指示时默认不装，BOM 只下 6 件。
- **不再有** 8P XH 针座、8P XH 线束、独立小板、M3 安装孔。旧方案的 25 个器件删到 6 个必需件（装 LED 则 8 个）。
- 可选晶振组（默认 DNP）见 §3.4，不计入主 BOM。

### 3.2 逐脚连线（照着画）

下表每一行是一段实际连接；“主板 `+3V3_SYS`”和“主板 `GND`”指主板的电源/地网络，最终汇到主控 VDD/VSS 和主板地平面。

| # | 从（器件 + 脚） | 到（器件 + 脚） | 网络名 |
|---|---|---|---|
| 1 | ICM-42688-P 六轴 IMU（LGA-14）8 脚 VDD | 主板 `+3V3_SYS`；100 nF 去耦电容（0402，C1，VDD 侧）2 脚；2.2 µF 储能电容（0402）2 脚；10 kΩ INT 上拉电阻（0402）1 脚；10 kΩ CS 上拉电阻（0402）1 脚；470 Ω LED 限流电阻（0402）1 脚（可选） | `+3V3_SYS` |
| 2 | ICM-42688-P 六轴 IMU（LGA-14）5 脚 VDDIO | 主板 `+3V3_SYS`；100 nF 去耦电容（0402，C2，VDDIO 侧）2 脚 | `+3V3_SYS` |
| 3 | ICM-42688-P 六轴 IMU（LGA-14）2 脚 RESV | 主板 `GND` | `GND` |
| 4 | ICM-42688-P 六轴 IMU（LGA-14）3 脚 RESV | 主板 `GND` | `GND` |
| 5 | ICM-42688-P 六轴 IMU（LGA-14）6 脚 GND | 主板 `GND` | `GND` |
| 6 | ICM-42688-P 六轴 IMU（LGA-14）7 脚 RESV | 主板 `GND` | `GND` |
| 7 | ICM-42688-P 六轴 IMU（LGA-14）10 脚 RESV | 主板 `GND` | `GND` |
| 8 | ICM-42688-P 六轴 IMU（LGA-14）11 脚 RESV | 主板 `GND` | `GND` |
| 9 | 100 nF 去耦电容（0402，C1，VDD 侧）1 脚 | 主板 `GND` | `GND` |
| 10 | 100 nF 去耦电容（0402，C2，VDDIO 侧）1 脚 | 主板 `GND` | `GND` |
| 11 | 2.2 µF 储能电容（0402）1 脚 | 主板 `GND` | `GND` |
| 12 | ICM-42688-P 六轴 IMU（LGA-14）13 脚 AP_SCL/AP_SCLK | 主控 34 脚 PB13 | `GYRO_SCK` |
| 13 | ICM-42688-P 六轴 IMU（LGA-14）1 脚 AP_SDO/AP_AD0 | 主控 10 脚 PC2 | `GYRO_MISO` |
| 14 | ICM-42688-P 六轴 IMU（LGA-14）14 脚 AP_SDA/AP_SDIO/AP_SDI | 主控 11 脚 PC3 | `GYRO_MOSI` |
| 15 | ICM-42688-P 六轴 IMU（LGA-14）12 脚 AP_CS | 主控 53 脚 PC12 | `GYRO_CS` |
| 16 | 10 kΩ CS 上拉电阻（0402）2 脚 | ICM-42688-P 六轴 IMU（LGA-14）12 脚/主控 53 脚 PC12 | `GYRO_CS` |
| 17 | ICM-42688-P 六轴 IMU（LGA-14）4 脚 INT1 | 主控 20 脚 PA4 | `GYRO_INT` |
| 18 | 10 kΩ INT 上拉电阻（0402）2 脚 | ICM-42688-P 六轴 IMU（LGA-14）4 脚/主控 20 脚 PA4 | `GYRO_INT` |
| 19 | 470 Ω LED 限流电阻（0402）2 脚 | 翠绿 LED（0805）阳极 | `LED_A`（可选） |
| 20 | 翠绿 LED（0805）阴极 | 主板 `GND` | `GND`（可选） |
| 21 | ICM-42688-P 六轴 IMU（LGA-14）9 脚 INT2/FSYNC/CLKIN | 不接（NC）；只有装 §3.4 的可选晶振时才接 | — |

两处容易错的地方：

- **ICM-42688-P 六轴 IMU（LGA-14）的 2/3/7/10/11 脚是 RESV，必须接地，不是悬空**。ICM-42688-P 数据手册明确要求这几个保留脚接 GND；悬空会引入噪声或让芯片工作异常。
- **ICM-42688-P 六轴 IMU（LGA-14）的 9 脚默认是 INT2 输出**，本设计不接。不要把 3.3 V 或 INT 网络接到 9 脚；如果装可选晶振，按 §3.4 串 1 kΩ，并确保固件不把 INT2 配成推挽。
- **10 kΩ INT 上拉电阻（0402）/10 kΩ CS 上拉电阻（0402）是上拉到 3.3 V，不是下拉到地**；翠绿 LED（0805）阳极经 470 Ω LED 限流电阻（0402）到 3.3 V，阴极到地（如果装 LED）。
- **SPI2 的 SCK/MISO/MOSI 直接到主控，不再经过 8P 针座**；CS 由 PC12 驱动，INT 由 PA4 读，两个上拉都在板载电路上。

### 3.3 网络汇总

| 网络 | 连接的器件与脚 |
|---|---|
| `+3V3_SYS` | 主板 19/32/48/64 脚 VDD；ICM-42688-P 六轴 IMU（LGA-14）5 脚 VDDIO、8 脚 VDD；100 nF 去耦电容（0402，C1，VDD 侧）2 脚、100 nF 去耦电容（0402，C2，VDDIO 侧）2 脚、2.2 µF 储能电容（0402）2 脚；10 kΩ INT 上拉电阻（0402）1 脚、10 kΩ CS 上拉电阻（0402）1 脚、470 Ω LED 限流电阻（0402）1 脚（可选） |
| `GND` | 主板 18/63 脚 VSS 和地平面；ICM-42688-P 六轴 IMU（LGA-14）2、3、6、7、10、11 脚；100 nF 去耦电容（0402，C1，VDD 侧）1 脚、100 nF 去耦电容（0402，C2，VDDIO 侧）1 脚、2.2 µF 储能电容（0402）1 脚；翠绿 LED（0805）阴极（可选） |
| `GYRO_SCK` | ICM-42688-P 六轴 IMU（LGA-14）13 脚；主控 34 脚 PB13 |
| `GYRO_MISO` | ICM-42688-P 六轴 IMU（LGA-14）1 脚；主控 10 脚 PC2 |
| `GYRO_MOSI` | ICM-42688-P 六轴 IMU（LGA-14）14 脚；主控 11 脚 PC3 |
| `GYRO_CS` | ICM-42688-P 六轴 IMU（LGA-14）12 脚；10 kΩ CS 上拉电阻（0402）2 脚；主控 53 脚 PC12 |
| `GYRO_INT` | ICM-42688-P 六轴 IMU（LGA-14）4 脚；10 kΩ INT 上拉电阻（0402）2 脚；主控 20 脚 PA4 |
| `LED_A` | 470 Ω LED 限流电阻（0402）2 脚；翠绿 LED（0805）阳极（可选） |

### 3.4 可选：32.768 kHz CLKIN（默认不装）

如果以后确实需要把 ODR 精度从内部 RC 的 ±8% 提到晶振的 ±20 ppm，再装这一组。**默认 DNP，不贴；PCB 可以预留焊盘，也可以完全不画。**

| 器件（参数 + 作用 + 封装） | 图上位号 | 数量 |
|---|---|---|
| 32.768 kHz 有源晶振，1.8–3.3 V，CMOS，3225 4P（YSO120TK） | U2 | 1 |
| 100 nF ±10% 50 V X7R 晶振去耦电容（0402） | C4 | 1 |
| 15 pF ±5% 50 V C0G 晶振负载电容（0402） | C5 | 1 |
| 1 kΩ ±1% 晶振输出串阻（0402） | R4 | 1 |
| 100 kΩ ±1% OE 上拉电阻（0402，可选） | R5 | 1 |
| Ø1.0 mm 测试点（插件，可选） | TP1 | 1 |

可选部分的逐脚连线：

| # | 从（器件 + 脚） | 到（器件 + 脚） | 网络名 |
|---|---|---|---|
| O1 | 32.768 kHz 有源晶振（3225）4 脚 VDD | 主板 `+3V3_SYS`；100 nF 晶振去耦电容（0402）2 脚；100 kΩ OE 上拉电阻（0402）1 脚 | `+3V3_SYS` |
| O2 | 32.768 kHz 有源晶振（3225）2 脚 GND | 主板 `GND` | `GND` |
| O3 | 32.768 kHz 有源晶振（3225）1 脚 OE/NC | 100 kΩ OE 上拉电阻（0402）2 脚 | `EX_OE` |
| O4 | 32.768 kHz 有源晶振（3225）3 脚 OUT | 15 pF 负载电容（0402）2 脚；1 kΩ 晶振输出串阻（0402）1 脚 | `EX_CLK_OSC` |
| O5 | 1 kΩ 晶振输出串阻（0402）2 脚 | ICM-42688-P 六轴 IMU（LGA-14）9 脚 INT2/FSYNC/CLKIN | `EX_CLK` |
| O6 | 100 nF 晶振去耦电容（0402）1 脚 | 主板 `GND` | `GND` |
| O7 | 15 pF 负载电容（0402）1 脚 | 主板 `GND` | `GND` |
| O8 | Ø1.0 mm 测试点（插件） | 32.768 kHz 有源晶振（3225）3 脚 | `EX_CLK_OSC` |

注意事项：

- **32.768 kHz 有源晶振（3225）的 1 脚悬空即使能**（YSO120TK 内部上拉，高电平或悬空为使能）；100 kΩ OE 上拉电阻（0402）只是抗干扰，不装也能工作。不要把它拉低，拉低是关输出。
- **15 pF 负载电容（0402）放在 32.768 kHz 有源晶振（3225）的 3 脚输出侧**，不要放在 ICM-42688-P 六轴 IMU（LGA-14）的 9 脚侧；1 kΩ 晶振输出串阻（0402）在 15 pF 负载电容（0402）之后、ICM-42688-P 六轴 IMU（LGA-14）的 9 脚之前。
- **固件必须做**：`INTF_CONFIG5`（bank1 0x7B）bits[2:1]=0b10 选 PIN9=CLKIN；`RTC_MODE`（0x4D）bit2=1；等晶振 3 ms 启动后再切；清 `INT_CONFIG1`（0x64）bit4=0；如果读 FIFO 时间戳，按数据手册 12.7 节乘 32.768/30，否则时间戳偏 9.2%；**不要**再把 INT2 配成输出。
- 1 kΩ 晶振输出串阻（0402）是保险：万一固件把 INT2 误配成推挽，最多 3.3 mA 灌进晶振输出，两边都不会坏。
- 装了晶振后，ICM-42688-P 六轴 IMU（LGA-14）的 9 脚不再是 NC，而是 `EX_CLK`；§3.2 的第 21 行改为“ICM-42688-P 六轴 IMU（LGA-14）9 脚 → 1 kΩ 晶振输出串阻（0402）2 脚（`EX_CLK`）”。

---

## 4. BOM

主 BOM（板载集成）：

| 参数 | 作用 | 封装 | 立创编号 / 型号 | 图上位号 | 装配 |
|---|---|---|---|---|---|
| ICM-42688-P 六轴 IMU | 角速度/加速度测量，SPI 从机 | LGA-14 3.0×2.5 mm | C1850418 | U1 | 必装 |
| 100 nF ±10% 50 V X7R | VDD/VDDIO 高频去耦 | 0402 | C77020 | C1、C2 | 必装 |
| 2.2 µF ±10% 6.3 V X5R | 3.3 V 储能去耦 | 0402 | C12530 | C3 | 必装 |
| 10 kΩ ±1% | INT1 上拉 | 0402 | C25744（0402WGF1002TCE） | R1 | 必装 |
| 10 kΩ ±1% | CS 上拉 | 0402 | C25744（0402WGF1002TCE） | R2 | 必装 |
| 470 Ω ±1% | LED 限流 | 0402 | C25117（0402WGF4700TCE） | R3 | 可选，默认不装 |
| 翠绿 LED，Vf 2.5–3.6 V | 上电指示 | 0805 | C84260 | LED1 | 可选，默认不装 |

- **必需 6 件**；R3/LED1 只在需要本地电源指示时装，主板上已有电源指示时默认不装。
- **价格（2026-11-20 立创商城人民币零售价，仅器件）**：必需 6 件约 **¥157.32**，其中 ICM-42688-P（C1850418）¥157.245，占 99.9%；加 R3/LED1 约 ¥157.39；加可选晶振组约再 +¥2.02。不含 PCB、钢网、SMT 工程费/上料费/手焊费。
- **C1850418 嘉立创 SMT 库存 0**（立创商城零售库存约 4700 片，¥157.245/片）；SMT 直贴下不了单，必须外购后手焊/寄件，或换型。下单前复核库存。
- **不再有** 8P XH 针座、8P XH 双母头线束、M3 安装孔；这些物料从 BOM 删除。
- 旧方案的 1×8 排针（C492407）不要套用；新方案没有排针。

可选晶振组（默认 DNP，不贴，不计入主 BOM）：

| 参数 | 作用 | 封装 | 立创编号 / 型号 | 图上位号 |
|---|---|---|---|---|
| 32.768 kHz 有源晶振，1.8–3.3 V，CMOS | CLKIN 时钟（可选） | 3225 4P | C725996（YSO120TK） | U2 |
| 100 nF ±10% 50 V X7R | U2 去耦 | 0402 | C77020 | C4 |
| 15 pF ±5% 50 V C0G | 晶振负载 | 0402 | C1548（0402CG150J500NT） | C5 |
| 1 kΩ ±1% | 晶振输出串阻 | 0402 | C11702（0402WGF1001TCE） | R4 |
| 100 kΩ ±1% | OE 上拉（可选） | 0402 | C25741（0402WGF1003TCE） | R5 |
| Ø1.0 mm 测试点 | `EX_CLK` 观测 | 插件/裸铜焊盘 | 裸铜焊盘 0 元；插件针按型号搜 | TP1 |

除 TP1 测试点外，主 BOM 与可选晶振组的立创编号都已核到；TP1 建议直接做 Ø1.0 mm 裸铜焊盘，不买插件测试针。若买插件测试针，按“Ø1.0 mm 测试点”型号搜。

---

## 5. 主板侧改动

### 5.1 网络改名与让位

| 旧网络/引脚 | 新网络/状态 | 说明 |
|---|---|---|
| `GIMBAL_LIMIT2`（主控 34 脚 PB13） | `GYRO_SCK` | 放弃限位2；限位2 座/RC 去掉或 DNP；PB12 的 `GIMBAL_LIMIT1` 保留 |
| `ADC_SPARE2`（主控 10 脚 PC2） | `GYRO_MISO` | 原 ADC 备用 |
| `ADC_SPARE3`（主控 11 脚 PC3） | `GYRO_MOSI` | 原 ADC 备用 |
| 主控 53 脚 PC12 = NC | `GYRO_CS` | 新用途；GPIO 输出，默认高 |
| `ADC_SPARE4`（主控 20 脚 PA4） | `GYRO_INT` | 新用途；EXTI4，下降沿 |
| `SWO`（PB3）、`SPARE_GPIO`（PB4）、`IMU_INT2`（PB5）、`EXP_SPI_CS`（PA15）、`HOST_TX`/`HOST_RX`（PC10/PC11） | 不变 | 保持原功能；调试排针（J1）6 脚仍为 SWO，IMU 排针（J2）4 脚仍为 IMU_INT2 |

- **不再新增 J15**；原“主板 8P XH 针座（J15）”计划取消。主板原理图直接画 U1 到主控的 5 根信号和电源/地。
- 旧网络名 `GIMBAL_LIMIT2`、`ADC_SPARE2`、`ADC_SPARE3`、`ADC_SPARE4` 在原理图上要全部改掉，避免留下两个同名网络或旧飞线。

### 5.2 限位2 座/RC 的处理

- **`GIMBAL_LIMIT2` 的限位座/RC**：如果云台转接板或限位座上装了 100 Ω 限位串阻（0603）和 1 nF 限位滤波电容（0603），改 SPI2 前要拆掉或 DNP；如果还接着限位开关，也要拆/移走。PB13 现在是 SCK，不能再挂 1 nF。
- **`GIMBAL_LIMIT1`（PB12）保留**：不要动 PB12 的限位电路；固件只读 PB12。
- 如果原限位2 网络在主板上有测试点/排针，改成 `GYRO_SCK` 后要确认没有其他器件挂在这条网络上。

### 5.3 IMU 排针（J2）/调试排针（J1）保持

- **IMU 排针（J2）4 脚保持 `IMU_INT2`（PB5）**：不要改 NC；IMU 排针（J2）上的原 IMU 模块可同时用 INT1（PD2）和 INT2（PB5），固件按需配置 PB5 的 EXTI。本板载陀螺仪不经过 J2。
- **调试排针（J1）6 脚保持 `SWO`（PB3）**：不要改 NC；SWD 调试和下载不受影响，不用关 JTAG。
- 如果以后决定不再使用 J2，可以把 J2 及其网络整体删掉，PB5 释放；这是另一项改动，不在本设计内。

### 5.4 固件

- **不用关 JTAG**：PA13/PA14 仍是 SWDIO/SWCLK；PA15（`EXP_SPI_CS`）、PB3（`SWO`）、PB4（`SPARE_GPIO`）、PB5（`IMU_INT2`）保持原功能，不做调试端口重映射。
- **SPI2 AF5**：PB13=SCK、PC2=MISO、PC3=MOSI；PC12 用普通 GPIO 做软件片选，默认高；SPI 模式 0 或 3，先 5.25 MHz 跑通，稳定后再升到 10.5 MHz 或更高（板载走线短，ICM-42688-P 支持到 24 MHz）。
- **PA4 做 EXTI4**：下降沿触发；模块已有 10 kΩ 上拉，主控内部上拉可开可不开；中断服务里读 `INT_STATUS` 清标志。
- **PB13 不再作限位输入**：删掉 `GIMBAL_LIMIT2` 的 EXTI/读取逻辑；PB12 的 `GIMBAL_LIMIT1` 保持。如果固件同时用两路限位，要改成只读 PB12。
- **ICM-42688-P 初始化**：清 `INT_CONFIG1`（0x64）bit4 `INT_ASYNC_RESET`；INT1 默认开漏/低有效，可以用默认，也可以改推挽/高有效，但改了要同步 EXTI 边沿；不启用 CLKIN 时不要碰 `INTF_CONFIG5`/`RTC_MODE`。
- **IMU 排针（J2）上的原模块**：继续 I2C1（PB8/PB9）+ INT1（PD2）+ INT2（PB5）；IMU 排针（J2）4 脚保持 `IMU_INT2`，固件按需配置 PB5 的 EXTI，不要改 NC。
- **DMA**：1 kHz 读 12 字节，轮询/中断足够；要用 FIFO 再开 SPI2 DMA。

### 5.5 需要同步更新的旧文档

- [`hardware-plan.md:12`](hardware-plan.md#L12) 还写着“扩展 SPI = SPI3（PC10–PC12）”；实际网表已是 PC10/PC11 = `HOST_TX`/`HOST_RX`，本设计走 SPI2。建议改成“SPI2 = PB13/PC2/PC3（AF5）+ PC12 CS + PA4 INT；陀螺仪板载，无连接器”。
- [`hardware-plan.md:160-161`](hardware-plan.md#L160-L161) 把 PB12/PB13 写成两路云台限位；现在 PB12 保留 `GIMBAL_LIMIT1`，PB13 改作 `GYRO_SCK`，限位2 去掉或 DNP。
- [`hardware-plan.md:380`](hardware-plan.md#L380) 的“J15 SPI 1×6”作废；不再新增 J15，陀螺仪直接画在主板上。
- [`uart-extension-plan.md:54`](uart-extension-plan.md#L54) 的 PB3/PB4/PB5 补偿方案**不采用**；PB3/PB4/PB5 保留 `SWO`/`SPARE_GPIO`/`IMU_INT2`，不要按那份补偿方案改。
- 以上是文档同步建议；本次没有改主板 EDA 文件，也没有改固件。

---

## 6. PCB 布局与机械

1. **U1 靠近主控放**：SPI2 的 PB13（34 脚）、PC2（10 脚）、PC3（11 脚）比较分散，U1 放在主控附近、走线短而直；SCK 和 MISO 之间用地线/地铜隔开，不要长距离并行。
2. **远离干扰源**：U1 远离电机驱动、MOSFET、电感、电池和 AMS1117；远离 HSE 8 MHz 晶振及其走线；不要放在板边、螺丝孔应力区或会弯折的区域。主板电机电流大，IMU 下方地平面要完整，不要被功率地/电机地割裂。
3. **没有独立小板/安装孔**：不再有 2 个 M3 孔、8P 针座和线束；U1 直接跟主板一起安装。主板如果硬固定在底盘上，陀螺会直接感受底盘振动，装配时注意减振/加橡胶垫，固件再做低通/陷波；不要把 U1 放在悬臂或薄板上。
4. **去耦**：100 nF 去耦电容（0402，C1）贴 ICM-42688-P 六轴 IMU（LGA-14）8 脚 VDD，100 nF 去耦电容（0402，C2）贴 5 脚 VDDIO，2.2 µF 储能电容（0402，C3）贴 8 脚附近；三个电容的地脚就近打过孔到地平面，电源—电容—芯片的环路尽量小。
5. **3V3 走线 ≥0.3 mm**：从主板 `+3V3_SYS` 到 C1/C3 再到 U1 8 脚，从 `+3V3_SYS` 到 C2 再到 U1 5 脚，分两路短而粗；不要从电机/舵机大电流路径上取电。
6. **SPI 走线**：SCK 从 U1 13 脚到主控 34 脚 PB13；MISO 从 U1 1 脚到主控 10 脚 PC2；MOSI 从 U1 14 脚到主控 11 脚 PC3；CS 从 U1 12 脚到主控 53 脚 PC12；INT 从 U1 4 脚到主控 20 脚 PA4。板载走线短，通常不需要串阻；如果 U1 离主控超过 10 cm，SCK/MOSI/CS 各串 22–33 Ω，并把时钟降到 5.25 MHz。
7. **地平面**：顶层/底层都铺地，U1 的 2/3/6/7/10/11 脚、C1/C2/C3 的地脚多打过孔；U1 下方不要走其他高速/大电流线。主板层叠按现有设计，不强制 2 层。
8. **丝印**：标 U1 的 1 脚点、X/Y/Z 箭头、板名/版本/日期；测试点标 `3V3`、`GND`、`SCK`、`MISO`、`MOSI`、`CS`、`INT`。没有连接器，不需要 1 脚方焊盘/防呆标记。
9. **装配方向**：U1 焊到主板后，记录主板 X/Y/Z 与机器人坐标的对应关系（符号、旋转 90°/180° 的情况），写进固件常量；不要靠“试出来”而不记录。
10. **热**：不做加热，但也不要把 U1 贴在电机、电调、电池或 AMS1117 旁边；IMU 自发热 <0.01 W，主要热源是环境。固件用 IMU 内部温度做零偏温补，并在调试记录里同时记温度和零偏。
11. **可选晶振**：如果装 32.768 kHz 有源晶振（3225，U2），放在 U1 的 9 脚附近；100 nF 晶振去耦电容（0402，C4）贴 U2 4 脚；15 pF 负载电容（0402，C5）贴 U2 3 脚；1 kΩ 晶振输出串阻（0402，R4）在 C5 之后、U1 9 脚之前；`EX_CLK` 走线短，Ø1.0 mm 测试点（插件，TP1）放在板边；不装时这些焊盘可以省略。

---

## 7. 调试与验收

| 步骤 | 操作 | 判据 |
|---|---|---|
| 1 | 目检 + 万用表：3V3 对 GND、CS 对 3V3、INT 对 3V3、RESV 对 GND | 3V3–GND 无短路（≥100 Ω；装 LED 时 LED 支路会拉低读数，属正常）；CS/INT 对 3V3 约 10 kΩ；RESV 对 GND <1 Ω |
| 2 | 在 TP1/TP2 加 3.3 V（限流 50 mA） | 电流 <5 mA；装 LED 时 LED 亮；3.3 V 稳定 |
| 3 | 接主控，读 `WHO_AM_I`（0x75） | 0x47；SPI 模式 0 或 3；CS 低有效 |
| 4 | 写/读一个寄存器（如 `PWR_MGMT0`） | 读回与写入一致；无位错、无移位 |
| 5 | 配置 INT1 为 data-ready，示波器看 TP7 | 低有效脉冲；静态高（10 kΩ 上拉）；频率 = ODR |
| 6 | 静止 60 s，读 gyro/accel | 零偏/噪声与数据手册同量级；无跳变、无丢帧 |
| 7 | 绕 X/Y/Z 旋转，检查符号和量程 | 符号与丝印一致；±2000 dps / ±16 g 不溢出 |
| 8 | 记录温度和零偏，做温补 | 温度变化时零偏可重复；数据如实记录，不伪造 |
| 9 | 检查改线后的让位：PB13 原限位2 座/RC、IMU 排针（J2）4 脚、调试排针（J1）6 脚 | PB13 到 U1 13 脚连通、到原限位2 座断开；若原限位座装了 100 Ω + 1 nF，已去掉/DNP；IMU 排针（J2）4 脚仍为 `IMU_INT2`、调试排针（J1）6 脚仍为 `SWO`，没有改 NC；主板没有新增 J15 |

> 上表是判据，不是实测结果。实测数据要写进调试记录（温度、零偏、噪声、SPI 时钟、U1 位置/轴向），不能拿“应该没问题”代替。

---

## 8. 与旧方案的差异

| 项 | 旧方案（[`陀螺仪.enet`](../netlist/陀螺仪.enet)） | 本方案（板载集成） |
|---|---|---|
| 形态 | 独立小板 + 1×8 排针；后来改为 8P XH + 线束 | 直接画在 DollyBot_V1 主板上，无连接器、无线束、无独立小板 |
| 接口 | 1×8 2.54：INT/PWM/CS/MISO/MOSI/CLK/GND/VCC；8P XH：3V3/GND/SCK/GND/MISO/MOSI/CS/INT | U1 直接到主控：SCK=PB13、MISO=PC2、MOSI=PC3、CS=PC12、INT=PA4；无 J15 |
| SPI | 没有落到 MCU；主板没有 SPI 口 | SPI2 AF5：PB13/PC2/PC3 + PC12 CS + PA4 INT；去掉 GIMBAL_LIMIT2，保留 SWO/PB4/IMU_INT2/UART4 |
| 温控 | 加热开关（Q1）、8×220 Ω（R11–R18）、栅极电阻（R20、R22），120 mA/0.396 W | 不做；无 PWM 资源、3.3 V 预算不够、无过温保护 |
| 晶振 | 32.768 kHz 晶振（U2）+ 去耦/负载电容（C11、C12）+ 测试点（TP1） | 默认不装；可选 DNP（§3.4） |
| INT | 无上拉 | 10 kΩ 上拉（R1） |
| CS | 无上拉 | 10 kΩ 上拉（R2） |
| 防护 | 裸 1×8 排针，无防呆/锁扣/保险；8P XH 有锁扣/防呆 | 无连接器，不存在插反/振松；用测试点 bring-up，无热插拔保护需求 |
| 器件数 | 25 | 6 个必需电气件 + 2 个可选（R3/LED1）；无 J1、无线束、无 M3 |
| 尺寸 | 独立小板，旧板未知；后建议 22×22 mm | 不新增板框/安装孔，随主板 |

---

## 9. 待核实/开放项

| 项 | 现状 | 需要确认 |
|---|---|---|
| 10 kΩ / 470 Ω 0402 | 已核 C25744（10 kΩ ±1%）、C25117（470 Ω ±1%），基础库 | 下单前复核库存；R1/R2 必装，R3 可选 |
| LED 是否装 | R3/LED1 默认不装 | 主板上已有电源指示就不装；要本地指示再装，BOM 加 2 件 |
| 15 pF / 1 kΩ / 100 kΩ / 测试点 | 已核 C1548 / C11702 / C25741；TP1 建议裸铜焊盘 | 只有装可选晶振才需要；TP1 若买插件针，按型号搜 |
| 晶振装不装 | 默认 DNP | 固件是否实现 CLKIN；不实现就不装 |
| 接口方案取舍 | 已选 SPI2 PB13/PC2/PC3 + PC12 CS + PA4 INT；去掉 `GIMBAL_LIMIT2` | 若以后要恢复限位2，需改回 SPI3 PB3/4/5 或 I2C；UART4 可放弃时也可改 SPI3 PC10–PC12 |
| 限位2 座/RC | 默认不装；若装 100 Ω + 1 nF 需去掉/DNP | 否则 10.5 MHz SCK 会被滤掉；PB12 的限位1 保留 |
| IMU 排针（J2）4 脚 / 调试排针（J1）6 脚 | 保持 `IMU_INT2`（PB5）/ `SWO`（PB3） | 不改 NC；不用关 JTAG；J2 上的原 IMU 模块可同时用 INT1/INT2 |
| 布局/振动 | 未定 | U1 在主板的实际位置、轴向、减振方式、与电机/电感的距离；板载后陀螺直接受主板振动 |
| 测试点 | 建议 TP1–TP7 | 板面是否放得下；至少保留 TP1/TP2/TP7 |
| 旧文档 | `hardware-plan.md`/`uart-extension-plan.md` 仍是旧 SPI3/UART4 描述 | 同步 SPI2 PB13/PC2/PC3 + PC12 CS + PA4 INT；J15 取消；限位2 去掉 |
| 可拆卸性 | 当前不需要 | 如果以后要外接陀螺仪模块，再单独加 8P XH；不属于本板载方案 |

---

## 10. 参考资料

- 主板实际网表：[`Netlist_mainboard_2026-11-19.enet`](../netlist/Netlist_mainboard_2026-11-19.enet)
- 旧陀螺仪网表（历史）：[`陀螺仪.enet`](../netlist/陀螺仪.enet)
- 旧引脚/电源计划：[`hardware-plan.md:12`](hardware-plan.md#L12)、[`:236`](hardware-plan.md#L236)、[`:319`](hardware-plan.md#L319)、[`:380`](hardware-plan.md#L380)
- SPI3 补偿方案（未采用）：[`uart-extension-plan.md:54`](uart-extension-plan.md#L54)
- 云台限位预留/默认不装：[`gimbal-wiring-guide.md:203`](gimbal-wiring-guide.md#L203)、[`gimbal-power-wiring.md:228`](gimbal-power-wiring.md#L228)
- 连接器/文档格式先例：[`encoder-board-design.md`](encoder-board-design.md)
- ICM-42688-P 数据手册：[DS-000347](https://www.invensense.tdk.com/en-us/download-resource/ds-000347-icm-42688-p-datasheet)
- YSO120TK 数据手册：[YXC-YSO120TK.pdf](https://yxc.hk/uploadfiles/2025/08/YXC-YSO120TK.pdf)
- CLKIN 配置参考：[betaflight PR #13314](https://github.com/betaflight/betaflight/pull/13314)
