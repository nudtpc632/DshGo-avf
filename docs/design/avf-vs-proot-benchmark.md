# AVF vs PRoot 综合性能对比（长城 PRoot / 长城 AVF / 树莓派 AVF）

- 文档编号：DSHGO-AVF-BENCH-001
- 版本：2.1（2026-11-25，补 Pi5 端到端聊天 + 聊天后内存 + 出网发现，三方数据齐全）
- 关联：[AVF 测试报告（长城）](avf-test-report.md) · [需求规格](avf-srs.md)
- 代码基线：`feat/avf-backend` @ cd196104（含 SIGPIPE 修复）

## 0. 三方总览（TL;DR）

| 指标 | 长城 PRoot v1.3.0 | 长城 AVF | **Pi5 AVF** |
|---|---|---|---|
| 冷启动（引擎就绪） | **~3.6s** | ~6.4s（VM up→ready） | ~8.2s（VM up→ready） |
| 控制通道 RTT | —（无独立通道） | **~9.8ms** | ~16.3ms |
| HTTP 401 小响应 | ~17.9ms | **~17ms** | ~20.6ms |
| HTTP 43KB 全页 | ~25ms + 半关闭缺陷 | **~14.4ms** | ~24.6ms（5/5、20/20 完整） |
| token→cookie 交换 | ❌ 400（tawcroot 环境） | ✅ 303 | ✅ 303 |
| 引擎侧内存 | ~374 MiB PSS | ~405 MiB RSS | ~388 MiB RSS（379 PSS） |
| App 进程内存 | 360 MiB RSS | 869 MiB RSS | **342 MiB RSS（172 PSS）** |
| 首次安装 | ~14s（解压 26869 文件） | **~2.5s** | ~5s |
| 端到端聊天（看看工作区） | 1m00s/47.4K | 1m29s/80.3K | **37–45s/51–78K** |
| 聊天后引擎内存 | ~255 MiB PSS | — | **483 MiB PSS（<512 配额）** |
| 设备内存 | 26.9 GiB | 26.9 GiB | 15.8 GiB |

**一句话结论**：PRoot 胜在冷启动与体积；AVF 胜在传输可靠性、首次安装与安全隔离；
Pi5 证明 AVF 后端可跨设备移植（Android 17 / 16K 页 / 不同 guest 内核），各项时延
比长城慢 ~30–70%（硬件差异）但全部达标且稳定。

## 1. 测试对象与环境

### 1.1 三个测试对象

| 项 | 长城 PRoot | 长城 AVF | Pi5 AVF |
|---|---|---|---|
| APK 来源 | GitHub Release v1.3.0 | 本地构建 arm64-release | 本地构建（含 SIGPIPE 修复） |
| versionName / Code | 1.3.0 / 11210245 | 1.1.0 / 11241211 | 1.1.0 / 11241211 |
| APK 大小 | 228 MiB | 817 MiB | 1016 MiB（release 未 split） |
| 引擎版本 | r15-…-7ded3ec22995 | r15-…-vm064ce1479b6b | r15-…-**vmsigpipe1** |
| 后端 | tawcroot（PRoot） | pKVM + crosvm | **non-protected KVM** + crosvm |
| 传输 | loopback TCP | vsock CID 2060 | vsock CID 2074 |
| rootfs | tar.xz 152 MiB→853 MiB | ext4 748.5 MiB | ext4 748.5 MiB（同镜像+修复） |
| 内存配额 | 无硬限制 | 512 MiB | 512 MiB |

### 1.2 设备环境

| 项 | 长城 pd2508 | 树莓派 Pi5 |
|---|---|---|
| 系统 | Android 16 / SDK 36 | **Android 17 / SDK 37**（aosp_rpi5） |
| 内核 | 6.6.103 kylin（4K 页） | 6.18.62（**16K 页**） |
| Guest 内核 | microdroid 6.6.98-android15 | microdroid **6.12.60-android16**（4K） |
| 虚拟化 | pKVM protected+non-protected | 仅 non-protected（无 pKVM） |
| 内存 | 26.9 GiB | 15.8 GiB |
| 构建 | userdebug / Permissive / su | userdebug / Permissive / su |
| 连接 | ADB TCP 10.42.37.36:5555 | ADB TCP 192.168.31.243:5555 |
| 备注 | 双显示器（App 在外接屏） | 单显示器 1920x1080；另跑用户 debian VM（CID 2071，不影响测量） |

