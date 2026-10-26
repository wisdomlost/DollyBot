# DollyBot 电源设计前审查

日期：2026-10-26。当前工程：`DollyBot_STM32_V1`，Board1，主控页 `01_MCU_Core`。
本次完成只读复查和修改前备份，没有修改原理图、PCB，也没有导出制造文件。

## 阶段一检查结果

| 检查项 | 实际工程内容 | 结论 |
| --- | --- | --- |
| U1 | ST STM32F405RGT6TR；TR 为包装后缀；LQFP64，10×10mm，0.5mm 节距 | 与 STM32F405RGT6 器件对应 |
| 符号/封装 | 符号 64 个引脚；封装 64 个焊盘，编号完整覆盖 1–64；提取相邻焊盘节距约 0.5001mm，焊盘约 0.30×1.50mm | 未发现漏脚或编号不对应；PCB 阶段仍需完成装配/DFM 复核 |
| VDD | 19、32、48、64 → `+3V3_SYS` | 连接正确 |
| VSS / VSSA | 18、63 / 12 → `GND` | 连接正确，无必要地脚悬空 |
| VDDA / VBAT | 13 / 1 → `+3V3_SYS` | 符合不使用独立备份电池的最小系统配置 |
| VCAP_1 | U1.31 → C6.2，C6.1 → GND；2.2uF | 独立网络，不接 3.3V、外设或另一 VCAP |
| VCAP_2 | U1.47 → C7.2，C7.1 → GND；2.2uF | 独立网络，不接 3.3V、外设或另一 VCAP |
| VDD 去耦 | C1–C4 各 100nF，C5 4.7uF | 原理图数量正确；“就近放置”尚未验证，因为本阶段不做 PCB 布局 |
| VDDA 去耦 | C8 100nF、C9 1uF → GND | 原理图正确；目前与数字 3.3V 同源，无独立模拟滤波支路 |
| VBAT 去耦 | C10 100nF → GND | 原理图正确 |
| HSE | PH0.5 / PH1.6 → 8MHz X1，C12/C13 各 33pF | 连接完整；负载、驱动能力和起振裕量未定型 |
| NRST | U1.7，10k 上拉、100nF 到地、复位按键到地 | 完整 |
| BOOT | BOOT0.60 默认 10k 下拉，按键接 3.3V；PB2/BOOT1.28 为 10k 下拉 | 正常启动和 ROM 启动配置完整 |
| SWD | PA13.46 / PA14.49 / PB3.55 → SWDIO / SWCLK / SWO；J1 有 NRST、3.3V 参考、GND | 完整；六针自定义线序，需要匹配调试线 |

