# WebSocket 协议 v1

固定入口 `ws://192.168.4.1/ws`，浏览器 Origin 必须是 `http://192.168.4.1`。单控制端；拒绝跨站请求。客户端 10 Hz 发送运动或心跳，服务器每个合法请求返回状态。无请求时不推送；网页超过 1200 ms 无响应主动断连并清除输入。

请求为单个、不分片的 UTF-8 文本帧，最多 256 字节。只接受以下字段，拒绝未知字段、重复字段、字符串转义、NUL、尾随非空白、错误类型、越界与非有限数字。客户端 seq 为 1..4294967295 整数，每会话严格递增；接近上限重建会话，禁止回绕。v 必须为 1。

```json
{"v":1,"type":"drive","seq":1,"linear":0.4,"angular":-0.2}
{"v":1,"type":"stop","seq":2}
{"v":1,"type":"ping","seq":3}
```

linear/angular 均 [-1,1]，正值分别为前进/左转，负值为后退/右转；不是物理单位。速度滑条在客户端缩放；STM32 接入后应在设备端定义物理速度、加速度限制。未提供轨迹类命令，不会误执行预留按钮。

```json
{"v":1,"type":"state","ack":3,"simulated":true,"stm32_connected":false,"linear":0,"angular":0,"reason":"stop","uptime_ms":1000,"free_heap":150000,"timeout_ms":500}
```

上例数值仅说明格式。ack 是最后接受的 seq。reason 包含 boot/connected/drive/stop/timeout/disconnected/invalid_command/stale_sequence。网页只将这些字段作为文本显示。

仅 drive 刷新运动期限，ping 不续命。500 ms 未收到有效 drive，独立高优先级任务下一次检查（周期 20 ms）停止；socket 关闭、无效数据或重复序号立即清零。非法应用帧终止会话；不发送复杂错误恢复包。TCP/Wi-Fi 保证传输校验，不另加 JSON CRC；这不提供身份认证，也不防御已知热点密码的恶意控制端。

HTTP 服务阻塞收发上限为每次 1 秒，控制任务独立执行保护。关闭连接后所有权释放；Wi-Fi 突然消失但 TCP 尚未感知时，控制权仍暂时保留，运动由命令期限停止。TCP 堆积的有效指令不具备绝对时间戳；未来真实硬件应增加会话握手、接收窗口与端到端新鲜度策略，并进行网络拥塞测试。