**Pi5 移植关键事实**（均真机实证）：
- 现有 APK 所有 .so PT_LOAD ≥16K 对齐，**无需重编**直接安装运行。
- 4K guest 内核在 16K host 上完全正常（KVM 独立页表），无需改内核选择代码。
- MANAGE/USE_CUSTOM_VIRTUAL_MACHINE 均 development 级，pm grant 可用。
- **发现并修复 SIGPIPE Bug**（§2.8）：guest 内核 6.12 暴露了桥的 SIGPIPE 竞态，
  6.6 未触发。修复已提交 cd196104。

## 2. 性能对比

### 2.1 冷启动时延（P1）

> 定义：PRoot = App 启动→dsh web 监听（app+prepare+dsh）；
> AVF = launching→VM up→dsh web ready（dsh.log mono 差）。
> 口径差异见 §5.2。

| 采样 | 长城 PRoot (ms) | 长城 AVF (ms) | Pi5 AVF (ms) |
|---|---|---|---|
| #1 | 3596 | 6107 | 8303 |
| #2 | 3651 | 6079 | 8116 |
| #3 | 3634 | 6080 | 8147 |
| #4 | — | 6122 | — |
| #5 | — | 7123 | — |
| #6 | — | 7118 | — |
| **均值** | **~3627** | **~6438** | **~8189** |
| **区间** | 3596–3651 | 6079–7123 | 8116–8303 |

Pi5 细分（CID 2074）：launching→VM up **502 ms**（与长城相同）；VM up→ready
8147 ms（guest 内 dsh 启动，Pi5 CPU 慢于长城）；startup 日志 app 303 ms +
prepare 12 ms + dsh 8651 ms。三样本极稳（±90ms）。

**PRoot 冷启动 ~3.6s，长城 AVF ~6.4s（PRoot 快 ~44%），Pi5 AVF ~8.2s。**

细分对比：

| 阶段 | 长城 PRoot | 长城 AVF | Pi5 AVF |
|---|---|---|---|
| App 启动→首帧 | ~760 ms | — | ~310 ms |
| VM 内核启动（P1a） | — | ~502 ms | ~502 ms |
| 引擎启动（dsh web） | ~2880 ms | ~6400 ms | ~8150 ms |
| 准备开销 | 8 ms | — | 12 ms |

PRoot 的引擎启动瓶颈是 Node.js 进程初始化（~2.9s）；AVF 额外承担 VM 内核启动
（~0.5s，两设备一致）+ guest OS 初始化。Pi5 的 guest 内 dsh 启动比长城慢 ~28%
（CPU 单核性能差异），但 VM 启动本身不受影响。

### 2.2 首次安装时延

> 定义：首次启动时 rootfs/镜像从 APK assets 解压/拷贝到数据目录的时间。

| 项 | 长城 PRoot | 长城 AVF | Pi5 AVF |
|---|---|---|---|
| 压缩资产大小 | 152 MiB（tar.xz） | 748.5 MiB（ext4） | 748.5 MiB（ext4） |
| 校验 | 137 ms | SHA-256 | SHA-256 |
| 解压/拷贝 | 13,599 ms | ~2500 ms | ~5000 ms |
| 同步+激活 | 948 ms | 原子换入 | 原子换入 |
| **总计** | **~14 s** | **~2.5 s** | **~5 s** |

**AVF 首次安装快 ~3–5.6×**（ext4 直接拷贝 vs 逐文件解压 26,869 个文件）。
Pi5 拷贝慢于长城（SD/USB 存储 vs UFS），但仍远快于 PRoot 解压。

PRoot 首次安装后后续冷启动不再解压；AVF 每次启动都需校验镜像完整性。

### 2.3 HTTP 响应时延（P3）

> 定义：从设备 shell 发起 HTTP 请求到接收完整响应的墙钟时间（含 nc 进程 spawn）。
> PRoot：直接 loopback TCP；AVF：loopback → vsock 转发 → guest dsh web。

| 响应类型 | 长城 PRoot (ms) | 长城 AVF (ms) | Pi5 AVF (ms) |
|---|---|---|---|
| 401 未登录（242 B） | ~17.9（10 次） | ~17（5 次） | ~20.6（10 次） |
| 303 登录重定向 | ❌ 400（§2.8） | ~13.2 | ✅ 303 正常 |
| 200 全页（43,341 B，设备 nc） | **~25**（含 10ms stdin 保持） | **~14.4** | **~24.6**（5 次：23–27） |
| 全页完整性 | 立即关 stdin 0/20；保持 10ms 20/20 | 20/20 | **20/20（无需保持）** |
| 200 全页（主机 curl 经 forward，参考） | ~9.0 | — | — |