依据：[ST DS8626 Rev 12](https://www.st.com/resource/en/datasheet/stm32f405rg.pdf)，重点为 LQFP64 引脚图、电源方案和 VCAP 参数表；[AN4488 Rev 7](https://www.st.com/resource/en/application_note/an4488-getting-started-with-stm32f4xxxx-mcu-hardware-development-stmicroelectronics.pdf)，重点为供电、去耦、复位、启动和调试章节。

## 器件级结论与未完成的验证

- C6/C7 是三星 CL10A225KO8NNNC，0603、2.2uF、16V、X5R、±10%。[厂家产品页](https://product.samsungsem.com/mlcc/CL10A225KO8NNN.do)确认型号和规格；已目视读取[三星特性表的公开镜像](https://datasheet.octopart.com/CL10A225KO8NNNC-Samsung-Electro-Mechanics-datasheet-11768396.pdf)。典型曲线显示低 ESR，100kHz 附近约数十毫欧，低于 ST 的 2Ω 上限；1.2V 附近的偏压降容相对较小。这是典型曲线核查，不是全温/老化/批次保证，也不是硬件实测。
- C5 的 4.7uF 是标称值，MLCC 存在偏压、温度、容差和老化影响；应核算有效电容。可考虑改为有充分有效容量裕量的 10uF 器件，但这属于选型取舍，本次没有替换。
- X1 当前为 HC-49S-SMD，8MHz、CL=20pF；33pF 两端电容仅构成约 16.5pF 串联等效容量，剩余由寄生电容贡献。现阶段不能保证实际负载匹配或起振裕量，也没有做真实起振测试。保留现设计，待晶振规格及布局一起核查；改小封装会涉及选型取舍。
- VDDA 与 VDD 直接同源符合基本连接要求。四电机环境下需评估模拟滤波、供电瞬态和地回流；是否增加模拟滤波支路应在电源方案确认后决定。
- 此次没有发现必须立即改正的电源引脚号、必要电源悬空或 VCAP 误接。不能据此声称整板已经具备制造或实测合格条件。

## 当前最小系统 BOM

| 位号 | 数量 | 制造商型号 | 主要规格 | PCB 封装 | 立创编号 | 选型理由 |
| --- | ---: | --- | --- | --- | --- | --- |
| U1 | 1 | ST STM32F405RGT6TR | Cortex-M4，LQFP64 | LQFP-64 10×10mm，P0.50mm | C2054141 | 保留现有主控 |
| C1–C4、C8、C10、C11 | 7 | YAGEO CC0603KRX7R9BB104 | 100nF，50V，X7R，±10% | C0603 | C14663 | VDD/VDDA/VBAT 去耦和 NRST 滤波 |
| C5 | 1 | Samsung CL10A475KO8NNNC | 4.7uF，16V，X5R | C0603 | C19666 | VDD 储能；有效容量仍需核算 |
| C6、C7 | 2 | Samsung CL10A225KO8NNNC | 2.2uF，16V，X5R，±10% | C0603 | C23630 | 内部稳压器 VCAP 稳定电容 |
| C9 | 1 | Samsung CL10A105KB8NNNC | 1uF，50V，X5R | C0603 | C15849 | VDDA 低频去耦 |
| C12、C13 | 2 | Murata GCM1885C2A330JA16J | 33pF，100V，C0G，±5% | C0603 | C1852355 | 晶振初始负载值，待验证 |
| R1–R3 | 3 | UNI-ROYAL 0603WAF1002T5E | 10kΩ，±1%，100mW | R0603 | C25804 | 复位上拉及 BOOT 下拉 |
| X1 | 1 | YXC X49SM8MSD2SC | 8MHz，CL=20pF | CRYSTAL-SMD L11.5-W4.8-LS12.7 | C12674 | HSE 时钟候选，保留现有器件 |
| SW1、SW2 | 2 | XKB TS-1088-AR02026 | 两引脚常开按键 | SW-SMD L3.9-W3.0 | C52175904 | 复位与 BOOT 操作 |
| J1 | 1 | SHOU HAN PZ2.54-1x6PZZ-L11.5 | 1×6 排针，2.54mm | HDR-TH PZ2.54-1X6PZZ-L11.5 | C54876671 | SWD 调试连接 |

## 验证、截图和备份

- 当前工程实际导出网表通过 44 项关键连接检查；21 个器件有封装，除预留 MCU 引脚外没有悬空的已用器件引脚。
- 嘉立创原理图 DRC：0 个错误，2 组 CHECKFLOATPIN 告警，共 45 个预留 MCU 引脚。没有通过批量 NC 标记隐藏告警。
- 当前截图：`../build/eda-work/stage1-MCU-Core.png`。
- 修改前完整可恢复快照：`../build/eda-work/DollyBot_before_02_Power_20261026.epro2`，已验证 ZIP CRC 完整性。
- 当前 `02_Power` 页只包含图框，没有器件或导线。读取前截图：`../build/eda-work/stage2-Power-before.png`；不能将它称为已完成电源原理图。
- 本次只做主机端网表检查和 EDA DRC；没有修改固件，没有运行固件主机测试或固件编译，没有硬件测试。

## 阶段二等待决定的内容

1. 电机额定电压、堵转电流，电池包容量/放电能力；这些影响电机支路、总保险丝、开关、连接器和防反接器件的定型。未知时保险丝额定值和 MOTOR_VM 连接必须留待确认。
2. 板载降压电路或有官方数据手册的外购模块。板载候选是 TPS54302 5V 电源，先评估 2A 连续与 3A 候选上限；3.3V 使用独立稳压，不以未经热验证的 3A 作为整板承诺。
3. ESP32 板型和 USB 供电路径。默认不能把板载 5V 与未知 ESP32 USB VBUS 并联。供电互斥方式须明确操作和接线；全自动切换必须先核对交互板电路。

以上决定已向用户发出。确认前不修改 02_Power，不开始电机、传感器、其他接口或 PCB 设计。
