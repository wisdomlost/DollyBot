# DollyBot 硬件资料索引

最后更新：2026-11-20
状态：方案审查阶段。未修改原理图、PCB，未做硬件测试，未烧录。
说明：本文描述的是当前工作区状态；早期工程快照仍留在 git 历史里（见文末"历史工程"）。

## 目录结构

| 路径 | 放什么 |
| --- | --- |
| `hardware/docs/` | **正式设计文档**，给别人看的都在这一个地方 |
| `hardware/bom/` | 采购与下单文件：BOM 表（xlsx/csv/md） |
| `hardware/netlist/` | 立创EDA 导出的网表 `.enet` |
| `hardware/DollyBot_V1/` | 立创EDA 专业版工程（`.eprj3` 文件夹工程） |
| `hardware/private/` | 私有草稿、临时脚本、截图；已 gitignore，不推送 |
| `tools/` | 仓库级脚本，如主机测试 `test.ps1` |

约定：正式文档只进 `docs/`，不要在 `hardware/` 顶层散放；一次性的东西进 `private/`。

## 文档

| 文档 | 内容 |
| --- | --- |
| [hardware-plan.md](docs/hardware-plan.md) | 硬件架构审查、STM32 资源分配与引脚复用校验、电源架构、接口定义、实施顺序。 |
| [component-selection.md](docs/component-selection.md) | 关键器件选型清单（含留空项）、电机—驱动—电池匹配矩阵、BOM 成本估算、待确认问题。 |
| [power-redesign.md](docs/power-redesign.md) | 电源方案重做：拓扑取舍、器件选型、与旧方案的差异。 |
| [Power_page.md](docs/Power_page.md) | 电源页逐页说明与器件参数（附 [power-topology.png](docs/power-topology.png)）。 |
| [gimbal-power-wiring.md](docs/gimbal-power-wiring.md) / [gimbal-wiring-guide.md](docs/gimbal-wiring-guide.md) | 云台供电与接线。 |
| [encoder-board-design.md](docs/encoder-board-design.md) / [encoder-wiring.md](docs/encoder-wiring.md) | 编码器板设计与接线。 |
| [gyro-board-design.md](docs/gyro-board-design.md) | 陀螺仪板设计。 |
| [uart-extension-plan.md](docs/uart-extension-plan.md) | UART 扩展与主板 5P 接口定义。 |
| [driver-board-bom.md](bom/driver-board-bom.md) | 双路驱动板 BOM、金额、下单方式、接线与待定问题。 |
| [AI_WORK_NOTES.md](docs/AI_WORK_NOTES.md) | 画板工作笔记：EDA 工程状态、引脚—网络表、MCP 操作速查。 |
| [learning-path.md](docs/learning-path.md) | 学习路线与资料索引。 |
| [eprj3-intro.md](DollyBot_V1/eprj3-intro.md) | 立创EDA 文件夹工程格式说明。 |

## 已确定的决策

来自 V0.9 需求、`config.md`、现有工程和 2026-10-27 的用户确认：

- 四轮麦克纳姆轮底盘，4 个直流电机；**电机为 RC380**，具体型号、额定电压、堵转电流待确认。
- **RC380 是否自带 AB 相编码器暂不确定**；主板先预留 4 路编码器接口和 RC 滤波，首版可按开环运行，后续加装编码器再闭环。
- 运动主控 STM32F405RGT6，LQFP64。
- ESP32 与 STM32 分离，通过 UART 通信；ESP32 负责 Wi-Fi、手机 APP、路径存储和用户交互。
- **已有电机驱动板**；每路控制接口已确认 **PWM+DIR**，主板提供 4 路 PWM + 4 路 DIR + 可选 EN/FAULT。
- 动力电池采用 **3S** 带 BMS 的成品可拆卸电池包，外置专用充电器；主板不做充电。
- **主板转接 3S 给驱动板**：电池 → 保险丝 → 防反接 → 主开关 → J20 → 驱动板；主板同时从 VM_BAT 取电给 5V/3.3V 逻辑。
- 第一版只设计 STM32 运动控制主板，不新增 ESP32 专用 PCB；ESP32 采用 **6Pin 排线接现成开发板**。
- 板载六轴 IMU 采用 **ST LSM6DSO**（I²C）。
- 预算按安全方案估算，不设硬上限；优先保证电池、保险丝、驱动接口和线径。
- EDA 使用嘉立创 EDA 专业版。
- 运动安全期限 500 ms、独立检查任务、ping 不刷新期限、新会话必须停机；这些是固件约束，硬件必须提供默认关闭的电机使能。

## 验证边界

- 引脚复用：用本机 STM32CubeMX MCU 数据库 `STM32F405RGTx.xml` 对 51 个 I/O 逐一校验，全部通过；并与 ST DS8626 Rev 12 的 LQFP64 引脚表交叉核对。
- 定时器、编码器模式、VCAP、去耦、复位、BOOT、HSE：依据 ST DS8626 Rev 12、AN4488、RM0090。
- 器件价格：早期文档中的价格是"参考区间"，未核实；驱动板 BOM 里的价格是 2026-11-20 从立创商城/嘉立创接口核到的实价。
- 未做任何硬件测试。

## 当前阻塞项（按优先级）

| 优先级 | 待确认 | 影响 |
| --- | --- | --- |
| 1 | RC380 额定电压（是否 3S 可用）、额定/堵转电流、是否带 AB 相编码器、轴径/减速比 | 3S 是否过压、保险丝、主开关、J20 端子/线径、驱动板是否够用、闭环是否可实现 |
| 2 | 已有驱动板的额定/峰值电流、逻辑电平、EN/STBY、FAULT/IS、J3 引脚定义 | 3.3 V 能否直驱、硬件停机方式、控制接口定稿 |
| 3 | 3S 电池容量/持续/峰值放电、BMS、连接器 | 续航、保险丝、线径、XT30/XT60 |
| 4 | 底盘/麦轮尺寸、安装孔、RC380 轴匹配；ESP32 具体板型和 UART GPIO | 板框、接插件、机械干涉、6Pin 线序 |
| 5 | 在 `hardware/DollyBot_V1/` 重建，还是恢复旧 `.epro2` | 后续原理图工作流；未确认前不覆盖 |

## 下一步

1. 回答 [component-selection.md](docs/component-selection.md) 中的关键问题，优先确认 **RC380 的额定电压/堵转电流/是否有编码器**，以及 **已有驱动板的型号/控制接口/电流能力**。
2. 确认 3S 电池容量/放电能力、底盘/麦轮尺寸和 ESP32 具体板型。
3. 确认是在 `hardware/DollyBot_V1/` 新工程中重建最小系统，还是恢复旧工程继续；未确认前不覆盖任何工程。
4. 冻结接口和电源拓扑后，再按 [hardware-plan.md](docs/hardware-plan.md) 的阶段 1–6 完善原理图，最后才进入 PCB 布局。

## 历史工程

旧版工程没有丢，仍在 git 历史里，恢复命令只写入 `build/` 或新目录，不覆盖当前工程：

```bash
git show HEAD:hardware/DollyBot_STM32_V1_MCU_Core.epro2 > build/eda-work/DollyBot_STM32_V1_MCU_Core.epro2
git show :hardware/DollyBot_STM32_V1.eprj2 > build/eda-work/DollyBot_STM32_V1.eprj2
git show HEAD:hardware/MCU_Core.md > build/eda-work/MCU_Core.md
git show HEAD:hardware/Power_Stage1_Review.md > build/eda-work/Power_Stage1_Review.md
```

`build/` 不入库。