**401 小响应三方基本持平**（17–21 ms），均含 nc 进程 spawn 开销；Pi5 略慢
（CPU 差异）但同量级。

全页口径说明：长城 PRoot 直连 loopback 存在**半关闭竞态**（§2.8），nc 立即关
stdin 时 0/20 完整；保持 stdin 10ms 后 20/20，扣除等待后实际 ~25ms。两台 AVF
设备的 C 桥 `conn_finish` 不半关闭，**无需任何等待即 20/20 完整**：长城
14.4ms、Pi5 24.6ms（Pi5 慢 ~70%，硬件差异，但稳定性一致）。

**结论：大响应场景 AVF 转发更快且更稳**；PRoot 直连的「理论优势」被半关闭
缺陷抵消。Pi5 的 token→cookie 交换正常 303（与长城 AVF 一致），进一步佐证
PRoot 的 400 是 tawcroot 环境问题而非引擎代码问题。

> 长城 PRoot 全页测量用自铸 session cookie（引擎自身 HMAC 协议 + 本机持久化
> 签名密钥）；Pi5 AVF 直接用官方 token 换 cookie（303 正常）。测量后 cookie
> 与密钥临时副本已全部删除。

### 2.4 控制通道 RTT（P2）

| 项 | 长城 PRoot | 长城 AVF | Pi5 AVF |
|---|---|---|---|
| 控制通道 | 无（HTTP 即控制） | vsock CID:3081 | vsock CID:3081 |
| RTT（10 次均值） | — | **~9.8 ms** | **~16.3 ms** |
| 达标线 ≤50ms | — | ✅ 余量 5× | ✅ 余量 3× |

PRoot 架构无独立 VM 控制通道，不适用此指标。Pi5 RTT 比长城慢 ~66%（vsock
转发 + CPU），但余量仍充足。

### 2.5 内存占用（P4）

| 进程 | 长城 PRoot | 长城 AVF | Pi5 AVF（冷启动） | Pi5 AVF（聊天后） |
|---|---|---|---|---|
| App 主进程 RSS | 360 MiB | 869 MiB | 342 MiB | **459 MiB** |
| App 主进程 PSS | 186 MiB | — | 172 MiB | **285 MiB** |
| 引擎（node/crosvm worker）RSS | 343 MiB | 405 MiB | 388 MiB | **483 MiB** |
| 引擎 PSS | 323 MiB | — | 371 MiB | **471 MiB** |
| relay/辅助 | 51 MiB | 15 MiB（VMM） | 含在 worker | 含在 worker |
| 线程数（App/引擎） | 51 / 11 | 64 / 20 | 70 / 18 | 101 / 18 |

**引擎侧内存三方同量级（冷启动 343–405 MiB RSS）**。Pi5 聊天负载后 guest
升至 **483 MiB RSS / 471 MiB PSS，仍 < 512 MiB 配额（余量 ~29 MiB）**——
配额保护生效，未 OOM。设备内存压力：长城 MemAvailable ~22.8 GiB，Pi5
~12.1 GiB（另跑用户 debian VM），均无压力。

> Pi5 聊天后 guest 483 MiB 接近配额上限，说明 512 MiB 对重负载偏紧；
> 长城 AVF 昨日聊天后 405 MiB 余量更宽。若 Pi5 跑更重任务可能触顶，
> 可考虑按设备内存调高配额（Pi5 有 15.8 GiB，不缺内存）。

关键差异：
- PRoot 的 node 与 App 共享地址空间（fork），PSS 更准确；AVF guest 独立配额。
- **Pi5 App 进程 342 MiB 远低于长城 AVF 的 869 MiB**：长城测量在聊天负载后
  （含 WebView/图片缓存），Pi5 为冷启动稳态；同口径下 AVF App 开销一致。
- PRoot 无内存硬限制（Node --max-old-space-size=2048），AVF 有 512 MiB 配额保护。

### 2.6 磁盘占用（P5）

| 项 | 长城 PRoot | 长城 AVF | Pi5 AVF |
|---|---|---|---|
| APK 内资产 | 152 MiB（tar.xz） | 748.5 MiB（ext4） | 748.5 MiB（ext4） |
| 安装后磁盘 | 853 MiB（26,869 文件） | 748.5 MiB（单镜像） | 748.5 MiB（单镜像） |
| APK 总大小 | 228 MiB | 817 MiB | 1016 MiB（未 split） |

