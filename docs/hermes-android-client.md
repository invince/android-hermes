# Hermes Phone — 实现方案 v2
## App 内嵌 proot · 无 Termux · 无真 chroot · root 控控制层

> 前提：Android arm64，已 root（Magisk，非必需但推荐）。
> v1（真 chroot）存在硬伤：SELinux enforcing、`/dev`/`/proc` mount、持久 root shell —— 见「§8 为什么改 proot」。
> v2 主线：**proot 跑 Ubuntu rootfs，嵌入 App 内，零 Termux、零真 mount、零 SELinux 烦恼**；root 只用于控制层。

---

## 0. 变更摘要（v1 → v2）

| 项 | v1（真 chroot） | v2（proot） |
|---|---|---|
| 运行环境 | root + `chroot rootfs` | `proot -0 -r rootfs`（用户态，无需真 mount） |
| SELinux | 需 permissive/写策略（**最大风险**） | ❌ 不涉及（proot 不走 mount） |
| /dev /proc /sys | 手动 bind mount（易缺节点） | `proot -b /dev -b /proc -b /sys`（proot 内部处理） |
| Termux 依赖 | 无 | 无（proot 二进制 + rootfs 都 App 自带/自下载） |
| root 需求 | 必须 | 控制层用；运行环境可不依赖 root |
| 启动器 | App exec chroot | App exec **proot** |

---

## 1. 架构

```
┌────────────────────────── HermesPhone.apk (Kotlin) ──────────────────────────┐
│                                                                              │
│  ┌──────────────┐   ┌────────────────────┐   ┌──────────────────────────┐   │
│  │ RootfsManager│   │  ProotService      │   │      PhoneControl        │   │
│  │ 下载 proot   │──▶│  exec: proot -0    │──▶│  su -c input/pm/am/...   │   │
│  │ + rootfs     │   │  -r rootfs /usr/bin│   │  tap/swipe/shot/装App    │   │
│  │ 解压/校验     │   │  /hermes gateway   │   │  (控制层, 无 ADB)         │   │
│  └──────────────┘   └─────────┬──────────┘   └──────────────────────────┘   │
│                               │  localhost:8787                              │
│  ┌────────────────────────────▼──────────────────────────┐                   │
│  │  MainActivity: WebView ──▶ hermes-webui (聊天界面)      │                   │
│  └───────────────────────────────────────────────────────┘                   │
│  BootReceiver: 开机自启 proot gateway                                          │
└──────────────────────────────────────────────────────────────────────────────┘
```

**核心**：proot 把"Ubuntu rootfs"透明地跑在 Android 内核上，App 只负责 `exec proot`。**不做真 mount ⇒ 不碰 SELinux ⇒ v1 的 3 个致命坑直接消失。**

---

## 2. 为什么 proot，而不是 Linux Deploy / 真 chroot

先厘清：**Linux Deploy 是成品 App，proot 是执行机制**，不是同一层东西。Linux Deploy 官方 README 写明它用 `chroot` 环境、**必须 root**、**2012–2019 停更**。

| 维度 | Linux Deploy | proot |
|---|---|---|
| **本质** | 成品 App（装/挂载/SSH/VNC/启停） | 执行机制（单一二进制） |
| **机制** | `chroot`（内核级） | `ptrace` 模拟 chroot（用户态） |
| **root** | **必须**（chroot + mount） | **不需要** |
| **SELinux** | 要处理（内部 setenforce 等） | ✅ 绕开 |
| **mount** | 真 mount 镜像/分区（易缺 /dev 节点） | `-b` 用户态重定向，不真 mount |
| **能否嵌进你的 App** | ❌ 独立 App，不能当库 | ✅ 静态二进制，随便嵌 |
| **自带功能** | 一键装 Alpine/Ubuntu/Debian/Kali…、SSH/VNC/X11、开机自启 | 无，全自己写 |
| **性能** | 接近原生（无 ptrace 开销） | 慢一些（每 syscall 走 ptrace） |
| **维护状态** | ⚠️ 2012–2019 停更 | Termux 生态在用，活跃 |
| **新 Android 兼容** | ⚠️ 风险大（Android 10+ SELinux 更严） | 较稳 |

