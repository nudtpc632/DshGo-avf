# AVF 虚拟机后端（前后端分离）设计

状态：阶段 0/1 已在真机完成（GreatWall pd2508），阶段 2（App 集成）进行中。
目标：把 DSH 引擎后端从 PRoot Ubuntu 迁到 AVF
（Android Virtualization Framework）虚拟机内的极简定制 Linux；前端 APK 保留在
安卓系统上，继续持有手机操控与 Android CLI 能力。本文是分阶段计划与契约草案，
实现前各阶段需在真机上验证（AGENTS.md 硬规则）。

## 1. 目标与非目标

目标：

- 后端（`dsh web` + dsh-go-bridge）跑在 AVF VM 里，使用裁剪版 Ubuntu rootfs：
  只保留运行必需组件，非必要一律不进镜像。
- 前端 APK 打开/管理 VM；VM 就绪后 App 即可用，聊天/文件/终端/插件体验不回退。
- 前端保留对安卓系统的 CLI 操作能力（`android_shell`、无障碍/Shizuku/ADB 手机
  操控），且这些能力不依赖引擎在哪。
- 对 Dart/UI 层尽量零改动：AVF VM 在 App 看来仍是「本机引擎」。

非目标（本期不做）：

- 不引入新的远程主机类型（`HostKind` 不变）；VM 不是「远程主机」。
- 不做 protected VM 的机密计算承诺；先以 non-protected VM 跑通。
- 不改 DSH 引擎补丁与 bridge 的 HTTP 协议本身。
- iOS/OHOS 不受影响（各自宿主实现不变）。

## 2. 现状事实（代码为准）

- 前后端已是 HTTP 分离：`packages/dsh_client` 的 `DshClient.connect(baseUri,
  token)` 不关心对端位置；SSH 主机模式已验证引擎不在 PRoot 时聊天/文件/终端/
  插件可用。
- 本机生命周期契约：Kotlin `RuntimeController` 经 `dsh/runtime`
  MethodChannel + `dsh/runtime/events` EventChannel 上报
  phase/baseUrl/token；Dart `runtime/runtime_state.dart` 只镜像。
  `localRuntimeProvider` 被约 54 个文件引用。
- 手机操控回路：引擎 `mcp__phone__*` → guest 内 `relay.cjs`（读
  `/var/lib/dsh-app/phone/bridge.json`）→ abstract unix socket（PRoot 下回落
  127.0.0.1 TCP）→ `PhoneBridge.kt` → `McpServer.kt` → 无障碍/Shizuku/ADB。
  SafetyGate 在 App（Kotlin）端。`phone_state.dart` 对非本机主机返回
  `PhoneBlocker.remoteHost`。
- Android CLI：`PhoneAndroidShell.kt`（Shizuku UserService / 自连无线 ADB /
  Sui / root 后端）独立于引擎，见 `docs/design/android-shell.md`。
- rootfs 配方：`runtime/build-runtime.sh`（Ubuntu Base 24.04.5 + Node 24.22.1
  + DSH 0.2.2-rc.1 + bridge + pnpm + Python PBS + tzdata/ca-certs/git），
  约 150 MB xz / 750 MB 解压；PRoot/talloc/shmem 以 jniLibs 提供，不在 rootfs。
- 仓库内无任何 AVF/vsock/crosvm 既有代码。

## 3. 目标架构

```text
┌────────────── 前端 APK（安卓系统）──────────────┐
│ Flutter UI + Riverpod（基本不动）               │
│ Kotlin:                                        │
│   AvfRuntimeController（新增，实现 dsh/runtime  │
│     契约：phase/baseUrl/token/events）          │
│   LoopbackForwarder 127.0.0.1:3080 → VM:3080   │
│   PhoneBridge（传输改造，SafetyGate 不动）      │
│   PhoneAndroidShell / 手机操控（原样保留）      │
└───────────────┬────────────────────────────────┘
                │ VirtualizationService（创建/启动/停止）
                │ HTTP/SSE 经 loopback 转发；phone 回路经 vsock/转发 TCP
┌───────────────▼────────────────────────────────┐
│ AVF VM（crosvm，non-protected 起步）            │
│   定制内核 + 极简 init                          │
│   裁剪 Ubuntu rootfs：glibc 基础 + ca-certs/    │
│     tzdata + Node + DSH(补丁) + dsh-go-bridge   │
│   dsh web --host 127.0.0.1 --port 3080          │
│   vsock↔TCP 桥 3080/3081 + vm-ctl 控制通道      │
│   C 桥 connect 模式 13082(egress)/13083(phone)  │
└─────────────────────────────────────────────────┘
```

