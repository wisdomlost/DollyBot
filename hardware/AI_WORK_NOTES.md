# DollyBot 画板工作笔记（给 AI 自己看）

最后更新：2026-10-28
用途：跨对话保存 EDA 工程状态、引脚网表、MCP 操作方式和画板约束。
人类可读的正式文档是 [hardware-plan.md](hardware-plan.md)、[component-selection.md](component-selection.md)、[README.md](README.md)；本文是操作笔记，允许更直接、更具体。

---

## 1. 绝对不要忘的规则

- 不要碰用户其他 EDA 会话/工程。当前只处理 `DollyBot_V1`。
- 不要删除用户已画的导线、器件、网络；批量修改前先确认或先备份。
- 用户说“不加其他的”时，只连已有网络的线/补已有引脚，不放新器件。
- 每次改完必须回读验证：引脚数、连线数、网络名、DRC。
- 不导出制造文件、不烧录、不擦除 Flash。
- 工程是 `.eprj3` 文件夹格式，磁盘和编辑器双向同步；改文件前注意编辑器可能正在打开。
- 会话 ID 每次用户重开工程都会变；先 `session.list`，不要复用旧 ID。

---

## 2. 当前工程状态（2026-10-28）

| 项目 | 状态 |
| --- | --- |
| 工程路径 | `D:\embedded_projects\DollyBot\hardware\DollyBot_V1\DollyBot_V1.eprj3` |
| 工程格式 | `.eprj3` 文件夹工程，双向同步 |
| 原理图 | `sch/Schematic1/MCU_Core.esch2` |
| PCB | `pcb/PCB1.epcb2`，目前只有 DOCHEAD/META，**空板，无器件/板框/走线** |
| 原理图器件 | 2 个：A4 图框 + `U1` STM32F405RGT6TR |
| U1 primitiveId | `f522e6d15cd31f95`（重开工程后可能不变，但以实时 `getAll()` 为准） |
| 导线 | 64 条，U1 的 64 个引脚全部有一条 30 单位短线 + 网络名 |
| DRC | `check(true,false,true)` 返回 56 条 `SHOWDANDLING` 警告，无 error；这是短线另一端悬空的正常结果，等后续器件接上会消失 |
| 备份 | `hardware/DollyBot_V1/DollyBot_V1_backup/DollyBot_V1_2026-10-27-21-06.epro2` |
| 旧工程 | `git show HEAD:hardware/DollyBot_STM32_V1_MCU_Core.epro2` 可恢复；旧 `.eprj2` 在暂存区 |

当前原理图只是“U1 + 64 条命名短线”，**不是完整最小系统**。去耦、VCAP、晶振、复位、BOOT、SWD、电源、连接器都还没放。

---

## 3. U1 完整引脚—网络表（2026-10-28 实读）

```text
1  VBAT     +3V3_SYS
2  PC13     LED_STATUS
3  PC14     LED_FAULT
4  PC15     GIMBAL_PWR_EN
5  PH0      HSE_IN
6  PH1      HSE_OUT
7  NRST     NRST
8  PC0      ADC_BAT
9  PC1      ADC_SPARE1
10 PC2      ADC_SPARE2
11 PC3      ADC_SPARE3
12 VSSA     GND
13 VDDA     +3V3_A
14 PA0_WKUP ENC_LF_A
15 PA1      ENC_LF_B
16 PA2      GIMBAL_TX
17 PA3      GIMBAL_RX
18 VSS      GND
19 VDD      +3V3_SYS
20 PA4      ADC_SPARE4
21 PA5      ADC_SPARE5
22 PA6      ENC_RF_A
23 PA7      ENC_RF_B
24 PC4      MOT_LR_DIR
25 PC5      MOT_RR_DIR
26 PB0      MOT_LF_DIR
27 PB1      MOT_RF_DIR
28 PB2      BOOT1
29 PB10     STM_TX
30 PB11     STM_RX
31 VCAP_1   VCAP1
32 VDD      +3V3_SYS
33 PB12     GIMBAL_LIMIT1
34 PB13     GIMBAL_LIMIT2
35 PB14     GIMBAL_PWM1
36 PB15     GIMBAL_PWM2
37 PC6      MOT_LF_PWM
38 PC7      MOT_RF_PWM
39 PC8      MOT_LR_PWM
40 PC9      MOT_RR_PWM
41 PA8      ENC_RR_A
42 PA9      ENC_RR_B
43 PA10     DRV_EN
44 PA11     DRV_FAULT
45 PA12     BUZZER/SPARE
46 PA13     SWDIO
47 VCAP_2   VCAP2
48 VDD      +3V3_SYS
49 PA14     SWCLK
50 PA15     EXP_SPI_CS
51 PC10     EXP_SPI_SCK
52 PC11     EXP_SPI_MISO
53 PC12     EXP_SPI_MOSI
54 PD2      IMU_INT1
55 PB3      SWO
56 PB4      SPARE_GPIO
57 PB5      IMU_INT2
58 PB6      ENC_LR_A
59 PB7      ENC_LR_B
60 BOOT0    BOOT0
61 PB8      IMU_SCL
62 PB9      IMU_SDA
63 VSS      GND
64 VDD      +3V3_SYS
```