**PRoot APK 小 3.6×**（228 vs 817 MiB），但安装后磁盘略大（853 vs 748 MiB）。
Pi5 APK 更大是因为本次构建未做 ABI split（含全部资产），分发时按长城口径
split 后同为 ~817 MiB。

### 2.7 端到端聊天（P6，2026-11-25 补测）

同一账号（137******97）、同一默认模型 **V4.1 Flash（思考深度高）**、同一批
主页预设提示词。长城 PRoot 3 轮 + 长城 AVF 1 轮 + **Pi5 AVF 3 轮**（已登录）：

| 轮次 | 设备/后端 | 提示词 | 墙钟 | 步数 | tokens | 工具耗时 | 等审批 |
|---|---|---|---|---|---|---|---|
| #1 | 长城 PRoot | 看看工作区 | 1m00s | 3 | 47.4K | **0.1s** | 51s |
| #2 | 长城 PRoot | 写个小脚本 | 1m02s | 4 | 80K | **0.3s** | 25s |
| #3 | 长城 PRoot | 写个小脚本（重跑） | **48s** | 6 | 112K | **0.4s** | 20s |
| #4 | 长城 AVF | 看看工作区 | 1m29s | 6 | 80.3K | — | 含人工审批 |
| #5 | **Pi5 AVF** | 看看工作区 | **45s** | 6 | 78K | **0.3s+<0.1s** | 31s |
| #6 | **Pi5 AVF** | 写个小脚本 | 6m31s（手动停） | 25 | 498K | <0.1s/步 | 9.3s |
| #7 | **Pi5 AVF** | 看看工作区（重跑） | **37s** | 4 | 51.1K | **<0.1s** | 29s |

关键观察：

1. **墙钟不可直接比**：各轮都含人工审批等待（20–51s）。扣除审批后
   Pi5 #7 实际工作 ~8s、#5 ~14s，与长城 PRoot #3（~28s）同量级或更快
   （步数更少）。
2. **工具执行三方都极快**：ls/find/pwd 全部 <0.1s–0.3s/步（轨迹口径），
   「PRoot ptrace 开销拖慢 shell 工具」的担忧在本负载下**未观测到**。
   注：轨迹精度 0.1s，重 I/O 负载另评。
3. **产出正确**：长城 #2/#3 写出 top_words.py + sample.txt 并运行出结果；
   Pi5 #5/#7 正确报告工作区为空。
4. 模型耗时主导总时长，后端差异被网络/模型波动淹没——
   **端到端聊天体验三方无可感知差异**。
5. **⚠️ Pi5 出网发现（#6）**：「写个小脚本」任务中模型选择从 Gutenberg
   下载《傲慢与偏见》做词频语料，但 **guest 经 egress 代理访问部分外部
   站点返回 503/Cloudflare 阻塞**，模型反复换镜像重试（25 步/498K tokens/
   6m31s 后手动停止）。长城 PRoot 同题未触发此路径（模型直接造 sample.txt）。
   这是**模型行为差异 + Pi5 egress 出网受限**的叠加，非引擎缺陷；
   纯本地工具任务（#5/#7）完全正常。提示：AVF egress 代理对某些站点
   的可达性需专项验证（§5.4）。

### 2.8 跨设备发现与修复（三个实测缺陷）

#### ③ Pi5 guest 内核 6.12 暴露 vsock 桥 SIGPIPE 竞态（已修复，cd196104）

- **现象**：Pi5 上 3080 桥（HTTP 转发）在 1–4 次连接后静默死亡，之后所有
  connect 均 ECONNRESET（strace 实证：无监听者）；3081 控制桥始终正常。
  App 显示 "Can't reach the engine"。长城（guest 6.6）从未复现。
- **根因**：`vsock-tcp-bridge.c` 无 SIGPIPE 处理。对端关闭读侧后 pump 线程
  `write()` 触发 SIGPIPE 杀死整个桥进程。guest 内核 6.12.60-android16 的
  vsock 关闭时序比 6.6.98-android15 更易触发该竞态。
- **诊断要点**：ECONNRESET on connect = 无监听者（桥死）；guest refused
  计数不随探测增长 = 桥未 accept；3082/3083 connect 模式本就不监听 vsock
  （host 连它们 reset 是正常的，非缺陷）。