关键决策：**保留 `dsh/runtime` 通道契约，替换 Kotlin 实现**。这样
`runtime_state.dart`、连接装配（`connection_state.dart` 的 local 分支）、
安全模式 UI、插件「本机」判定等全部不动；AVF VM 保持「本机」语义，
`phone_state.dart` 的 remoteHost 门禁天然不触发。

## 4. AVF 前提与设备门槛（阶段 0 验证）

- API：`android.system.virtualmachine.VirtualMachineManager`（公开 API 34+，
  Android 15 起普通应用可用 non-protected VM；更低版本或 custom VM 需要系统
  签名/`MANAGE_VIRTUAL_MACHINE` 等特权）。
- 内核：pKVM 或至少 crosvm 可用的 KVM；检查 `/dev/kvm`、
  `getprop ro.boot.hypervisor.protected_vm.supported`、
  `getprop ro.boot.hypervisor.vm.supported`、
  `pm list features | grep virtualization`
  （`android.software.virtualization_framework`）。
- 自定义 VM（非 Microdroid）需要 `USE_CUSTOM_VIRTUAL_MACHINE`（签名级）或
  设备厂商放行；这是本方案最大的平台门槛，阶段 0 必须在目标设备（定制安卓
  系统）上实测确认，不能以模拟器结果代替。
- 回退策略：VM 不可用（feature 缺失/创建失败）时回退现有 PRoot 路径；
  回退判定单点放在 Kotlin 运行时选择处，Dart 不感知。

阶段 0 交付物：目标设备 AVF 可用性报告（feature/权限/内核/自定义 VM 是否放行、
App↔VM 网络连通性实测）。不通过则本设计止步，不进入阶段 1。

## 5. 极简 VM 镜像（阶段 1）

复用 `runtime/build-runtime.sh` 的钉死输入与补丁机制，新增 VM 镜像产出目标
（不改动现有 PRoot 产物路径）：

保留（必需）：

- Ubuntu Base 的 glibc 与最小核心工具（可进一步用 dpkg 裁剪清单）；
- ca-certificates、tzdata；
- Node 24.22.1（DSH 是 Node 程序）；
- DSH 0.2.2-rc.1 + `runtime/patches`（pins.json 同步）+ dsh-go-bridge；
- phone relay（`relay.cjs`，传输改造后版本）。

裁掉（非必要不进镜像）：

- PRoot/talloc/android-shmem jniLibs 与 Direct/tawcroot 启动分支（真内核不需要）；
- Python PBS（约数十 MB；技能需要时再按开关加回）；
- git、pnpm（首期不做 VM 内插件安装；插件市场后续经 bridge API 下发，见 §8）；
- 文档/locale/man 等常规裁剪，沿用 `PRUNE_DEV_METADATA`。

镜像组成（阶段 1 实测修正）：guest 内核用设备自带 microdroid_kernel
（virtio_blk/ext4/vsock 内建；**无 virtio_net，TAP 路线不可行**）+
**只读 rootfs 单分区** + 极简 init（直接拉起 `dsh web`，崩溃由 init 监管循环
重启）。**可写数据分区不可行**：麒麟 virtmgr 打开磁盘 fd 恒为 O_RDONLY
（/proc/<crosvm>/fd 证实），guest 写盘必 EBADF → 持久化改走 vsock 控制通道
save/restore（§6）。

阶段 1 已交付（`runtime/build-vm-image.sh` + `runtime/vm/`）：

- 复用 build-runtime.sh 钉死输入与补丁；额外 deb 仅
  tzdata/ca-certificates/busybox-static；`--prune-dev-metadata` 裁剪。
- **root= 直启，免 initramfs**：params
  `earlycon=uart8250,mmio,0x3f8 console=hvc0 root=/dev/vda1 ro rootwait init=/init`；
  virtmgr 给裸 ext4 包 GPT，rootfs 在 vda1；必须显式 `init=/init`。
