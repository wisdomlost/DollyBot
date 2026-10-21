# DollyBot 开发约定

- ESP-IDF v5.5.4，经典 ESP32；`idf.py build`。不擅自烧录、擦除 Flash。
- `web/` 纯静态资源，通过 main/CMakeLists.txt 内嵌。协议、控制、网络和运动接口各自独立。
- 保留 500 ms 运动期限及独立检查任务；ping 不刷新期限；新会话必须停机；不得伪造实测数据。
- `motion_link_set` 接入 UART 后必须非阻塞，不在控制互斥锁内等待硬件。
- 修改控制/协议需运行 `tools/test.ps1`；明确报告主机测试、固件编译、硬件测试的区别。
- 生成配置与 build 不入库，私密热点参数仅本地设置。保留历史及用户修改，按节点提交。
- 禁止批量删除文件或目录，禁止 del /s、rd /s、rmdir /s、Remove-Item -Recurse、rm -rf。需要删除时仅一次删除一个明确文件；批量删除必须由用户手动完成。