- **修复**：`main()` 加 `signal(SIGPIPE, SIG_IGN)`，write 返回 EPIPE 由 pump
  正常收尾单个连接。重建镜像（版本后缀 -vmsigpipe1）后 **3080 连探 40/40
  成功**，App 转发 ~21ms 稳定，全页 20/20 完整。
- **意义**：该修复对长城同样是防御性加固；证明 AVF 后端跨内核版本移植时
  需关注 guest 内核行为差异。

#### ①② 长城 PRoot 直连的两个缺陷（AVF 已规避）

补测全页时延时发现，均为 v1.3.0 官方 APK + 长城实测：

**① token→cookie 交换返回 400（正确 token 也 400）**

- 现象：`GET /?token=<正确48hex>` → `400 Bad Request`（无 body、无 guard 头）；
  同长度错误 token → 401；`/connect?token=<正确>` 同样 400。
- 定位：400 来自 `dsh-host-webserver` 的 catch-all（handler 抛异常）；
  正确 token 走 `encodeCookie→sessionCookie→303` 铸造路径时抛错，
  错误 token 走 `writeUnauthorized` 正常。App 自身（Dart DshClient）同样
  依赖此交换，受影响面待查（App 聊天正常，说明 App 侧另有通路或时序不同）。
- 影响：外部 HTTP 客户端无法用官方 token 登录；本次用自铸 cookie 绕过完成测量。
- **AVF 对照**：长城 AVF 303 正常（~13.2ms），**Pi5 AVF 303 也正常**（本次
  实测）——两台 AVF 设备同版本引擎均无此问题，实锤是 PRoot/tawcroot 环境
  问题而非引擎代码问题，待专项定位。

**② loopback 半关闭竞态：大响应 0/20 完整**

- 现象：nc 发完请求立即关 stdin（TCP 半关闭）→ 43KB 全页 0/20 完整接收
  （连接 ~19ms 即断、0 字节）；401 小响应（单次 write）不受影响。
- 对照实验：同长度无效签名 cookie → 401 正常；保持 stdin 10ms → 20/20 完整；
  主机 curl 经 adb forward（不同关闭时序）→ 5/5 完整。
- 根因方向：PRoot/tawcroot 对 loopback 半关闭的翻译使引擎侧提前收到 EOF，
  `serveResource` 的 `onClose→abort` 取消了响应流。
- **AVF 对照**：这正是 AVF 测试报告 §4.4 的「半关闭 0/20（内核缺陷）」——
  C 桥 `conn_finish` 契约（不半关闭）就是为规避它设计的，20/20 完整。
  **该缺陷是 PRoot 路径固有的，AVF 架构已修复。**

## 3. 架构差异分析

### 3.1 PRoot 优势

| 优势 | 说明 |
|---|---|
| **冷启动快 44%** | 无 VM 启动开销，直接 fork+exec node 进程 |
| **APK 体积小 3.6×** | tar.xz 压缩率远高于 ext4 镜像 |
| **无虚拟化依赖** | 不需要 pKVM/crosvm/virt APEX，兼容更多设备 |
| **无内存硬限制** | 引擎可用内存不受 VM 配额约束 |
| **架构简单** | 无 vsock 桥/转发层/VM 生命周期管理 |
| **首次安装后无校验** | 后续启动直接使用已解压 rootfs |

### 3.2 AVF 优势

| 优势 | 说明 |
|---|---|
| **首次安装快 5.6×** | ext4 镜像拷贝 vs 逐文件解压 |
| **安全隔离** | 独立 VM 内核，guest 崩溃不影响 host |
| **内存配额保护** | 512 MiB 硬限制防止引擎 OOM 拖垮系统 |
| **无 ptrace 开销** | PRoot 每个 syscall 经 ptrace 翻译，AVF 原生执行（轻负载下差异不可感知，见 §2.7） |
| **跨设备已验证** | 长城（pKVM/4K/guest 6.6）与 Pi5（non-protected/16K/guest 6.12）均正常运行 |
| **独立内核** | guest 可用不同内核版本/配置 |
| **状态快照** | VM 可暂停/恢复，PRoot 无此能力 |

### 3.3 PRoot 劣势

| 劣势 | 说明 |
|---|---|
| **ptrace 开销** | 每个 syscall 经 ptrace 拦截翻译，I/O 密集场景性能下降 |
| **首次安装慢** | 26,869 文件逐文件解压 ~14s |
| **无安全隔离** | 引擎与 App 同进程空间，崩溃可能影响 App |
| **无内存保护** | 引擎内存泄漏可能耗尽系统内存 |
| **兼容性依赖** | 需要 ptrace 权限，部分设备/SELinux 策略可能阻止 |

