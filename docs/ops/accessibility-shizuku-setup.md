# DSH Go 开通无障碍服务与 Shizuku 操作说明

- 适用版本：DSH Go 1.1.0+（Android 14+ 的自动恢复功能；Shizuku 通道本身不限版本）
- 实证设备：长城 pd2508（麒麟定制 Android 16 / SDK 36 / arm64），2026-11-24 真机全流程验证
- 面向读者：需要让 DSH Go「操控手机」（读屏/点击/执行系统命令）的用户与运维

## 0. 先分清三条通道（很多困惑来自混淆它们）

| 通道 | 干什么 | 开关位置 | 依赖 |
|---|---|---|---|
| **无障碍服务**（PhoneA11yService） | 读屏、点击、手势——`mcp__phone__screen/tap/...` 的基础 | 系统设置 → 无障碍 | 无（手动开）或 Shizuku（自动开/恢复） |
| **自动恢复无障碍**（PrivilegedDisplay） | 系统关掉无障碍后自动重新打开；顺带授予 WRITE_SECURE_SETTINGS | 手机操控 → 更多设置 → 自动恢复无障碍 | Shizuku / Sui / Root / 自连 ADB 四选一 |
| **Android Shell**（`android_shell` 工具） | 执行系统 shell 命令（如 `pm uninstall` 卸载应用） | 手机操控 → 更多设置 → Android Shell | Shizuku 或 自连 ADB；**总开关默认关闭** |

三条通道**互相独立**：授权了「自动恢复无障碍」不等于 Android Shell 可用；
引擎报 `android_shell is disabled` 时要去开第三条，不是重新授权 Shizuku。

## 1. 准备：安装并启动 Shizuku

1. 安装 Shizuku（`moe.shizuku.privileged.api`，官网/应用商店）。
2. **启动 Shizuku server**（关键：App 装了不等于 server 在跑）。三种方式任选：
   - **无线调试**（Android 11+，免电脑）：Shizuku App →「通过无线调试启动」，
     按提示配对。设备重启后需重做。
   - **ADB 有线/网络**（有电脑）：
     ```bash
     adb shell <Shizuku安装目录>/lib/arm64/libshizuku.so
     ```
     安装目录用 `adb shell pm path moe.shizuku.privileged.api` 定位
     （取 base.apk 所在目录）。例（路径随机段每次安装会变）：
     ```bash
     adb shell /data/app/~~xxxx==/moe.shizuku.privileged.api-yyyy==/lib/arm64/libshizuku.so
     ```
     成功标志：`adb shell ps -A | grep shizuku_server` 能看到
     **shell 身份**的 `shizuku_server` 进程。设备重启后需重跑。
   - **Root**：Shizuku App 内直接「启动」（root 设备可开机自启）。
3. 验证：Shizuku App 首页显示「Shizuku 正在运行」。

## 2. 开通无障碍服务

### 方式 A：手动（不依赖 Shizuku）

系统设置 → 无障碍 → 已下载的应用/服务 → **DSH Go** → 打开。
缺点：部分系统（含麒麟）会在锁屏/清理后台时关掉它，需要反复手开。

### 方式 B：Shizuku 自动开通 + 自动恢复（推荐）

1. DSH Go → 设置（左下角齿轮）→ **手机操控** → **更多设置** →
   **自动恢复无障碍**。
2. 点蓝色按钮「**先授权 Shizuku，再授予权限**」。
3. 弹出 Shizuku 授权框「要允许 DSH Go 使用 Shizuku 吗？」→ 点「**始终允许**」。
   - 多屏设备注意：授权框可能弹在**另一块屏**（如内置屏），不在 App 所在屏。
4. 成功后本页显示：
   - 无障碍：「系统允许 App 读屏和点击」✅
   - 自动恢复无障碍：「已开启。系统关掉无障碍时会被自动打开」✅
   - 顶部状态从「还差一步」变为「**可以操作手机**」。

原理：DSH Go 经 Shizuku binder 以 shell 身份给自己授予
`WRITE_SECURE_SETTINGS`，再写 `enabled_accessibility_services` 开启无障碍；
之后系统若关闭无障碍，同一通道自动恢复。

### adb 核验（可选）

```bash
# 无障碍已启用（应包含 dsh_app 的 PhoneA11yService）
adb shell settings get secure enabled_accessibility_services
# WRITE_SECURE_SETTINGS 已授予
adb shell dumpsys package io.github.ccch1mneyyy.dsh_app | grep WRITE_SECURE_SETTINGS
```

## 3. 开通 Android Shell（执行系统命令，如卸载应用）

> 症状对照：DSH 对话里让它卸载应用，报
> 「手机操控通道还没有切换到 Shizuku（错误代码 disabled）」→ 就是本节没做。

1. DSH Go → 设置 → 手机操控 → 更多设置 → 下滑 → **Android Shell**。
2. 打开「**允许 Android Shell**」总开关 → 弹风险确认框
   （提示：命令可能改设置/删文件/卸载应用；每条命令执行前仍单独确认）→
   点「**允许 Android Shell**」。
3. 「连接方式」选 **Shizuku**（默认已选中；另一选项是自连 ADB）。
4. 点「**连接所选通道**」→ 执行状态变为「**已连接**」。
   - Shizuku 授权若第 2 节已给「始终允许」，这里不会再弹框。
5. 回到 DSH 对话重试命令。每条命令仍会弹**原生确认框**由用户批准——
   这是设计内的安全门，不能也不应绕过。

### adb 核验（可选）

```bash
adb shell su 0 cat /data/data/io.github.ccch1mneyyy.dsh_app/shared_prefs/android-shell-v1.xml
# 期望：backend="shizuku"，enabled=true
```

## 4. 常见问题排查

| 症状 | 原因 | 处置 |
|---|---|---|
| 授权按钮点了没反应/提示「请在 Shizuku 中允许授权」 | 授权框弹在别的屏，或 server 没在跑 | 看另一块屏；`ps -A \| grep shizuku_server` 确认 server |
| 重启后全部失效 | ADB 方式启动的 shizuku_server 不持久 | 重跑 §1 启动命令（授权本身是「始终允许」，不用重新点）；或改用无线调试/root 自启 |
| 无障碍总被系统关掉 | 厂商后台管控 | 开「自动恢复无障碍」（§2B）+ 电池优化白名单（更多设置 → 后台运行） |
| `android_shell` 报 disabled | Android Shell 总开关没开 | §3 第 2 步 |
| `android_shell` 报 permissionRequired | Shizuku 授权被撤销 | Android Shell 页点「申请 Shizuku 授权」重新允许 |
| `android_shell` 报 shizukuStopped | server 停了 | 重跑 §1 启动命令 |
| Shizuku 更新后 ADB 启动命令失效 | 安装目录随机段变了 | `pm path moe.shizuku.privileged.api` 重新定位 |
| 多屏设备找不到授权框 | 框在 display 0（内置屏） | `uiautomator dump` 搜「始终允许」的 bounds，或直接看内置屏 |

## 5. 安全边界（为什么这样设计）

- Shizuku 提供的是 **shell(uid 2000)** 身份，不是 root；能做的限于 adb shell
  能做的事。
- Android Shell 双开关：总开关（本页）+ **每条命令的原生确认**；命令内容
  先完整显示再执行，确认前不执行。
- 「自动恢复无障碍」只写无障碍相关 secure settings，不代为执行任意命令。
- 所有授权可随时在 Shizuku App 里撤销；DSH Go 侧关闭对应开关即断开。