- guest init（busybox sh，shebang `#!/usr/bin/busybox sh`——busybox-static
  deb 只有 /usr/bin/busybox）：mount proc/sys → **tmpfs /dev**（devtmpfs 在
  此内核不可用）+ mknod null/zero/urandom/console/tty → tmpfs /tmp、/dev/shm、
  /root/.dsh(0700，凭据只进内存) → lo up → vsock↔TCP 桥
  （3080→3080、3081→13081）+ vm-ctl + guest 出网代理（13082，见 §6）
  + phone C 桥（13083→vsock 3083，见 §7）+ 监管循环跑
  `dsh web --host 127.0.0.1 --port 3080 --no-open`（叠加 phone overlay）。
- 镜像制作：`fakeroot tar 解包 + mkfs.ext4 -d`（无需 root/loop）；
  实测 748 MiB ext4（未压缩内容约 626 MB，21155 文件）。压缩体积以构建报告
  为准；「≤80 MB」原目标对含完整 Node+DSH 的镜像不现实，后续裁剪另行评估。

构建在 x86_64 主机交叉产出 arm64 镜像，沿用「不执行外架构代码」的现有约束。

## 6. 网络、token 与生命周期（阶段 2）

阶段 0 真机实测结论（GreatWall pd2508，Android 16 / 麒麟 6.6 内核）：

- **AVF tap 网络在 custom VM 路径不可用**：`vm run --network-supported`
  不产生 crosvm `--net`/TAP（virtmgr 静默忽略；vmnic AIDL 服务手动启动也
  不改变）。guest 无 eth0。
- **vsock 端到端已验证**：crosvm 持 `/dev/vhost-vsock`，guest 内核
  PF_VSOCK 内建；主机 `AF_VSOCK` socket 直接 `connect(cid, port)` 即可，
  **不需要打开 `/dev/vsock`**（strace 证实；该设备 0600 root 但 shell
  uid=2000 无 capability 也能连通，麒麟内核放宽）。HTTP over vsock 已用
  静态 C 代理验证：guest vsock-httpd :3080 ↔ 主机 127.0.0.1:3080 代理，
  连续请求 200 OK。
- guest 内核（microdroid_kernel 6.6.98-android15-8）**不支持 gzip
  initramfs，必须未压缩 cpio**；earlycon 必须
  `earlycon=uart8250,mmio,0x3f8 console=hvc0`。最终方案 root= 直启，
  不再需要 initramfs。
- **可写 virtio-blk 不可用**：virtmgr 打开磁盘 fd 恒 O_RDONLY，guest 写
  superblock 即 EBADF。持久化只能走 vsock 控制通道。
- **vsock 半关闭缺陷（真机定论）**：客户端发完请求后 `shutdown(SHUT_WR)`
  会触发 guest 内核 vsock 丢在途数据——小响应竞态（时好时坏），大响应
  （如登录后 43 KB HTML）几乎必丢。guest 桥已改为正确的半关闭转发
  （EOF 只 shutdown 对端写方向），小响应恢复可靠，但大响应仍受内核缺陷
  影响。**传输契约：App 侧 vsock 客户端发完请求后不得 shutdown(SHUT_WR)，
  直接读响应到对端关闭**（HTTP/1.0 服务端与 vm-ctl 都在响应后自关，
  无需半关闭）。无半关闭客户端实测全通道 20/20。契约已写入
  vm-manifest.json 的 `vsock.transportContract`。

据此网络方案定为 **vsock 优先**：

- App 侧 `LoopbackForwarder` 监听 127.0.0.1:3080，每条连接经
  `AF_VSOCK connect(cid, 3080)` 转发到 VM；`baseUrl` 对 Dart 仍是
  loopback → `DshClient` 与连接装配零改动。VM 内 `dsh web` 监听 vsock
  端口 3080（经 guest 内 vsock↔TCP 桥接，或 bridge 直接支持 vsock）。