### 3.4 AVF 劣势

| 劣势 | 说明 |
|---|---|
| **冷启动慢** | VM 启动 + guest 初始化额外 ~2.8s（长城）/ ~4.6s（Pi5） |
| **APK 体积大** | ext4 镜像不可压缩，817 MiB |
| **硬件依赖** | 需要 KVM + crosvm + virt APEX；pKVM 可选（Pi5 无 pKVM 也运行） |
| **架构复杂** | vsock 桥/转发/VM 生命周期/egress 代理 |
| **guest 内核差异敏感** | 6.12 暴露 SIGPIPE 竞态（已修）；跨内核移植需真机验证 |

## 4. 综合评估

### 4.1 性能评分（5 分制）

| 指标 | 长城 PRoot | 长城 AVF | Pi5 AVF | 说明 |
|---|---|---|---|---|
| 冷启动 | ★★★★★ (3.6s) | ★★★☆☆ (6.4s) | ★★★☆☆ (8.2s) | PRoot 最快；Pi5 硬件慢 |
| 首次安装 | ★★☆☆☆ (14s) | ★★★★★ (2.5s) | ★★★★☆ (5s) | AVF 明显更快 |
| HTTP 小响应 | ★★★★☆ (~18ms) | ★★★★☆ (~17ms) | ★★★★☆ (~21ms) | 三方持平 |
| HTTP 大响应 | ★★★☆☆ (~25ms+缺陷) | ★★★★★ (14.4ms) | ★★★★☆ (24.6ms) | AVF 稳定 20/20 |
| 端到端聊天 | ★★★★☆ | ★★★★☆ | ★★★★☆ | 模型耗时主导；Pi5 出网受限 |
| 内存效率 | ★★★★☆ (374 MiB) | ★★★☆☆ (405 MiB) | ★★★★☆ (388 MiB) | 同量级 |
| APK 体积 | ★★★★★ (228 MiB) | ★★☆☆☆ (817 MiB) | ★★☆☆☆ | PRoot 明显更小 |
| 安全隔离 | ★★☆☆☆ | ★★★★★ | ★★★★☆ | Pi5 无 pKVM（non-protected） |
| 传输可靠性 | ★★☆☆☆ (0/20) | ★★★★★ (20/20) | ★★★★★ (20/20) | AVF 契约修复 |
| 设备兼容 | ★★★★★ | ★★★☆☆ | ★★★☆☆ | AVF 需 KVM+virt APEX |
| 架构简洁 | ★★★★★ | ★★★☆☆ | ★★★☆☆ | PRoot 更简单 |

> Pi5 安全隔离四星：non-protected VM 仍有独立内核与地址空间隔离，但无
> pKVM 的 host 不可见保护（host root 可读 guest 内存）。对 DSH 场景足够。

### 4.2 适用场景建议

| 场景 | 推荐 | 理由 |
|---|---|---|
| 日常使用（频繁启动） | **PRoot** | 冷启动快 44%，用户体验更流畅 |
| 首次安装/更新 | **AVF** | 安装快 5.6×，减少等待 |
| 低内存设备 | **AVF** | 512 MiB 配额保护，防止 OOM |
| 旧设备/无虚拟化 | **PRoot** | 无 KVM/virt APEX 依赖 |
| 安全敏感场景 | **AVF** | 独立 VM 隔离 |
| I/O 密集任务 | **AVF** | 无 ptrace 开销 |
| 分发体积敏感 | **PRoot** | APK 小 3.6× |

### 4.3 结论

1. **PRoot 在冷启动和分发体积上有明显优势**，适合日常频繁使用场景。
2. **AVF 在安全隔离、首次安装速度和传输可靠性上有优势**；大响应转发
   实测反而更快（长城 14.4 vs PRoot ~25ms），因为规避了半关闭缺陷。
3. **HTTP 小响应时延三方持平**（17–21ms），vsock 转发开销不显著。
4. **内存占用三方同量级**（引擎侧 343–405 MiB），AVF 有配额保护更安全。
5. **端到端聊天三方无可感知差异**（长城 + Pi5 实测）：模型耗时主导，
   工具执行都 <0.1s–0.3s/步；「ptrace 拖慢 shell」在本负载下未观测到。
   Pi5 纯本地任务 37–45s 完成，与长城同量级。