注意：

- `45 PA12` 的网络名是 `BUZZER/SPARE`，含 `/`。EDA 可能把它当层级分隔符；后续如果要改，改成 `BUZZER` 或 `SPARE_GPIO`，不要贸然批量重命名。
- `+3V3_A`（VDDA）和 `+3V3_SYS` 目前是分开的网络；电源页要按磁珠/滤波方案连接。
- `VCAP1` / `VCAP2` 是独立网络，后续只接各自 2.2 µF 电容到 GND，不能并联。
- `ADC_SPARE1..5`、`SPARE_GPIO`、`IMU_INT2`、`BUZZER/SPARE` 是预留网络，不代表已接器件。

---

## 4. 2026-10-28 本次操作记录

用户要求：“把没 stm32 上没连接完的网表连一下，不加其他的”。

- 原状态：49 条导线，U1 引脚 1–49 已连；50–64 未连。
- 本次只新增 15 条短线 + 网络名，未放任何新器件：
  - 50 `EXP_SPI_CS`、51 `EXP_SPI_SCK`、52 `EXP_SPI_MISO`、53 `EXP_SPI_MOSI`
  - 54 `IMU_INT1`、55 `SWO`、56 `SPARE_GPIO`、57 `IMU_INT2`
  - 58 `ENC_LR_A`、59 `ENC_LR_B`、60 `BOOT0`、61 `IMU_SCL`、62 `IMU_SDA`
  - 63 `GND`、64 `+3V3_SYS`
- 回读结果：64 个引脚全部有导线和网络，0 个未连，0 条无引脚导线。
- DRC：56 条 `SHOWDANDLING` 警告，无 error。原因是每条 30 单位短线只有一端在引脚上，另一端悬空；后续接器件/网络标签后会减少。
- 本次没有移动 U1、没有删除/修改原有 49 条导线。

---

## 5. MCP 操作速查（lceda-pro）

### 5.1 先找会话

```text
mcp__lceda-pro__lceda-pro_call path=session.list
```

找到 `path` 以 `DollyBot_V1.eprj3` 结尾、`status: alive` 的 `sessionId`。用户重开工程后 ID 会变。

### 5.2 调用编辑器 API

```text
mcp__lceda-pro__lceda-pro_call path=invoke input={
  "session": "<sessionId>",
  "code": "<async function body; return 值会原样返回>"
}
```

`code` 里可以直接用 `eda`，例如：

```js
const comps = await eda.sch_PrimitiveComponent.getAll();
const stm = comps.find(c => c.designator === 'U1');
const pins = await eda.sch_PrimitiveComponent.getAllPinsByPrimitiveId(stm.primitiveId);
const wires = await eda.sch_PrimitiveWire.getAll();
return { comps, pins, wires };
```

### 5.3 常用读取

- 当前图页：`eda.dmt_Schematic.getCurrentSchematicPageInfo()`
- 所有原理图页：`eda.dmt_Schematic.getAllSchematicPagesInfo()`
- 所有器件：`eda.sch_PrimitiveComponent.getAll()`
- 器件引脚：`eda.sch_PrimitiveComponent.getAllPinsByPrimitiveId(primitiveId)`
- 所有导线：`eda.sch_PrimitiveWire.getAll()`
- 所有网络：`eda.sch_Net.getAllNets()`、`eda.sch_Net.getAllNetsName()`
- 打开图页：`eda.dmt_EditorControl.openDocument(uuid)`
- 等待图页：`eda.dmt_EditorControl.waitForDocumentReady(...)` / `getDocumentReadyState()`

### 5.4 创建导线

```js
const w = await eda.sch_PrimitiveWire.create(
  [x1, y1, x2, y2],   // 或 [[x1,y1,x2,y2], ...] 连续多段
  'NET_NAME',          // 网络名；不传则按落点推断
  null,                // color
  null,                // lineWidth 1-10
  null                 // lineType
);
return { id: w.getState_PrimitiveId(), net: w.getState_Net(), line: w.getState_Line() };
```

