# 第二路交互串口方案

日期：2026-11-17　依据：`hardware/Netlist_mainboard_2026-11-17.enet`（87 器件 / 90 网络，2026-11-17 14:51 导出）
状态：方案待定稿，尚未改原理图、尚未布线（`pcb/PCB_mainboard.epcb2` 仍为空板）

## 1 现状核对

对外接口只有下面这些：

| 器件 | 类型 | 引脚与网络 | 用途 |
| --- | --- | --- | --- |
| U4 | 1×4 2.54 排针 | 1 STM_RX(PB11)、2 STM_TX(PB10)、3 GND、4 +3V3_SYS | **唯一对外 UART**，接 ESP32 |
| J1 | 1×6 2.54 | +3V3、SWDIO、SWCLK、GND、NRST、SWO | SWD 调试 |
| J2 | 1×6 2.54 | GND、+3V3、IMU_INT1(PD2)、IMU_INT2(PB5)、IMU_SCL(PB8)、IMU_SDA(PB9) | IMU 模块 |
| CN12–CN15 | 1×5 XH ×4 | 1/2 = PWM、DIR（各串 100 Ω）；5 = DRV_EN（CN12/CN15）或 GND（CN13/CN14）；**3、4 脚悬空** | 电机驱动板控制 |
| CN16/CN17 | XT30 | + MOTORPOWER、− GND | 驱动板电机电源 |
| CN1 | XT30 | 电池输入 | 电源 |
| U7 / U9 | 2P 排针 | 5V_ESP32 ←D3、5V_LOGIC ←D4 | 供电跳线 |

结论：

- **只有一个对外串口**，即 U4 = USART3（PB10/PB11），已经给 ESP32。
- **USART2 的 PA2/PA3 虽然命名成 `GIMBAL_TX`/`GIMBAL_RX`，但全网只有 U1 两个引脚，没有任何连接器**，等于画在纸上。
- `EXP_SPI_CS/SCK/MISO/MOSI`（PA15/PC10/PC11/PC12）同样只有 U1，没有扩展口。
- `DRV_FAULT`(PA11) 悬空；`LED_STATUS`(PC13)/`LED_FAULT`(PC14) 连 LED 和限流电阻都没有；`ENC_*` 八根线只到 U1。
- `5V_ESP32` 只在 U7.1，没有接出去，ESP32 目前只能靠自身 USB 供电。

## 2 UART 资源核算（STM32F405RGT6 LQFP64）

| 串口 | 可用引脚 | 现状冲突 | 能否直接用作交互口 |
| --- | --- | --- | --- |
| USART1 | PA9/PA10 或 PB6/PB7 | PA9 = ENC_RR_B、PA10 = DRV_EN、PB6/PB7 = ENC_LR_A/B | 否（占用四路编码器 + 驱动使能） |
| USART2 | PA2/PA3 | 网络名 GIMBAL_TX/RX，无连接器 | **可以**，但云台串口要另想办法 |
| USART3 | PB10/PB11 | 已接 ESP32（U4） | 否 |
| UART4 | **PC10/PC11**（AF8），或 PA0/PA1 | PC10/PC11 = EXP_SPI_SCK/MISO，均无连接器 | **可以** |
| UART5 | PC12(TX)/PD2(RX)（AF8） | PC12 = EXP_SPI_MOSI，PD2 = IMU_INT1 | 可以，代价是丢 SPI MOSI 与 IMU 中断 |
| USART6 | PC6/PC7 | PC6/PC7 = MOT_LF/MOT_RF_PWM（TIM8） | 否（等于废掉两路电机 PWM） |

结论：**USART1 与 USART6 确实不可用；真正能用的只有 UART4、UART5 和 USART2**。与 `hardware-plan.md` 第 45 行的判断一致。

## 3 推荐方案：UART4 @ PC10/PC11

### 3.1 理由

1. PC10/PC11 是相邻的一对引脚，本身就是 UART4_TX/RX 的 AF8 位置，不需要跨页飞线。
2. 这两根现在叫 `EXP_SPI_SCK`/`EXP_SPI_MISO`，但 **Expansion 页上没有任何扩展连接器，也没有任何器件使用 SPI3**；拿它做交互口，实际损失接近零。
3. 保留 USART2 给云台，不给未来留隐患。
4. UART4 与 USART3 属于不同外设，DMA 流互不冲突，运动帧和交互流量可以完全隔离。
5. PC12（原 `EXP_SPI_MOSI`）空出来，可以留作自由 GPIO（例如 RS485 的 DE、或第二串口的流控）。

### 3.2 代价与补偿