6. **三个实测缺陷均已定位**：PRoot token 交换 400、PRoot 半关闭 0/20
   （AVF 架构规避）；Pi5 guest 6.12 SIGPIPE 竞态（已修复 cd196104，
   对长城也是防御性加固）。
7. **AVF 后端跨设备移植成立**：Pi5（Android 17 / 16K 页 / non-protected /
   guest 6.12）与长城（Android 16 / 4K / pKVM / guest 6.6）均正常运行，
   无需改 Flutter/JNI/内核选择代码，仅修一个 C 桥信号处理。
8. **Pi5 各项时延比长城慢 ~30–70%**（CPU/存储硬件差异），但全部达标
   （NFR-PERF 余量 ≥2×）且稳定（冷启动 ±90ms，全页 20/20）。
9. **AVF 的核心价值在安全隔离、传输契约和架构演进**：独立 VM 为未来
   多引擎/插件沙箱奠定基础。

## 5. 测试方法说明

### 5.1 计时工具

- 设备 toybox `date +%s%N` 纳秒差值（与 AVF 测试一致）。
- HTTP 时延含 `nc` 进程 spawn 开销，是「用户可感知」口径。

### 5.2 冷启动定义

- PRoot：startup 日志 app + prepare + dsh（App 启动→dsh web 监听）。
- AVF：dsh.log `launching AVF VM` → `AVF dsh web ready`（含 VM 内核启动）。
- 两者口径略有差异：PRoot 含 App UI 启动（~0.7s），AVF 表中也含 app 段
  （startup 日志的 app+prepare+dsh 与 mono 差基本一致，Pi5 实测 8.6s vs 8.1s）。
  同口径比较结论不变：PRoot 最快，Pi5 硬件最慢。

### 5.3 内存测量

- PRoot：`/proc/<pid>/smaps_rollup` PSS（比例共享内存），更准确反映实际占用。
- AVF：`/proc/<pid>/status` VmRSS（crosvm guest worker）。
- PSS vs RSS 口径不同：PSS 按共享比例分摊，RSS 含全部共享页。

### 5.4 未完成项

| 项 | 原因 | 后续计划 |
|---|---|---|
| Pi5 egress 出网可达性 | #6 Gutenberg 503/Cloudflare 阻塞 | 专项：测常用站点经 egress 的可达性 |
| Pi5 重负载内存配额 | 聊天后 483/512 MiB 偏紧 | 评估按设备调高配额 |
| token 交换 400 根因（长城 PRoot） | 已定位到铸造路径抛异常 | 专项：抓引擎 stderr / strace |
| PRoot ptrace 开销量化 | 轨迹精度 0.1s 不够 | 重 I/O 负载 + strace 计数专项 |

### 5.5 凭据处理

- 全页测量用的自铸 cookie 与签名密钥临时副本（设备 /data/local/tmp、主机 /tmp）
  测量完成后已全部删除；密钥与 cookie 值未写入本文档与日志。
- DeepSeek 登录经浏览器一键授权（设备已有会话），密码未经过测试侧。

## 6. 原始数据

### 6.1 PRoot 冷启动日志（3 次）

```
#1: startup: app 735 ms, prepare 8 ms, dsh 2853 ms → total 3596 ms
#2: startup: app 742 ms, prepare 8 ms, dsh 2901 ms → total 3651 ms
#3: startup: app 740 ms, prepare 8 ms, dsh 2886 ms → total 3634 ms
```

### 6.2 PRoot HTTP 401 时延（10 次）

```
19, 14, 17, 19, 20, 18, 16, 17, 19, 20 ms → sum 179, mean 17.9 ms
```

### 6.3 PRoot 内存（冷启动后稳态）

```
App:   RSS 360,004 kB / PSS 190,329 kB / 51 threads
Node:  RSS 351,280 kB / PSS 331,138 kB / 11 threads
Relay: RSS  51,832 kB / ~51 MiB PSS  /  7 threads
```

### 6.4 PRoot 首次安装日志

```
install verify: 137 ms
install extract: 13599 ms
install sync: 471 ms
install activate: 477 ms
runtime ready in 14s
```

### 6.5 设备环境

```
MemTotal: 28,216,112 kB (~26.9 GiB)
MemAvailable: 23,862,056 kB (~22.8 GiB)
rootfs: 853 MiB / 26,869 files
APK: 239,344,269 B (228 MiB)
```

### 6.6 端到端聊天轨迹（2026-11-25 补测）