### 5.5 DRC

```js
const violations = await eda.sch_Drc.check(true, false, true); // strict, no UI, verbose
const passed = await eda.sch_Drc.check(true, false, false);    // boolean
return { count: violations.length, violations };
```

注意：`check()` 必须给 3 个参数；不传参数会返回 `false`，不要误判为通过。

### 5.6 PCB 相关（还没实际用过）

- `eda.pcb_Document.importChanges(...)` 从原理图导入变更；调用前先用 `doc.api` 查 `PCB_Document.importChanges` 的参数。
- `eda.pcb_Document.save()` 保存。
- `eda.pcb_Document.zoomToBoardOutline()`、`eda.pcb_Document.navigateToRegion()` 方便看板。
- PCB 图元类：`PCB_PrimitiveLine`、`PCB_PrimitivePad`、`PCB_PrimitiveComponent`、`PCB_PrimitiveVia`、`PCB_PrimitiveFill`、`PCB_PrimitivePour`、`PCB_PrimitiveRegion`、`PCB_PrimitiveString`。
- PCB 规则/网络：`PCB_Drc`、`PCB_Net`、`PCB_Layer`、`PCB_NetClass`。
- PCB 目前是空板；布局前先定板框、安装孔、连接器位置。

---

## 6. 坐标与画布事实

- 原理图坐标单位：10 mil。
- 当前 A4 图框：`1655 × 1170`。
- U1 位置：`(295, 930)`，rotation 0，mirror false。
- U1 左侧引脚：`x = 230`，`y = 1085 → 775`，每脚间隔 10，pin 1→32。
- U1 右侧引脚：`x = 360`，`y = 775 → 1085`，每脚间隔 10，pin 33→64。
- 现有短线：左侧 `[230,y,200,y]`，右侧 `[360,y,390,y]`，长度 30。
- 新增网络时，保持同样 30 单位短线，避免和已有短线重叠。
- 放置新器件前先 `zoomToAllPrimitives()` 或看截图，避免压到 U1/短线。

---

## 7. 画板约束（来自用户与需求）

| 项目 | 已确认/待确认 |
| --- | --- |
| 电机 | RC380，已有；额定电压/堵转电流/编码器待确认 |
| 编码器 | 不确定；主板先预留 4 路 TIM1/2/3/4 接口和 RC 滤波；首版可开环 |
| 驱动板 | 已有；每路 **PWM+DIR** 已确认；电流/逻辑电平/EN/FAULT 待确认 |
| 电池 | 3S；容量/放电/连接器待定 |
| 3S 拓扑 | 主板转接 3S 给驱动板（J20）；主板承载电机总电流，需按堵转电流核算；大电流时改电池直连驱动板 |
| ESP32 | 6Pin 排线接现成开发板；板型/UART GPIO 待定 |
| IMU | LSM6DSO 板载 I²C |
| 预算 | 按安全方案估算，不设硬上限 |
| 安全 | 500 ms 运动期限；驱动 EN/负载开关默认关；新会话停机 |
| 云台 | 未来扩展；PWM/UART/电源/限位预留 |

**关键安全提醒：** 公开资料中 RC380/RS-380SH 常见 7.2 V、空载 >14,000 rpm；3S 12.6 V 可能是 1.75 倍过压。380 级堵转电流可能每台数十安（需实测）。如果 4 台总堵转 >20 A，不要让主板承载电机总电流；电池直接并驱动板，主板只取逻辑电。详见 `hardware-plan.md` 第 3.1 节。

---

## 8. 下一步画板顺序（建议）

1. **最小系统**：在 U1 周围放 VDD 去耦（4×100 nF + 4.7/10 µF）、VCAP1/2（2.2 µF）、VDDA 滤波、8 MHz 晶振 + 负载电容、NRST、BOOT0/BOOT1、SWD 排针。
2. **电源页**：3S 输入、保险丝、防反接 P-MOS、主开关、TVS、5V buck、3.3V LDO、电池 ADC、J20 3S 输出。
3. **电机/编码器**：J3 驱动板控制、J4–J7 编码器、RC 滤波、ESD。
4. **通信/传感器**：ESP32 6Pin、ESD/倒灌保护、LSM6DSO、I²C 上拉。
5. **扩展**：SPI/I²C/ADC/GPIO/UART、云台接口、测试点、LED/蜂鸣器。
6. **PCB**：板框/安装孔 → 导入变更 → 布局（电源/电机大电流路径优先）→ 布线 → DRC/DFM。