**对"单 App"目标的适配**：Linux Deploy 自成体系，没法塞进你的 APK（只能变成"两个 App"）；proot 可嵌入，生命周期由你的 `ProotService` 管。

**结论**：别用 Linux Deploy（chroot + 停更 + 破坏单 App），走 **proot 嵌入**。唯一代价是性能慢一点，但 Hermes 是 API 密集，感知很小。

---

## 3. proot 启动命令（核心，已避开 v1 坑）

```sh
PROOT=/data/local/hermes/bin/proot
ROOTFS=/data/local/hermes/rootfs

exec $PROOT \
  --link2symlink \
  -0 \
  -r $ROOTFS \
  -b /dev -b /proc -b /sys \
  -b /sdcard \
  -b /data/local/hermes/hermes-home:/root/.hermes \
  -w /root \
  /usr/bin/env HOME=/root PATH=/usr/local/bin:/usr/bin:/bin \
  /usr/bin/hermes gateway run
```

参数逐个解释：
| 参数 | 作用 |
|---|---|
| `--link2symlink` | Android 文件系统（f2fs）不支持硬链接时，proot 用符号链接替代（否则 pip/apt 会炸） |
| `-0` | 假装 uid/gid 0（rootfs 里 `whoami`=root，方便装东西） |
| `-r $ROOTFS` | 把 rootfs 当作 `/` |
| `-b /dev -b /proc -b /sys` | proot **用户态**重定向，不是真 mount → 无 SELinux |
| `-b .../hermes-home:/root/.hermes` | **关键**：配置/记忆持久化到 rootfs 外（重建 rootfs 不丢） |
| `-w /root` | 工作目录 |

> 首次装 Hermes：同一条命令把末尾换成 `sh -c "apt update && curl ... install.sh | bash"`，只跑一次。

---

## 4. rootfs 与 proot 二进制获取

**rootfs（Ubuntu 24.04 arm64）** —— 任选：
```bash
# A. docker export（在 DGX 上，最干净）
docker pull --platform linux/arm64 ubuntu:24.04
docker create --platform linux/arm64 --name htmp ubuntu:24.04 /bin/sh
docker export htmp | gzip > ubuntu-arm64-rootfs.tar.gz
docker rm htmp
scp ubuntu-arm64-rootfs.tar.gz phone:/sdcard/

# B. proot-distro 下载的 tar 直接复用
# C. debootstrap --arch=arm64 noble ...
```

**proot 二进制**（arm64 静态）：从 Termux 包或 proot 官方 release 取单个静态 `proot`，随 App assets 打包或首次下载。

> 放置：`/data/local/hermes/{bin/proot, rootfs, hermes-home}`（ext4/f2fs，别用 `/storage/emulated/0`）。

---

## 5. App 组件（含 v1 review 的修正）

### 5.1 RootShell.kt —— 修正：流读取死锁 + 持久 shell
```kotlin
object RootShell {
    private val proc by lazy { ProcessBuilder("su").start() }   // 持久 shell，只授权一次
    private val out by lazy { proc.outputStream.bufferedWriter() }
    private val inp by lazy { proc.inputStream.bufferedReader() }

    @Synchronized
    fun exec(cmd: String, timeoutMs: Long = 20_000): String {
        out.write(cmd + "\n echo __EOF__\n"); out.flush()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = inp.readLine() ?: break
            if (line == "__EOF__") break
            sb.appendLine(line)
        }
        return sb.toString()
    }
    fun ok(cmd: String) = exec(cmd)
}
```
> 修正 v1 的两个 bug：① 持久 shell 避免 `readText()` 双流死锁；② 只授权一次，连续 tap 不卡。

### 5.2 ProotService.kt —— 修正：不能阻塞主线程
```kotlin
class ProotService : Service() {
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        startForeground(1, buildNotification())            // 前台，防杀
        Thread {
            ProcessBuilder("sh", "/data/local/hermes/run-gateway.sh")
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().forEachLine { Log.i("hermes", it) }
        }.start()                                          // 后台线程，不 ANR
        return START_STICKY
    }
}
```
> 修正 v1：gateway 是长期进程，绝不能在 `onStartCommand` 同步 `readText()`。

