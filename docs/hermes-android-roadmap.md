# Hermes Agent on Android — 自建 Roadmap

> 目标：不依赖官方（当前 broken 的）Termux APT 包，自己从零在安卓手机上跑起 Hermes Agent，
> 并让它能"控制手机"（点击/滑动/输入/截图/通知/短信/相机）。
>
> 核心判断：
> - **Hermes 的"脑子"**（LLM agent + 终端 + 文件 + 网页 + 消息网关）→ 能跑。
> - **Hermes 的"手"**（桌面 computer_use / cua-driver）→ **不在 Termux 环境里**，控制层要自己搭。
> - 控制手机 = `terminal` 工具调 **Termux:API** + **adb shell input** + 截图喂 vision，自己封装成 tool/skill。

---

## 路线选择

| 路线 | 优点 | 缺点 | 推荐 |
|---|---|---|---|
| **A. proot-distro (Ubuntu/Debian)** | glibc 环境，native wheel 全可用，官方 `install.sh` 直接跑，最接近桌面 | proot 有性能开销；访问 Android 硬件要桥接 | ✅ 首选 |
| **B. Termux 原生 pip** | 轻量、无 proot 层 | Termux 默认 Python 3.14 **超出** Hermes 要求(>=3.11,<3.14)，要从 TUR 装 3.13；native 依赖要自己编译；browser/voice 都没有 | 备选 |

下面以 **路线 A** 为主线，B 在阶段 1 给备选命令。

---

## 阶段 0 — 设备与前置（~10 分钟）

- [ ] 确认 **arm64 (aarch64)** 设备、Android 11+（推荐 13+）
- [ ] 装 **Termux**（F-Droid 版；Play Store 版已停更，别用）
- [ ] 装 **Termux:API**（配套 app，F-Droid，同名）
- [ ] 存储 ≥ 3 GB
- [ ] 给 Termux 加**电池优化白名单** + `termux-wake-lock`

验收：
```bash
getprop ro.product.cpu.abi      # 应为 arm64-v8a
termux-wake-lock                # 不报错
termux-wake-lock && termux-battery-status   # Termux:API 生效
```

---

## 阶段 1 — Linux 环境（proot-distro）

```bash
pkg update && pkg upgrade
pkg install -y proot-distro
proot-distro install ubuntu          # 或 debian
proot-distro login ubuntu
```

进入 Ubuntu 后：
```bash
uname -a                             # 应显示 glibc Linux
apt update && apt install -y curl git
```

**备选（路线 B，Termux 原生）**：
```bash
pkg install -y git python clang rust make pkg-config libffi openssl nodejs ripgrep ffmpeg
pkg install tur-repo
pkg install python3.13               # 关键：绕开默认 3.14
```

验收：`uname -a` 出 glibc Linux（A）/ `python3.13 --version` = 3.13.x（B）。

---

## 阶段 2 — 装 Hermes 并跑通

路线 A（proot Ubuntu 内）：
```bash
curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash
hermes setup                          # 配 provider / model / API key
```

路线 B（Termux 原生）：
```bash
git clone https://github.com/NousResearch/hermes-agent.git && cd hermes-agent
python3.13 -m venv venv && source venv/bin/activate
export ANDROID_API_LEVEL="$(getprop ro.build.version.sdk)"
python -m pip install -e '.[termux]'
hermes setup
```

验收：
```bash
hermes --version
hermes doctor                         # 全绿
hermes chat -q "ping"                 # 有模型回复
```

> 到这一步 = "脑子"在手机上跑起来了。下面才是"手"。

---

## 阶段 3 — 控制手机层（自己搭，核心）

分三块，全部通过 Hermes 的 `terminal` 工具触发。

### 3a. Termux:API 桥接（通知/短信/相机/剪贴板/电量/传感器）
Termux:API 运行在 **Android 进程层**，proot 里要桥接：
```bash
# proot Ubuntu 内，把 Termux 层的 termux-* 二进制 + 库链接进来
ln -sf /data/data/com.termux/files/usr/bin/termux-* /usr/local/bin/
# 若报缺 libtermux.so：
export LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib:$LD_LIBRARY_PATH
```
验收：`termux-notification -s test -m "from hermes"` 弹出通知。

### 3b. 屏幕操控（tap / swipe / text）
- **非 root（推荐）**：开 **ADB 无线调试**，proot 里装 adb：
  ```bash
  apt install -y adb
  adb connect <手机IP>:5555
  adb shell input tap 540 960
  adb shell input swipe 540 1500 540 500 300
  adb shell input text "hello"
  ```
- **root**：直接 `input tap ...`（免 adb）。

验收：`adb shell input tap 540 960` 屏幕有反应。

### 3c. 截图
```bash
adb shell screencap -p /sdcard/s.png
adb pull /sdcard/s.png ./screen.png
```
验收：本地拿到一张 `screen.png`。

---

## 阶段 4 — 把控制能力接进 Hermes（形成闭环）

让 Hermes 的 LLM 真正"能操作手机"，二选一：

- **方案 1（快）**：写一个 **skill**，教 Hermes 用 `terminal` 调 3a/3b/3c 的命令，
  并规定"先截图 → `vision_analyze` 看屏幕 → 决策坐标 → tap/swipe"的循环。
- **方案 2（干净）**：写一个 **MCP server / 自定义 tool**，封装
  `tap(x,y)` / `swipe(...)` / `type(text)` / `screenshot()` / `notify(...)` / `sms(...)` / `camera()`，
  注册进 Hermes，LLM 直接调用。

闭环验证：
> 对 Hermes 说"打开设置" → 它截图 → vision 定位"设置"图标 → tap → 再截图确认。

---

## 阶段 5 — 持久化 & 后台

- 装 `termux-services`（Termux 的 systemd 替代）跑 gateway：
  ```bash
  pkg install termux-services
  # 建 service 脚本跑: hermes gateway run
  termux-service start hermes-gateway
  ```
- 开机自启 + `termux-wake-lock`。
- 注意：Android 仍可能杀后台，白名单 + wake-lock 能缓解但**不保证**。

验收：锁屏 / 重启后 `hermes gateway` 仍在监听。

---

## 风险 & 坑清单

| 坑 | 说明 / 对策 |
|---|---|
| 官方 APT 包 broken | 走 proot(A) 或 Termux 原生(B)，不碰官方包 |
| Python 版本 | Termux 默认 3.14 超范围 → B 路线要 TUR 装 3.13；A 路线无此问题 |
| proot 性能 | 比原生慢，CPU 密集任务明显；日常 agent 够用 |
| Android 杀后台 | wake-lock + 电池白名单，不保证常驻 |
| 非 root 屏幕操控 | 依赖 ADB 无线调试，需保持 ADB 授权 |
| 截图/相机权限 | 首次要授权；Termux:API 相机需运行时权限 |
| 无 desktop computer_use | 控制层全靠自己封装（阶段 4） |
| voice (faster-whisper) | 依赖 ctranslate2，无 Android wheel → 手机端没有本地语音 |

---

## 时间估算

| 阶段 | 工作量 |
|---|---|
| 0–2（环境 + 跑通 Hermes） | 半天 |
| 3（控制层：Termux:API 桥接 + ADB + 截图） | 1–2 天 |
| 4（封装成 tool/skill + 视觉闭环） | 1–2 天 |
| 5（持久化/后台） | 半天 |
| **合计到"能自主控制手机"** | **约 3–5 天** |
