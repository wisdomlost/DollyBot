# VOFA+ 无线调参

## 连接

1. 在电脑上运行 `ipconfig`，记下与小车位于同一网络的网卡 IPv4。实验室使用同一个 2.4 GHz Wi-Fi，或让 ESP32 加入电脑开的 2.4 GHz 热点。
2. 在 `firmware/esp32` 用 ESP-IDF 5.5.4 运行 `idf.py menuconfig`。进入 **DollyBot VOFA+**，填写 Wi-Fi 名称、WPA2 密码和电脑 IPv4。配置保存在本机 `sdkconfig`。电脑地址留空时无线遥测关闭。若沿用旧 `sdkconfig`，在 **Partition Table** 选择 **Single factory app (large), no OTA**。Wi-Fi 和蓝牙同时编译后使用 1500 KiB 应用分区，适配 2 MB Flash。
3. 使用更新后的 STM32 和 ESP32 固件。首次用新版 ESP32 固件时需要一并更新分区表。在电脑的 VOFA+ 里选择 **TCP 服务端**，监听端口 **9000**，协议引擎选 **FireWater**，启动监听。ESP32 联网后会主动连到电脑。Windows 防火墙可给 VOFA+ 放行当前实验网络的入站连接。
4. 在 VOFA+ 的发送区以 ASCII 发送一条模式命令，末尾加换行（LF 或 CRLF）。也可以把命令绑定到 VOFA+ 按钮。连接时默认暂停采集，命令切换后只发送当前组，发送 `OFF` 暂停。TCP 断开后重新连接，需要重新选择一组。
5. 插入波形图并绑定当前组的通道。STM32 约 50 Hz 发送，ch0 是 STM32 的时间，网络拥塞时波形可能缺帧。采集过程走 Wi-Fi，运动和回放使用原来的蓝牙 App。推车读编码器可以在 App 未连接时进行。

## 命令与通道

四轮顺序始终是左前、右前、左后、右后。右移、前进和顺时针为正。四组均使用 ch0 表示 STM32 上电时间（ms），ch1 表示状态位。

| VOFA+ 命令 | 当前组 | 通道 |
|---|---|---|
| `SPEED` | 速度环 | ch2–ch5 四轮目标速度（mm/s），ch6–ch9 四轮编码器反馈（mm/s），ch10–ch13 四轮有符号 PWM（%） |
| `RATE` | 角速度环 | ch2 目标角速度（°/s），ch3 陀螺实际角速度（°/s），ch4 差速输出（mm/s） |
| `ANGLE` | 角度环 | ch2 回放目标航向（°），ch3 陀螺积分航向（°），ch4 最短角度误差（°），ch5 回放状态 |
| `ODOM` | 惯导系数 | ch2–ch5 四轮累计计数，ch6 四轮计数之和，ch7、ch8 里程估计 X、Y（mm），ch9 当前惯导系数（mm/count） |
| `OFF` | 暂停波形 | 暂停发送 |

例如 `SPEED` 加换行的十六进制写法为 `53 50 45 45 44 0A`。切换后按当前组重新绑定通道。

ch1 的 bit 0 表示运动已授权，bit 1 表示 IMU 采样有效，bit 2 表示已校准，bit 3 表示惯导配置有效，bit 4 表示回放角度环启用，bit 5 表示电池电压允许驱动，bit 6 表示电源保护已锁存。速度反馈在 bit 1、2、3 同时为 1 时有效。实际航向在 bit 1、2 为 1 时有效。角度组的目标和误差只在 bit 4 为 1 时有效。失去有效参考的字段以零占位。角度组的回放状态依次为 0 待机、1 回首点、2 对齐朝向、3 回放、4 完成、5 跟踪故障。

## 调参时看哪些曲线

- **惯导系数**。选择 `ODOM` 并直线向前推已知距离。惯导系数等于实际距离（mm）除以 ch6 的前后差值。ch2–ch5 用来检查是否有单轮反向。结果填入 `firmware/stm32/board/chassis_config.h` 中的 `惯导系数`。系数为 0 时仍上报累计计数。
- **速度环**。选择 `SPEED` 并低速直行，叠加每轮目标与反馈。例如左前轮用 ch2、ch6，用 ch10 查看 PWM 是否饱和。调整 `WHEEL_SPEED_KP` 和 `WHEEL_SPEED_KI`。
- **角速度环**。选择 `RATE` 并原地转向，比较 ch2、ch3，用 ch4 查看差速指令。调整 `YAW_RATE_KP` 和 `YAW_RATE_KI`。
- **角度环**。选择 `ANGLE` 并运行带航向变化的回放段，比较 ch2、ch3，过 ±180° 时直接看 ch4。当前角度环使用 P 控制，调整 `REPLAY_YAW_KP`。

三个环的增益在 `firmware/stm32/src/control_config.h`，调整后重新编译 STM32。ESP32 的 TCP 连接提供波形和采集组选择。运行中的增益由 STM32 固件配置。STM32 原有运动授权、500 ms 到期以及电源保护继续生效。

ESP32 向 STM32 发送会话号为 `0` 的 `0x70` 采集选择，启用时每 250 ms 刷新一次。STM32 连续 1 秒收不到刷新时停止采集。遥测帧类型为 `0x93`、会话号为 `0`，载荷首字节为组编号，随后是当前组的小端单精度值。ESP32 将收到的当前组转换为一行 FireWater 数据。

[VOFA+ 命令配置](https://www.vofa.plus/docs/learning/start/data_cmd_parameter/) · [FireWater 数据格式](https://www.vofa.plus/docs/learning/dataengines/firewater/) · [ESP32 无线共存说明](https://docs.espressif.com/projects/esp-idf/en/v5.5.4/esp32/api-guides/coexist.html)
