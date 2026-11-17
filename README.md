# DollyBot Android App

DollyBot 是通过经典蓝牙 SPP 控制小车、云台和轨迹录制的 Android App。当前发布的是可安装的测试版，配套 Python 模拟车用于没有实车时的联调。

## 下载与安装

**[下载 DollyBot.apk](apk/DollyBot.apk)**

- Android 7.0（API 24）及以上。
- 安装包为调试签名测试版，不是 Google Play 正式版本。
- 首次安装时按系统提示允许“附近设备”权限。
- 如果系统阻止安装，请在系统设置中临时允许当前文件管理器或浏览器安装未知来源应用。
- 安装前请确认文件来自本仓库；SHA-256：

  ```text
  ACD835C0906ACDF745C835BFBDA37FE4CEC83B79F2E0BEDAB567B6036CEF35EF
  ```

## 使用 App

1. 在 Windows 蓝牙设置中与电脑配对，并创建蓝牙“传入”串口。
2. 打开模拟车或实车，启动 App 的设备页，刷新已配对设备并连接。
3. 等待握手完成，停车后点击“校准”。校准会建立新的坐标系。
4. 控制页支持车身移动、旋转和云台偏航/俯仰控制；松手或离开页面会停止车身运动。
5. 点击“录制”选择模式：
   - **连续录制**：车端周期上传位姿，App 记录为连续轨迹。
   - **取点**：点击“取点”保存当前位姿。
   - **推车**：App 请求车端开始，收到确认后记录车身和云台位姿；录制期间暂停车身控制，保留云台控制。
6. 点击“停止”后，App 会先保存未命名草稿。可以立即命名保存、稍后保存，或放弃。推车轨迹归入“连续轨迹”，可离线预览和编辑。

## 启动模拟车

需要 Python 3、`pyserial` 和 Tk。Windows 示例：

```powershell
Set-Location 'D:\Android\project\Dollybot'
python -m pip install pyserial
python -m simulator.app --port COM4
```

也可以设置周期上报间隔，范围是 20–1000 ms，默认 50 ms：

```powershell
python -m simulator.app --port COM4 --status-interval-ms 100
```

打开串口后周期设置会锁定。模拟车窗口显示连接状态、校准状态、车身位姿、云台角度、录制模式、轨迹和采样数。推车模式会自动生成一段圆弧位姿；App 发送第一条云台指令后，模拟车改为保持 App 指定角度。

更完整的串口、协议和推车联调说明见 [`simulator/README.md`](simulator/README.md)。

## 从源码构建

需要 Android Studio 或 JDK 11+、Android SDK 37 和 Gradle Wrapper。Windows PowerShell：

```powershell
Set-Location 'D:\Android\project\Dollybot'
.\gradlew.bat :app:assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。发布包使用当前源码重新构建后复制为 `apk/DollyBot.apk`；本目录中的 `app/build/` 仅是本地生成目录，不应提交。

## 测试

Android 单元测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

模拟车主机测试：

```powershell
python -m unittest discover -s simulator/tests -v
```

若连接 Android 模拟器或真机，可运行仪器测试：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

主机测试和 Android 测试不等同于真实蓝牙、实车运动或硬件验收；本发布包未自动烧录固件，也未修改或擦除 Flash。

## 目录

| 路径 | 内容 |
| --- | --- |
| `app/` | Android App 源码、资源和测试 |
| `simulator/` | Python 蓝牙模拟车和协议测试 |
| `apk/DollyBot.apk` | 唯一的可下载测试版 APK |
| `gradle/`、`gradlew*` | Gradle Wrapper 和构建配置 |
| `AGENTS.md` | 项目开发约定 |

当前仓库发布内容未包含正式签名密钥，也没有附带开源许可证。公开发布前请根据项目需要补充许可证和正式签名流程。
