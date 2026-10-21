# 软件验证记录

日期：2026-10-21。开发过程中未连接开发板，未调用 flash/erase/monitor，也未读取串口芯片信息。

## 环境

- 本机已有 ESP-IDF v5.5.4（EIM 安装），Xtensa GCC 14.2.0（esp-14.2.0_20260121）、Python 3.14.4。
- 既有 `D:\ESP32\projects\01_wifi_scan\sdkconfig` 目标为经典 esp32，DIO/40 MHz/2 MB，因此新项目采用此基线，未修改旧工程。
- 初始终端未激活 IDF；普通 export.ps1 寻找传统布局 python_env 失败。EIM 自带 PowerShell profile 正常激活，无需安装或修改系统配置。
- 沙箱阻止 IDF Python 创建 Windows 子进程管道，获准在沙箱外执行本地编译；Git 元数据受只读保护，使用授权命令完成本地提交。

## 结果与范围

- **固件编译通过**：`idf.py build` 生成 bootloader、分区表、dollybot.elf、dollybot.bin；经典 ESP32、2 MB 配置通过分区大小检查。
- 最终镜像 754448 bytes（约 737 KiB），1 MiB 应用分区剩余 28%；SHA256：`6C85FB9C60B58E516190A94F8B83C47D4EA31EC27C72B977C569211A9D00CE48`。
- **C 主机测试通过**：MinGW GCC，`-std=c11 -Wall -Wextra -Werror`，使用本机 IDF 的实际 cJSON 源码和项目实际协议/控制模块。
- 校验正常 drive/stop、seq 最大值、空输入、坏 JSON、错误根类型/版本/字段类型、缺失字段、越界速度、分数/越界 seq、重复/额外字段、尾随 JSON、隐藏 NUL、无限数值、超长帧。
- 控制验证独占控制权、非持有者断开不影响控制、499 ms 保持/500 ms 清零、ping 不延长期限、重复 seq 停止、无效指令停止、断连清零、新会话序号重置、stop 清零。
- **前端逻辑测试通过**：Node VM 执行实际 app.js，验证摇杆归一化、速度缩放、方向按住/释放、pointercancel/lostpointercapture、键盘释放/失焦、立即停止、页面失焦/隐藏、心跳、无响应断连、重连先 stop、异常遥测断连；`node --check` 通过。
- Chrome 无界面访问本地静态预览，HTML/CSS/JS 均返回 HTTP 200；已生成截图。桌面窗口截图被最小布局宽度影响，移动设备调试仿真未完成，不将此项计作手机布局验证。预览服务器没有实现 `/ws`，其 404 是预期结果，不代表固件 WebSocket 验证通过。
- 主机适配器不模拟 FreeRTOS 并发、HTTP 服务器或真实无线栈，不能证明实际网络端到端延时与 RF 稳定性。硬件验收清单见 README。
- **真实硬件验证待用户执行**：Wi-Fi 关联、WPA2、原生手机浏览器 WebSocket 握手、触摸手感、串口停车时间、长时间运行和真实 STM32 均未实测。

复现命令见 README，测试失败脚本以非零退出。未批量删除任何文件，也未更改系统安装或重写 Git 历史。
