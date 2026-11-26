# KioskSpike — Gitee 云编译验证工程

目的：验证「国内 Gitee 云端编译 APK」这条链路能不能跑通。
这是一个最小全屏 WebView 壳：全屏 + 同意麦克风权限 + 加载指定网址。

## 这个工程做了什么

- 沉浸式全屏（隐藏状态栏/导航栏）
- WebView 加载 `MainActivity.java` 里 `URL` 常量指定的网址
- 自动同意麦克风/摄像头权限请求（绕开 `NotAllowedError`）
- 允许明文 HTTP（访问局域网 `http://192.168.x.x` 需要）
- 返回键优先回退网页历史

**注意**：用的是系统 WebView。AudioWorklet 能否运行取决于系统 WebView 版本，这一步要等真机验证。

## 怎么用（Gitee 网页操作，不用装任何环境）

### 1. 建仓库（关键：语言必须选 Android）

1. 登录 Gitee（gitee.com）→ 右上角 `+` → 新建仓库
2. 填写仓库名，比如 `kiosk-spike`
3. **重要**：编程语言选 **Android**（这样才会出现"下载APK"按钮；忘了可在仓库"管理"里改）
4. 勾选"使用 README 文件初始化仓库" → 创建

### 2. 上传代码

1. 进仓库 → `+` → **上传文件**
2. 把本 ZIP 解压后的**全部文件和文件夹**选上（含 `gradle/` 目录、`.github/` 目录、`gradlew`）
3. 提交

### 3. 云端构建

1. 仓库页面点 **"下载APK"** 按钮 → **"新建构建"**
2. 等约 5-10 分钟（Gitee 用国内镜像下载 Gradle，比较快）
3. 构建成功后刷新页面 → 下载 APK → 传到设备安装

## 改成加载你自己的页面

编辑 `app/src/main/java/com/example/kiosk/MainActivity.java`，找到这一行：

```java
private static final String URL = "https://example.com";
```

把 `https://example.com` 换成你的地址，例如 `http://192.168.0.249:8090/`，提交后重新构建。

## 常见失败

- **没有"下载APK"按钮**：仓库语言没设成 Android，去"管理"里改。
- **构建失败**：点开构建日志看报错，把报错贴给我。
- **gradlew 权限问题**：已设置为可执行，正常不会出现。