### 5.3 MainActivity.kt —— 修正：cleartext + WebView
```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (!RootfsManager.ready()) RootfsManager.setup()      // 首次：下载+解压
        ContextCompat.startForegroundService(this, Intent(this, ProotService::class.java))
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            loadUrl("http://127.0.0.1:8787")                   // webui
        }
        setContentView(wv)
    }
}
```
**Manifest 必须加**（v1 漏，会白屏）：
```xml
<application android:usesCleartextTraffic="true" ...>
<service android:name=".ProotService"
         android:foregroundServiceType="dataSync" />
```
> `usesCleartextTraffic` 放行 `127.0.0.1`（或更严：`network_security_config` 只放行 localhost）。
> `foregroundServiceType` 是 Android 14+ 硬要求，v1 漏了会启动失败。

### 5.4 PhoneControl.kt —— 修正：命令注入 + 转义
```kotlin
object PhoneControl {
    fun screenSize(): Pair<Int,Int> =
        Regex("(\\d+)x(\\d+)").find(RootShell.ok("wm size"))!!
            .destructured.let { it.first.toInt() to it.second.toInt() }

    fun tap(x:Int,y:Int)                  = RootShell.exec("input tap $x $y")
    fun swipe(x1:Int,y1:Int,x2:Int,y2:Int,ms:Int=300) =
                                          RootShell.exec("input swipe $x1 $y1 $x2 $y2 $ms")
    fun text(s:String)                    = RootShell.exec("input text ${shellQuote(s)}")
    fun screenshot():String { RootShell.exec("screencap -p /data/local/tmp/_h.png")
                              return "/data/local/tmp/_h.png" }
    fun install(apk:String)               = RootShell.exec("pm install -r ${shellQuote(apk)}")
    fun launch(comp:String)               = RootShell.exec("am start -n ${shellQuote(comp)}")
    fun setting(k:String,v:String)        = RootShell.exec("settings put global $k ${shellQuote(v)}")
    fun logcat(n:Int=50)                  = RootShell.ok("logcat -d -t $n")

    private fun shellQuote(s:String) = "'" + s.replace("'", "'\\''") + "'"   // 防注入
}
```
> 修正 v1：`input text '$s'` 遇 `'` 会炸/注入 → 用 `shellQuote`。截图写 `/data/local/tmp` 而非 `/sdcard`（避免 scoped storage）。

### 5.5 RootfsManager（要点）
- 下载 1.5GB：**前台 Service + 断点续传 + sha256 校验 + 空间检查**（v1 只写了"下载"，不完整）
- 解压到 `/data/local/hermes/rootfs`，`hermes-home` 单独建并 bind 进 proot
- 失败要有 UI 状态（onboarding 状态机：检查 root → 下载 → 解压 → 装 Hermes → 起 gateway → 就绪）

---

## 6. Milestone

| # | 交付 | 验收 |
|---|---|---|
| **M0** | **spike**：手动 `proot -0 -r rootfs uname -a`（先验证 proot 能不能跑） | 出 Ubuntu，`apt update` 通过 |
| **M1** | 最小 App：RootShell 持久 shell + WebView 静态页 | `su` 一次授权，连续 exec 不卡 |
| **M2** | RootfsManager 下载/解压/校验 | rootfs 就位，proot 能进 |
| **M3** | ProotService 起 gateway + 首次装 Hermes | `:8787` 可访问，`hermes doctor` 绿 |
| **M4** | WebView 连 webui 聊天 | App 内对话成功 |
| **M5** | PhoneControl + 注入转义 | "打开设置"→截图→tap 成功 |
| **M6** | BootReceiver 自启 + 前台 Service + 签名打包 | 重启后 gateway 自动起 |

---

## 7. 坑 & 风险（v2 更新）