当前只完成第 0 步的“U1 引脚命名”，还没有放第一个无源器件。

### 8.1 最小系统手动操作卡（用户自己画）

目标：只放最小系统无源件/晶振/按键/SWD，不碰电源页、电机页。

**放件顺序：**

1. **VDD 去耦**：C1–C4 100 nF，每个 VDD 脚（19/32/48/64）一个；C5 4.7 µF 或 10 µF 一个。全部 `+3V3_SYS` ↔ `GND`。
2. **VCAP**：C6 2.2 µF 接 `VCAP1` ↔ `GND`；C7 2.2 µF 接 `VCAP2` ↔ `GND`。两个 VCAP 网络绝不能并联。
3. **VDDA**：FB1（磁珠或 0 Ω）接 `+3V3_SYS` ↔ `+3V3_A`；C8 100 nF、C9 1 µF 接 `+3V3_A` ↔ `GND`。
4. **VBAT**：C10 100 nF 接 `+3V3_SYS` ↔ `GND`（VBAT 脚已接 +3V3_SYS）。
5. **复位**：R1 10 kΩ `+3V3_SYS` ↔ `NRST`；C11 100 nF `NRST` ↔ `GND`；SW1 `NRST` ↔ `GND`。
6. **BOOT**：R2 10 kΩ `BOOT0` ↔ `GND`；SW2 `BOOT0` ↔ `+3V3_SYS`；R3 10 kΩ `BOOT1` ↔ `GND`。
7. **HSE**：X1 8 MHz 接 `HSE_IN` ↔ `HSE_OUT`；C12/C13 33 pF 分别 `HSE_IN`/`HSE_OUT` ↔ `GND`。
8. **SWD**：J1 1×6，1 `+3V3_SYS`、2 `SWDIO`、3 `SWCLK`、4 `GND`、5 `NRST`、6 `SWO`。

**画法建议：**

- 现有 64 条短线就是网络锚点；新器件引脚优先用**同名网络标签**（Net Label）连接，不要拉长线跨过 U1。
- 所有 GND 用 `GND` 网络标签；所有 3.3 V 用 `+3V3_SYS`；VDDA 用 `+3V3_A`；VCAP 用 `VCAP1`/`VCAP2`。
- 每个电容尽量贴近对应 U1 引脚放置，原理图上按功能分组即可，最终 PCB 再按“就近”摆放。
- 不要给 `ADC_SPARE*`、`SPARE_GPIO`、`IMU_INT2` 等预留网络强行接器件；它们保持悬空是正常的。

**完成后自检：**

- U1 的 19/32/48/64、1、13 都有 +3V3 连接；12/18/63 都接 GND。
- `VCAP1` 只出现 U1.31、C6；`VCAP2` 只出现 U1.47、C7。
- `+3V3_A` 只经 FB1 连到 `+3V3_SYS`，没有直接短路。
- `BOOT0` 有 10 kΩ 下拉和按键上拉；`BOOT1` 有 10 kΩ 下拉。
- `NRST` 有 10 kΩ 上拉、100 nF 到地、按键到地。
- `HSE_IN`/`HSE_OUT` 只接 X1 和 C12/C13。
- SWD 六脚线序与调试器匹配，丝印标注 1 脚。
- 跑 DRC：`SHOWDANDLING` 应减少；剩下的是未用预留网络/尚未接的引脚，不应有 error。

---

## 9. 文件与参考

- 正式方案：`hardware/hardware-plan.md`
- 选型清单：`hardware/component-selection.md`
- 当前状态摘要：`hardware/README.md`
- 旧最小系统审查：`git show HEAD:hardware/MCU_Core.md`
- 旧电源审查：`git show HEAD:hardware/Power_Stage1_Review.md`
- 旧工程快照：`git show HEAD:hardware/DollyBot_STM32_V1_MCU_Core.epro2`
- 嘉立创 EDA 文件夹格式说明：`hardware/DollyBot_V1/eprj3-intro.md`
- ST DS8626 / AN4488 / RM0090：见 `hardware-plan.md` 第 8 节

---

## 10. 变更日志

| 日期 | 变更 |
| --- | --- |
| 2026-10-28 | 建立本笔记；记录 U1 64 脚网表、MCP 用法、当前 DRC、PCB 空板状态 |
| 2026-10-28 | 补 U1 引脚 50–64 的 15 条短线/网络；回读 64/64 已连；DRC 56 条 SHOWDANDLING 警告 |