- cid 由 VirtualizationService 分配（`vm list` 可见），App 从 VM 句柄获取。
- token：launch token 每次进程启动都变。VM 内 vm-ctl 从 `/tmp/dsh-web.log`
  抓取 token，App 经 vsock 控制通道（端口 3081，guest TCP 127.0.0.1:13081）
  主动查询；控制通道协议为每连接一行 JSON 请求/一行 JSON 响应：
  - `{"cmd":"status"}` → `{ok, ready, token, baseUrl}`；
  - `{"cmd":"save"}` → `{ok, files:{relPath:base64}}`（DSH_HOME 常规文件）；
  - `{"cmd":"restore","files":{…}}` → `{ok, restored:N}`（拒绝对路径与
    `..`，总量 ≤64 MB，落盘 mode 0600；另接受白名单键
    `@phone/bridge.json` → tmpfs /var/lib/dsh-app/phone，仅 restore、不进 save）。
  save/restore 已真机实测（8 文件往返，restore 后登录态与二次 save 一致）。
  凭据在 guest 只存 tmpfs（/root/.dsh），VM 停止即消失，主机侧持久化由
  App 负责；控制通道不落盘持久化 token。
- 登录契约（真机实测）：launch token 只在 `GET /?token=…` 且
  `Host: 127.0.0.1:3080` 时有效 → 303 + Set-Cookie（dsh-auth-…，30 天）；
  cookie 绑定 authority，VM 重启后 token/cookie 均失效，App 需重新
  status + login。转发层必须保持 Host 头为 127.0.0.1:3080。
- 生命周期映射（AvfRuntimeController）：
  - notInstalled/installed：VM 镜像是否已就位（APK assets 或下载）；
  - installing：镜像导入/校验；
  - starting：VM 启动 + guest init 就绪 + token 上报；
  - running：HTTP 探活通过（沿用现有 3080 探测语义）；
  - stopping/error：VM 停止/启动失败；失败计数映射现有安全模式 strikes/LKG
    语义（VM 起不来 ≠ proot 崩溃，但 UI 契约不变）。
- 前台服务 `DshService` 持有 VM 生命周期，App 退后台策略与现有引擎一致。
- **guest 出网（egress）**：custom VM 无 virtio-net，guest 默认无外网，
  LLM API 不可达。方案：guest 内 C 桥 connect 模式
  （`vsock-tcp-bridge -c 13082 3082`）监听 127.0.0.1:13082，init 导出
  `HTTP(S)_PROXY=http://127.0.0.1:13082` + `NODE_USE_ENV_PROXY=1`
  （dsh-http-proxy 的 installProxyFromEnvironment 生效）；桥把 CONNECT /
  absolute-form 请求经 vsock 3082 隧道到 App 侧 `EgressProxy`
  （`Vsock.listen(3082)` accept），由 App 用自身网络栈 dial 真实目标
  （TLS 端到端不变，App 只转字节）。loopback 目标在 guest 内直连，不出 VM。
  **Node 24 无 AF_VSOCK**（`net.connect({family:42})` 报
  ERR_INVALID_ARG_VALUE，真机实证），guest→host 一律走 C 桥；
  host 侧 accept 由 JNI `listenNative/acceptNative` 提供。
- **App 集成权限**：userdebug 设备 `pm grant` 授予
  `MANAGE_VIRTUAL_MACHINE` + `USE_CUSTOM_VIRTUAL_MACHINE`（signature 级，
  adb 可授）；量产设备需厂商放行（§10）。每次 `install -r` 后需重授。
- 开发期绕过：aconfig custom_vm flags disabled，普通 APK API 被挡；阶段 2
  前用 shell `vm run` + 静态代理验证，App 集成需麒麟开 flag 或授特权（§10）。

## 7. 手机操控回路改造（阶段 3，最大改动）

问题：abstract unix socket 跨不了 VM 边界。

方案（已实现）：guest 内 C 桥 connect 模式
（`vsock-tcp-bridge -c 13083 3083`）监听 guest loopback 13083，
`relay.cjs` dial 127.0.0.1:13083（Node 无 AF_VSOCK，见 §6），桥经
vsock CID 2:3083 到 App；App 端 `PhoneVsockListener` 用
`Vsock.listen(3083)` accept 后双向 pump 进既有
`PhoneBridge` loopback TCP——hello token 常数时间比较、NDJSON JSON-RPC、
SafetyGate/审批全部不变。AVF 启动时 `PhoneControl.prepareAvfLaunch`
停旧 bridge、起 TCP+vsock 双端点，`bridge.json` 经 vm-ctl
`@phone/bridge.json` restore 进 guest tmpfs；relay/overlay 静态文件构建期
预置进镜像（`PhoneGuest.kt` 常量为唯一源，build-vm-image.sh 提取）。

