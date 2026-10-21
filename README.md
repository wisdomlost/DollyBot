# DollyBot ESP32 控制系统

第一版离线手机控制固件，ESP-IDF **v5.5.4**，目标 **经典 ESP32**，2 MB Flash 基线；无需 PSRAM、Node 构建工具或互联网资源。当前没有 STM32，电机接口仅输出模拟串口日志。

## 编译

安装 ESP-IDF v5.5.4 及 ESP32 工具链，在已激活的 ESP-IDF 终端中：

```powershell
idf.py build
```

本机 EIM 安装的 PowerShell 激活方法（仅本机路径，其他成员使用各自安装路径）：

```powershell
. C:\Espressif\tools\Microsoft.v5.5.4.PowerShell_profile.ps1
idf.py --version
idf.py build
```

不要使用系统 Python 直接替代 IDF 虚拟环境。普通 export.ps1 在此 EIM 安装下不能自动定位虚拟环境，使用 EIM 激活脚本即可。依赖仅 ESP-IDF 内置组件，无第三方组件下载。`sdkconfig.defaults` 为共享构建配置，生成的 `sdkconfig` 不入库。

## 手动烧录与使用

本轮开发不自动烧录、清 Flash。连接板子后自行执行，COM5 替换为实际串口：

```powershell
idf.py -p COM5 flash
idf.py -p COM5 monitor
```

监视器退出：Ctrl+]。经典 ESP32 是根据既有工程配置选择的目标，尚未读连接板的芯片信息。若实际为 S3/C3 等，先确认型号，再自行 `idf.py set-target esp32s3` 等并重新编译；该命令会调整本地生成配置。

手机连接 **DollyBot**，初始演示密码 **dollybot123**，保留“无互联网”Wi-Fi 连接；浏览器输入 **http://192.168.4.1/**（HTTP）。无需安装 App，也没有 DNS 劫持或强制门户。网页只允许此 IP 的同源 WebSocket；不支持 localhost/mDNS 地址控制。需要时关闭手机自动切换移动网络。

摇杆或方向按钮按住移动、松开停止；速度滑条限制归一化指令幅度。立即停止按钮清除当前输入；切后台、失焦、触摸取消均主动停止。网页自动重连后保持停止，必须重新按下。单个网页独占控制，第二个控制端会被拒绝并重试。关闭首个页面后可接管，异常网络中断可能需等待 TCP 会话超时释放所有权，但运动保护会先停止。

热点名、密码、信道通过 `idf.py menuconfig` → DollyBot 配置。初始密码是公开演示配置，正式使用请改本地配置，勿提交私密密码。访问权限由 WPA2 热点及单控制端机制约束；HTTP/WS 未做 TLS 或用户账户认证。

## 目录与扩展

| 路径 | 职责 |
| --- | --- |
| `main/wifi_ap.c` | WPA2 SoftAP、网络初始化 |
| `main/web_server.c` | 内嵌静态资源、同源检查、WebSocket 收发 |
| `main/protocol.c` | 有界 JSON 协议校验，不依赖网络 |
| `main/control.c` | 控制权、序号、500 ms 超时保护、独立 20 ms 检查任务 |
| `main/motion_link.*` | 独立运动硬件接口，目前模拟后端，未来替换 UART |
| `web/` | 无框架 HTML/CSS/JS，编译时内嵌，无 CDN |
| `tests/` | 真实协议和控制模块主机测试、浏览器逻辑适配器测试 |
| `docs/` | 协议、硬件接入与验证说明 |

硬件状态与指令状态严格区分：网页 linear/angular 是归一化目标指令，**不是实测速度**；STM32 始终未连接，不生成虚假电量、里程、轨迹。运行时间、堆内存来自 ESP32 真实运行信息。轨迹录制、预览、回放仅提供禁用入口。

## 软件测试

主机需 Node.js（无 npm 依赖）、MinGW GCC、已安装的 IDF（复用其 cJSON 源码）：

```powershell
$env:PATH = 'D:\msys2\ucrt64\bin;' + $env:PATH
.\tools\test.ps1 -IdfPath $env:IDF_PATH -HostGcc D:\msys2\ucrt64\bin\gcc.exe
```

这会编译真实的 `protocol.c`、`control.c` 和 IDF 自带 cJSON，使用模拟时钟、任务和硬件适配器验证边界条件；不验证 FreeRTOS 调度、射频或实际电机。前端测试在 Node VM 中运行原始脚本和 DOM/WebSocket 适配器，不等同于真机浏览器视觉或 Wi-Fi 联调。详见 [验证记录](docs/validation.md)。

## 硬件验收

1. 启动日志应出现 AP 地址和 `SIMULATION ONLY`，手机页面所有资源可离线加载。
2. 按住各方向、摇杆、调速，核对串口 `SIM command` 和网页指令符号；松开、停止、切后台后归零。
3. 按住时断开手机 Wi-Fi；核对串口在最后运动指令后约 500–520 ms 加任务调度延迟内归零。不能只看已经断连的页面。
4. 第二部手机应不能抢占；关闭首个页面后新页面可连接，重连不自动恢复运动。
5. 长时间运行检查堆内存、重连、并发静态资源请求；用 iOS Safari / Android Chrome 检查多点触控和屏幕旋转。
6. 接入电机前必须实现 STM32 独立失联停车并完成架空轮测试。