| 坑 | 对策 |
|---|---|
| proot 性能（ptrace 开销） | 接受；Hermes 是 API 密集，日常感知小；CPU 密集操作避开 |
| `--link2symlink` 必须开 | 否则 apt/pip 硬链接失败（f2fs） |
| rootfs 下载 1.5GB | 前台 Service + 续传 + sha256 |
| WebView cleartext | manifest `usesCleartextTraffic` 或 networkSecurityConfig |
| 前台服务类型 | Android 14+ `foregroundServiceType` |
| 持久 root shell 泄漏 | App 退出/Service 停止时 `proc.destroy()` |
| 命令注入 | 所有用户输入 `shellQuote` |
| `~/.hermes` 丢失 | bind mount 到 `hermes-home`（已含） |
| proot 与 SELinux | 不涉及（proot 不 mount）；但 `su` 仍受 Magisk 策略，正常 |
| 小屏 webui 适配 | M0 先用手机浏览器验证 8787 布局 |

---

## 8. 为什么改 proot（v1 review 摘要）

v1（真 chroot）的 4 个硬伤：
1. **SELinux enforcing**：即使 Magisk root，chroot 进程仍受约束，多数设备要 `setenforce 0`（安全降级）或写 sepolicy —— 最大不确定性。
2. **`/dev` 处理**：`mount -o bind /dev` 进 Ubuntu 会缺 `/dev/pts`、`/dev/urandom`，`apt`/`install.sh` 直接挂。
3. **持久 root shell**：每次 `su -c` 新起进程，连续 tap 极慢。
4. **App 侧 bug**：`RootShell.run` 双流 `readText()` 死锁；`ProotService` 同步起 gateway → ANR；WebView cleartext 未配 → 白屏。

proot 一次性解决 1、2（不 mount），本文件 5.1/5.2/5.3 解决 3、4。

---

## 9. 一句话

**v2 = proot 嵌入 App（摆脱 Termux + 绕开 SELinux/mount）+ WebView 连 webui + root `su` 控制层。**
先跑 M0 spike 验证 proot，再按 M1→M6 落地。

---

## 10. 实测验证（LineageOS Android 16 arm64 VM，已通过）

在 `~/vm/lineage` 的 QEMU/KVM LineageOS VM（Android 16 / arm64-v8a / Magisk root）上，用 overlay 快照端到端跑通：

```
Hermes Agent v0.22.2 (2026.9.24) · stable · local d923a5eb487a
Install directory: /root/.hermes/hermes-agent
Install method: git
```
`hermes doctor`：delegation / file / memory / terminal / skills / web search / tts / todo 全部 ✓；
仅 a2a/browser/computer_use/vision 等需额外依赖或 API key 的项 ⚠（正常）。
唯一待办：`hermes setup` 配 API key。

### 实测踩到的 12 个坑（照此配置即通）

| # | 坑 | 正确配置 |
|---|---|---|
| 1 | Android 无 glibc，Ubuntu 的 proot 跑不了 | 必须用 **Termux 版 proot**（`/system/bin/linker64` 解释器） |
| 2 | `execve("/bin/sh"): No such file` | 必须设 **`PROOT_LOADER`** 指向 proot 的 `libexec/proot/loader` |
| 3 | proot 起不来 / 缺库 | 必须带 **`libandroid-shmem.so` + `libtalloc.so.2`**，设 `LD_LIBRARY_PATH` |
| 4 | **不要 `--link2symlink`** | `/data/local/tmp` 支持真 hardlink（实测 `ln` 成功）；加它反而让 uv hardlink 报 `EPERM` |
| 5 | `apt` 写不了 root 属主目录 | `proot -0` 是**假** root；用**真实 `su -c`** 跑 proot |
| 6 | `TMPDIR` 让 apt/dpkg 失败 | 两层：proot 自己用主机路径 `$BASE/tmp`；rootfs 内进程用 `TMPDIR=/tmp` |
| 7 | 装完没网（`Network is unreachable`） | Android policy routing：`ip rule add from all lookup eth0 pref 100`；DNS 用 slirp 的 `10.0.2.3` |
| 8 | `cua-driver` 装不上（缺 `libXi.so.6`） | 无需修——它就是 desktop computer_use，手机上本来不用 |
| 9 | `hermes dashboard` 的 CHAT 其实是 PTY 里的 TUI（侧栏像 console） | 改用 **nesquena/hermes-webui**（原生 SSE 流式聊天），默认端口同为 8787，可直接替换 |
| 10 | webui 在 tarball 安装下报 `Failed to fetch` / 版本 `unknown` | 用 Hermes 托管 Python 跑 `server.py`，装 **`pyyaml` + `cryptography`**；`v=unknown` 只影响更新提示，不影响聊天 |
| 11 | App 内 WebView 输入全无反应（每 0.1s 重载一次首页） | WebView 会把 URL 补成结尾 `/`，而 webui 每个请求都打日志→`SetupState`→`render()`；用 `loadedWebUiUrl` 只加载一次 |
| 12 | composer/发送键被系统导航栏和输入法盖住 | targetSdk 35 edge-to-edge：`windowSoftInputMode=adjustResize` + root padding `max(nav, ime)` inset |