不变量：

- SafetyGate、审批、任务所有者结算全部留在 App 端，引擎侧无安全裁决；
- hello token 常数时间比较、uid/来源校验语义保留（TCP 回调需校验对端为本 VM）；
- `android_shell` 与无障碍/Shizuku/ADB 执行路径零改动；
- AVF 模式保持「本机」语义，不解除也不绕过 remoteHost 门禁。

## 8. guest 文件注入与插件（阶段 4）

现状 `PhoneGuest`/`GuestSetup`/`ManagedSkillInstaller` 直接写 rootfs 文件。
VM 镜像只读化后（可写数据分区已证实不可行，见 §5/§6）：

- 静态内容（AGENTS.md、phone overlay、patch.yml）构建期预置进镜像；
- 动态内容（托管技能、phone 配置）经 vsock 控制通道 restore 写入 guest
  tmpfs（/root/.dsh 等），VM 重启后由 App 重新下发；
- 插件安装：首期 VM 内不带 pnpm，插件市场安装走「App 下载 → 校验 → 经控制
  通道 restore 写入 guest → 重启引擎」；`plugins_state` 的本机判定不变。
  若后续恢复 VM 内 pnpm，再评估镜像体积。

## 9. 分阶段计划与验证

| 阶段 | 内容 | 验证（真机为准） |
|---|---|---|
| 0 | 目标设备 AVF 可用性 + vsock/网络实测 | ✅ 已完成：VM 启动、vsock echo、HTTP over vsock 端到端（见 §6）；TAP 不可用 |
| 1 | VM 镜像构建目标（裁剪清单+内核+init） | ✅ 已完成：build-vm-image.sh 产出 748 MiB 镜像；真机 dsh web 就绪、登录 303+cookie、43 KB 页面 20/20、save/restore 往返、vsock 半关闭契约定论（见 §5/§6） |
| 2 | AvfRuntimeController + 转发 + token；PRoot 回退 | ✅ 真机：冷启动→主页、登录/余额/模型 live、save/restore×3、杀 VM→失败卡+重试正确；聊天出网经 egress 代理已实证（真实模型回复 + guest shell 执行，6 步 80.3K tokens） |
| 3 | phone 回路 vsock/TCP 改造 | ✅ 真机全链路已实证：guest relay → C 桥 13083 → vsock 3083 → App PhoneVsockListener → PhoneBridge TCP；`mcp__phone__apps` 成功列出 92 应用（screen 工具仅因设备无障碍服务未开被拒，非链路问题） |
| 4 | guest 注入/插件下发/诊断与文案核对 | 真机：插件安装、诊断包、双语 l10n/guide 同步 |

每阶段独立可合入、可回退；阶段 2 之前 PRoot 路径保持默认。涉及
`packages/dsh_client`、bridge、runtime 补丁的改动按 AGENTS.md 同时检查另一端
调用方与向后兼容。

## 10. 风险与开放问题

- 自定义 VM 权限是硬门槛：普通签名 APK 在多数设备上无法加载自定义内核/
  rootfs（只能 Microdroid）。若目标定制系统不放行，备选是 Microdroid +
  payload（把 Node/DSH 作为 microdroid payload 跑），镜像裁剪策略不同，
  需阶段 0 结论后另行评估。
- vsock 在 AVF 公开 API 中的可用性随版本变化；阶段 0 实测，不行就走 TCP。
- VM 内存开销（guest 内核 + Node 峰值 ~300 MB）高于 PRoot；低端机保留
  PRoot 回退。
- 自更新：APK 更新与 VM 镜像更新解耦，镜像版本进 manifest，沿用现有
  校验链（哈希钉死）。
- 冷启动时间：VM 启动 vs PRoot 解压启动需实测对比（参考
  `docs/design/runtime-cold-start.md`）。