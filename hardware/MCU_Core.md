# DollyBot STM32F405 最小系统

2026-10-26：在当前 `DollyBot_STM32_V1` 工程的 `01_MCU_Core` 页补齐最小系统，共 21 个器件。

完整工程快照：`DollyBot_STM32_V1_MCU_Core.epro2`，可导入嘉立创 EDA 专业版。该快照包含四页原理图、PCB 和原有工程结构；没有另建逻辑工程。
编辑器中的工程已保存，原 `.eprj2` 文件也已由客户端更新，SQLite 完整性检查为 `ok`；同时保留 `.epro2` 导出快照便于导入和恢复。本版本将设计保存在历史记录中，不能根据旧 `documents` 表为空判断工程为空。

## 电路

| 功能 | 连接和器件 |
| --- | --- |
| 主控 | 保留 U1：STM32F405RGT6TR，LQFP64 |
| 数字电源 | 19/32/48/64 脚接 `+3V3_SYS`，18/63 脚接 `GND` |
| VDD 去耦 | C1–C4 各 100nF，分别靠近四个 VDD；C5 为 4.7uF 储能 |
| 内部稳压器 | 31 脚 `VCAP1` 接 C6 2.2uF 到地；47 脚 `VCAP2` 接 C7 2.2uF 到地；两个 VCAP 网络独立 |
| 模拟电源 | VDDA 13 脚接 `+3V3_SYS`，VSSA 12 脚接地；C8 100nF、C9 1uF 就近去耦 |
| 备份电源 | VBAT 1 脚接 `+3V3_SYS`；C10 100nF；没有独立 RTC 电池 |
| 复位 | NRST 7 脚：R1 10k 上拉、C11 100nF 到地、SW1 按下接地 |
| 启动 | BOOT0 60 脚：R2 10k 下拉，SW2 按下接 3.3V；PB2/BOOT1 28 脚由 R3 10k 下拉 |
| HSE | PH0/PH1 接 X1 8MHz 晶振，C12/C13 各 33pF C0G 到地 |
| SWD | PA13 → SWDIO，PA14 → SWCLK，PB3 → SWO；J1 同时引出 NRST、3.3V 参考和 GND |

J1 为自定义 1×6、2.54mm 调试排针，1–6 脚依次为：`+3V3_SYS`、`SWDIO`、`SWCLK`、`GND`、`NRST`、`SWO`。调试器应使用匹配接线，1 脚作为目标电压参考。
正常启动保持 SW2 松开；进入 ROM 启动模式时按住 SW2 并复位。

## 选件

| 器件 | 立创编号 | 参数 / 型号 |
| --- | --- | --- |
| C1–C4、C8、C10、C11 | C14663 | 100nF / 50V / X7R / 0603 |
| C5 | C19666 | 4.7uF / 16V / X5R / 0603 |
| C6、C7 | C23630 | 2.2uF / 16V / X5R / 0603 |
| C9 | C15849 | 1uF / 50V / X5R / 0603 |
| C12、C13 | C1852355 | 33pF / 100V / C0G / 0603 |
| R1–R3 | C25804 | 10k / 1% / 0603 |
| X1 | C12674 | X49SM8MSD2SC，8MHz，CL=20pF，HC-49S-SMD |
| SW1、SW2 | C52175904 | TS-1088-AR02026，两引脚按键 |
| J1 | C54876671 | PZ2.54-1x6PZZ-L11.5，1×6 排针 |

X1 负载电容按 CL=20pF 选择初始值 33pF，实际负载包含引脚和 PCB 寄生电容；PCB 完成后需实测起振与频率，再决定是否调整。VCAP 电容按 ST 要求采用低 ESR 陶瓷电容，就近接地。

## 验证与边界

- 实际导出网表通过 44 项连接检查，21 个器件均有封装，新增器件没有悬空引脚；两个 VCAP 网络分别只有 MCU 和对应电容。
- 最终 DRC：0 个错误；2 组 `CHECKFLOATPIN` 告警，涉及 U1 的 45 个预留引脚，供后续电机、接口页分配。不使用批量 NC 标记隐藏它们。
- 导出 PNG 已目视检查布局、网络标签与连线；原有 U1 和五段电源接线保留。
- 导出工程与修改前备份逐段比较：`02_Power`、`03_Motor`、`04_Interfaces` 和 `DollyBot_CTRL` PCB 内容一致。
- `+3V3_SYS` 需要由后续电源页或外部稳压源提供。没有装 LSE 晶振，RTC 可使用内部 LSI；其精度需按应用评估。
- 本节点完成原理图；尚未进行 PCB 放置/布线、样板上电、晶振起振、SWD 下载和复位启动实测。
- 本次主机验证为网表检查；未修改控制/协议代码，未运行固件主机测试或 `idf.py build`，未烧录硬件。
- 修改前完整备份、网表检查结果、最终 PNG 及执行记录保存在本地 `build/eda-work/`，不入库。

参考：[STM32F405/407 数据手册](https://www.st.com/resource/en/datasheet/stm32f405rg.pdf)、[ST AN4488 硬件设计指南](https://www.st.com/resource/en/application_note/an4488-getting-started-with-stm32f4xxxx-mcu-hardware-development-stmicroelectronics.pdf)。