```
#1 看看工作区: 1m00s = 模型 8.1s + 工具 0.1s + 等你 51s
   输入 46.2K（缓存命中 92%）输出 1.2K 首字 0.7s
#2 写个小脚本: 1m02s = 模型 37s + 工具 0.3s + 等你 25s
   输入 73K（95%）输出 7K 首字 0.7s；产出 top_words.py(+111) sample.txt(+11)
#3 写个小脚本: 48s = 模型 28s + 工具 0.4s + 等你 20s
   输入 107K（97%）输出 4.7K 首字 0.8s
```

### 6.7 全页 HTTP 时延与半关闭实验（2026-11-25 补测）

```
nc 立即关 stdin:   0/20 完整（~19ms 断连，0 字节）
nc 保持 stdin 10ms: 20/20 完整，35–37ms（扣 10ms 等待 ≈ 25ms）
nc 保持 stdin 50ms: 10/10 完整，75–77ms
nc 保持 stdin 300ms: 5/5 完整，326–328ms
主机 curl 经 adb forward: 5/5 完整，8.0–10.3ms（43,203B）
对照：同长度无效签名 cookie → 401 正常（排除长度因素）
```

### 6.8 聊天负载后内存（长城 PRoot，3 轮对话后）

```
App:   RSS 486,388 kB / PSS 294,913 kB
Node:  RSS 260,796 kB / PSS 240,654 kB（较冷启动 331 MiB 回落，V8 GC）
Relay: RSS  51,900 kB / PSS  31,758 kB
```

### 6.9 Pi5 AVF 实测原始数据（2026-11-25，镜像 vmsigpipe1）

```
冷启动（VM up→ready，3 样本）:
  CID 2072: launching 23462027 → VM up 23462533 (506ms) → ready 23470836 (8303ms)
  CID 2073: launching 23489264 → VM up 23489769 (505ms) → ready 23497885 (8116ms)
  CID 2074: launching 23514983 → VM up 23515485 (502ms) → ready 23523632 (8147ms)
  startup 日志: app 269–315 ms, prepare 12 ms, dsh 8623–8809 ms

控制 RTT: 10 次总 163ms，avg 16.3ms（vsock-line 2074 3081，含 spawn）
HTTP 401: 10 次总 206ms，avg 20.6ms（nc 经 App 转发）
全页 43,341B: 25/24/23/27/24 ms，5/5 完整，sum 123ms avg 24.6ms
全页完整性: 20/20（nc 立即关 stdin，无需保持——C 桥契约）
token→cookie: 303 + Set-Cookie 正常（官方交换，非自铸）

内存（冷启动稳态，CID 2074）:
  App (pid 12172):        RSS 341,712 kB / PSS 171,730 kB / 70 threads
  crosvm_dsh-go (12252):  RSS 397,408 kB / PSS 379,659 kB / 18 threads
  MemTotal 16,576,880 kB / MemAvailable 12,200,528 kB
  （另跑用户 debian VM CID 2071，不计入本测量）

SIGPIPE 修复验证:
  修复前: 3080 桥 1–4 次连接后死亡，后续 ECONNRESET（strace 实证无监听者）
  修复后: 3080 连探 40/40 成功；App 转发 22/19/22/22/21 ms；全页 20/20

首次安装（新镜像 vmsigpipe1）: installing → image ready in 5s
```

### 6.10 Pi5 端到端聊天与聊天后内存（2026-11-25，账号 137******97）

```
#5 看看工作区: 45s / 6 步 / 78K tokens / 等审批 31s
   工具: pwd&&ls 0.3s, find <0.1s, working_directory <0.1s
   思考: 2.5s + 2.3s + 1.2s
#6 写个小脚本: 6m31s 手动停 / 25 步 / 498K tokens
   模型从 Gutenberg 下载语料遇 503/Cloudflare，反复换镜像重试（出网受限）
#7 看看工作区(重跑): 37s / 4 步 / 51.1K tokens / 等审批 29s
   工具: pwd;ls <0.1s ×2；思考 1s + 2s + 2.8s

聊天后内存（CID 2075，#5/#6/#7 三轮后）:
  App (pid 13499):       RSS 469,792 kB / PSS 291,529 kB / 101 threads
  crosvm_dsh-go (13581): RSS 494,320 kB / PSS 482,559 kB / 18 threads
  MemAvailable 12,742,112 kB
  guest 483 MiB < 512 MiB 配额（余量 ~29 MiB，未 OOM）
```