### 可用脚本（`/workspace/personal/android-hermes/scripts/`）
- `step-apt.sh` — apt update + curl + ca-certificates
- `step-install-hermes.sh` — 官方 install.sh 装 Hermes（**无 `--link2symlink`**）
- `verify-hermes.sh` — `hermes --version` + `doctor`
- `m0-proot-test.sh` / `deploy-test-proot.sh` — proot 机制验证
- `test/run-test.sh` — overlay 快照启动 VM

---

## 11. LLM 对话 + WebUI 验证（已通过）

### 对话
配 `DEEPSEEK_API_KEY` / `DEEPSEEK_BASE_URL` 后：
```
hermes chat -q "Reply with exactly: PONG"  →  PONG（6s）
model.provider=deepseek, model.default=deepseek-chat
```
✅ Android proot 里能正常调云端 LLM。

### WebUI（v3：已换成 hermes-webui 原生聊天 UI）

`hermes dashboard` 的 Chat 标签实际是内嵌 PTY 跑 TUI，不是真正的 Web 聊天。现在默认安装
**nesquena/hermes-webui**（Python + vanilla JS，无构建步骤），端口同为 8787：

```
安装：下载 pinned tarball → 解压到 $BASE/webui（bind 到 /opt/hermes-webui）
依赖：托管 Python（~/.hermes/tools/python-3.1*）+ pip install pyyaml cryptography
启动：HERMES_HOME=/root/.hermes HERMES_WEBUI_AGENT_DIR=/root/.hermes/hermes-agent
      HERMES_WEBUI_PORT=8787 PYTHONPATH=/opt/hermes-webui python3 server.py
回退：server.py 不存在时 run-hermes.sh 仍 exec hermes dashboard
```

VM 实证（App 内 WebView，不是浏览器）：
- ✅ 原生聊天 UI（侧栏 WebUI sessions / CLI sessions、消息气泡、SSE 流式）
- ✅ 输入 PONG → "PONG received — what do you need?"（5s）
- ✅ 历史 CLI 会话可见，可接管继续聊
- ✅ 重启 App 后 webui 自动拉起；`/health` 200

截图：`test/app-webui-vm.png`、`test/app-chat-vm.png`

> 注：tarball 没有 `.git`，顶部会显示 "Agent (origin/main): N updates available"，点 Later 即可；不影响功能。

### dashboard（旧方案，仅作回退）
`hermes dashboard --port 8787 --host 127.0.0.1` 启动后：
```
HERMES_DASHBOARD_READY port=8787
adb forward tcp:18787 tcp:8787   →  curl 200, <title>Hermes Agent - Dashboard</title>
```
用 **VM 内 Jelly 浏览器打开 `http://127.0.0.1:8787`**（真实 Android WebView 场景）截图验证：
- ✅ 正常渲染（深色主题 + 侧边栏 + 主区），无白屏/报错
- ✅ 小屏布局适配良好（侧边栏 CHAT/SESSIONS/FILES/MODELS/LOGS/CRON/SKILLS/PLUGINS/MCP，无挤压/溢出）
- ✅ **无登录墙**
- ℹ️ 侧栏显示 `Gateway Status: Off`（只起了 dashboard，chat 需另起 `hermes gateway run`）

截图：`test/webui-phone2.png`

### 结论
**proot 方案全链路实证可行**：Android 16 → proot Debian → Hermes v0.22.2 → LLM 对话 → WebUI，全部跑通，无需 Termux。