- 代价：SPI3 失去 SCK/MISO，等于放弃原计划的扩展 SPI。
- 若以后确实需要 SPI 扩展：把 SPI3 整体挪到 **PB3/PB4/PB5（AF6）**，同时放弃 SWO、`SPARE_GPIO`(PB4)、J2.4 的 `IMU_INT2`(PB5)；IMU 只用 INT1(PD2) 足够。此补偿不影响 UART4。

### 3.3 备选方案

| 方案 | 引脚 | 代价 | 适用 |
| --- | --- | --- | --- |
| B | USART2 PA2/PA3 | 云台串口要改（云台若用 PB14/PB15 PWM 舵机则代价为零） | 想要功能最全、带流控/ISO7816 的串口 |
| C | UART5 PC12/PD2 | 丢 SPI3 MOSI + IMU_INT1 | 只想加一个 TX 为主的调试口 |
| D | USB-C + CH340C 接 UART4 或 USART2 | BOM +¥3–5，多一个 USB 口 | 想插上就是 COM 口、并顺带 5V 调试供电 |

## 4 J19 接口定义（推荐）

与 U4 同序，线材可互换：

| 引脚 | 网络名 | 说明 |
| --- | --- | --- |
| 1 | `HOST_RX` | UART4_RX(PC11)，交互设备 TX → STM32 |
| 2 | `HOST_TX` | UART4_TX(PC10)，STM32 → 交互设备 RX |
| 3 | GND | 必须共地 |
| 4 | `+3V3_SYS` | 仅供电，不要求；经 0 Ω/焊盘跳线可选断开 |

保护与电平：

- `HOST_TX` 串 33 Ω，`HOST_RX` 串 100 Ω（与现有电机信号 100 Ω 风格一致）。
- `HOST_RX`/`HOST_TX` 各加一颗 PESD5V0S1BA 到 GND；若走长线再加 USBLC6-2SC6。
- PC10/PC11 为 FT（5 V 容忍）引脚，接 5 V USB-TTL 模块不会立刻损坏，但**推荐只用 3.3 V 逻辑**，禁止把 5 V 灌进 +3V3 引脚。
- 若要给串口屏/单片机供 5 V：另加 1 个脚或焊盘跳线到 `5V_AUX`（U8 PPTC 0.5 A 之后），默认断开，避免与外部供电倒灌。

器件：`HDR-TH_4P-P2.54-V-M`，丝印 `J19`，旁边标 `HOST UART 3V3`。

## 5 原理图改动清单（Expansion 页）

1. 放置 J19（1×4 2.54），按上表连 4 根线。
2. 把 `EXP_SPI_SCK` 改名 `HOST_TX`、`EXP_SPI_MISO` 改名 `HOST_RX`，并确认 U1.51/U1.52 上不再有旧的 SPI 网络名残留。
3. 加 R（33 Ω / 100 Ω）与 TVS（可选贴装）。
4. `EXP_SPI_CS`(PA15) 与 `EXP_SPI_MOSI`(PC12) 保留为普通网络，暂不接任何东西；若决定彻底放弃 SPI3，可把它们标成 `SPARE_*` 并接到 2 针测试点。
5. 跑 ERC：重点看 U1.51/U1.52 是否还挂在线路上、以及 J19 是否有单端网络。
6. 若同时实施补偿方案（SPI3 挪到 PB3/PB4/PB5），需要同步改 J2 的第 4 脚（放弃 IMU_INT2）。

## 6 固件影响

- **STM32 侧（工程尚未建立）**：UART4 初始化 115200 8N1，收发用中断或 DMA + IDLE；建议先做文本 CLI（`help`/`stat`/`enc`/`pwm`/`stop`）用于台架调试，正式二进制帧仍只走 USART3。
- 日志 `printf` 重定向到 UART4 时必须与 CLI 共用一把输出锁，否则会串帧。
- 交互口**不得**参与运动安全：运动心跳超时只由 USART3 与 STM32 自身看门狗判定。
- **ESP32 侧**：本次改动不涉及，`motion_link.c` 的模拟后端保持不变。

## 7 未决问题

1. 交互口接什么设备？PC USB-TTL、串口屏、Jetson/树莓派上位机，还是第二块 ESP32？这决定是否需要 5 V 供电、是否需要 RS485/隔离。
2. 云台是总线舵机（必须 UART）还是 PWM 舵机？若后者，方案 B（直接用 USART2）最省事。
3. CN12–CN15 的 3/4 脚准备接什么？建议至少补 GND 与 `DRV_FAULT`，把故障回读做出来。
