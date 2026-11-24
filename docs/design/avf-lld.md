# dsh-Go PRoot → AVF 虚拟化改造 详细设计说明书（LLD）

- 文档编号：DSHGO-AVF-LLD-001
- 版本：1.0（2026-11-24）
- 关联：[需求规格](avf-srs.md) · [概要设计](avf-hld.md) · [测试报告](avf-test-report.md)
- 代码基线：分支 `feat/avf-backend`（全部改动未提交）

> 本文按「guest 侧 → 传输层 → host 侧 → 构建流水线」顺序，把每个模块的
> 数据结构、协议、时序、边界条件讲透。所有结论均真机实证。

## 1. guest 镜像与启动

### 1.1 镜像布局（只读 ext4）

```
/                       Ubuntu Base 24.04.5（裁剪：无 pnpm/Python/git）
├─ init                 busybox sh PID1（runtime/vm/init，0755）
├─ usr/bin/busybox      busybox-static（init shebang 用 /usr/bin/busybox sh）
├─ opt/node/            Node 24.22.1
├─ usr/local/bin/dsh    补丁版 DSH（runtime/patches，target android）
├─ opt/dsh-go/
│   ├─ vsock-tcp-bridge 静态 aarch64（无 guest libc 依赖）
│   ├─ vm-ctl.mjs       控制通道服务端
│   └─ dsh-go-bridge/   App↔引擎 HTTP 桥
├─ opt/dsh-app/phone/   relay.cjs / phone.patch.yml / diagnostics-policy.mjs
├─ etc/dsh-app/dsh-web.patch.yml
├─ root/.dsh/           空目录（init 挂 tmpfs，0700）
├─ var/lib/dsh-app/phone/ 空目录（init 挂 tmpfs，0700）
└─ proc sys dev tmp     挂载点（ro 根上必须预建，否则 mount ENOENT）
```

镜像尺寸：784,793,600 B（748 MiB）ext4，未压缩内容 ~626 MB，21155 文件。
块数公式：`blocks = (du -sb × 5/4 + 4095) / 4096`（25% 余量给 ext4 元数据/
journal/inode 表），`mkfs.ext4 -q -F -d staging -b 4096 -m 0`。

### 1.2 vm-config.json（`vm run` 配置）

```json
{
  "kernel": "/apex/com.android.virt/etc/fs/microdroid_kernel",
  "params": "earlycon=uart8250,mmio,0x3f8 console=hvc0 root=/dev/vda1 ro rootwait init=/init",
  "memory_mib": 512,
  "platform_version": "1.0",
  "disks": [{"partitions": [{"label": "rootfs", "path": "<installed>/rootfs.ext4"}], "writable": false}]
}
```

要点（真机定论）：
- **必须显式 `init=/init`**：该内核 root= 模式只试 /sbin/init、/etc/init、
  /bin/init、/bin/sh，不试 /init（/init 仅 initramfs 语义）。
- **earlycon 必须 uart8250,mmio,0x3f8**（pl011 无输出）；console=hvc0。
- crosvm 自动给裸 ext4 包 GPT → guest 见 vda（整盘）+ **vda1（分区，fs 在这）**。
- `writable:false` + `ro`：可写盘彻底不可用（virtmgr 磁盘 fd 恒 O_RDONLY）。
- kernel 路径由 AvfImage.install 在设备上定位（virt APEX），不随 APK。
- `platform_version:"1.0"` 必填。

### 1.3 guest init（PID 1，busybox sh）

执行顺序（`runtime/vm/init`）：

1. mount proc /proc、sysfs /sys。
2. **tmpfs /dev**（devtmpfs 在此内核不可用）+ mknod null(1,3)/zero(1,5)/
   urandom(1,9)/console(5,1)/tty(5,0)，chmod 666（console 600）；
   mkdir /dev/shm /dev/pts，tmpfs /dev/shm。
3. tmpfs /tmp（1777）。
4. **tmpfs /root/.dsh（0700）**：DSH_HOME 必须可写（凭据/设置），ro 根
   写不进，由 vm-ctl 持久化。
5. **tmpfs /var/lib/dsh-app/phone（0700）**：bridge.json 每次启动下发。
6. `ip link set lo up`。
7. 导出环境（镜像 PRoot launch_template，去掉 pnpm 项）：
   - `DSH_HOME=/root/.dsh`、`NODE_OPTIONS=--dns-result-order=ipv4first`、
     `UV_USE_IO_URING=0`、`NARB_DISABLE_NATIVE_CACHE=1`。
   - **出网代理**：`HTTP_PROXY=HTTPS_PROXY=http://127.0.0.1:13082`（大小写
     都设）、`NO_PROXY=127.0.0.1,localhost`、`NODE_USE_ENV_PROXY=1`
     （DSH 的 installProxyFromEnvironment 生效）。
8. 起 4 个桥（见 §2）+ vm-ctl.mjs。
9. **dsh web 监管循环**：
   ```sh
   while :; do
     dsh web --patch /etc/dsh-app/dsh-web.patch.yml \
             --patch /opt/dsh-app/phone/phone.patch.yml \
             --host 127.0.0.1 --port 3080 --no-open > /tmp/dsh-web.log 2>&1 &
     wait $!; sleep 2   # 崩溃 2s 重启，VM 不重启
   done
   ```
   token 行写 /tmp/dsh-web.log（vm-ctl 监视）+ 串口控制台。

## 2. vsock-tcp-bridge（guest 侧 C 桥）

静态 aarch64（`aarch64-linux-gnu-gcc -static -O2`），171 行，双模式。

### 2.1 模式与用法

```
vsock-tcp-bridge <vport> <tport>      # listen 模式（host→guest）
vsock-tcp-bridge -c <tport> <vport>   # connect 模式（guest→host）
```

| 模式 | 守 | accept 后 dial | 实例 |
|---|---|---|---|
| listen | AF_VSOCK bind VMADDR_CID_ANY:vport, listen 16 | TCP 127.0.0.1:tport | `3080 3080`（web）、`3081 13081`（ctl） |
| connect(-c) | TCP 127.0.0.1:tport, listen 16 | AF_VSOCK connect CID_HOST(2):vport | `-c 13082 3082`（egress）、`-c 13083 3083`（phone） |

### 2.2 连接状态机（半关闭修复的核心）

```c
struct conn { int vfd, tfd; pthread_mutex_t mu; int done; };
```

- 每连接两个 detached pump 线程，各管一个方向（16KB 缓冲）。
- **方向归属按模式互换**：`cn->vfd = connect_mode ? t : c; cn->tfd = connect_mode ? c : t;`
  （listen 模式 accept 得到的是 vsock fd=c、dial 得到 tcp fd=t；connect 模式相反）。
- `conn_finish(cn, a, b)`：本方向 EOF → `shutdown(b, SHUT_WR)`（只半关
  **对端写侧**，让对端读到 EOF）→  mutex 下 `++done`，**最后完成者（done==2）
  close 双 fd + free**。
- **修复点**：旧代码任一方向 EOF 即双向 SHUT_RDWR+close，竞态掐断在途响应；
  新代码两方向独立收尾，客户端半关闭后仍能收完整响应。
- pump_rev 创建失败时：shutdown(t,SHUT_WR) + done++，让已运行的 th1 收尾。

### 2.3 与传输契约的关系

桥本身允许半关闭（EOF→SHUT_WR 对端），但 **guest 内核 vsock 在半关闭后丢
在途数据**（D7），所以 host 侧客户端一律不半关闭；guest 服务端响应后自关，
桥读到 EOF 正常收尾。桥的半关闭修复解决的是「桥内部」竞态，内核缺陷靠
host 契约规避，两者互补。

## 3. vm-ctl 控制通道

### 3.1 协议（guest 服务端 vm-ctl.mjs，TCP 13081 ← vsock 3081）

每连接一行 JSON 请求、一行 JSON 响应，服务端 `sock.end` 后关闭（allowHalfOpen:false）。

| cmd | 请求 | 响应 |
|---|---|---|
| status | `{"cmd":"status"}` | `{"ok":true,"ready":bool,"token":str|null,"baseUrl":str|null}` |
| save | `{"cmd":"save"}` | `{"ok":true,"files":{relPath:base64}}`（DSH_HOME 全部常规文件） |
| restore | `{"cmd":"restore","files":{...}}` | `{"ok":true,"restored":n}` |

### 3.2 token 捕获

- 正则 `^dsh web: (http://127\.0\.0\.1:[0-9]+/)\?token=([A-Za-z0-9_-]+)`
  扫 /tmp/dsh-web.log；fs.watch 监视，文件未建时 500ms 轮询直到命中。
- `ready = token != null`。token 只在 `GET /?token=…` 且 Host:127.0.0.1:3080
  时有效 → 303 + Set-Cookie（dsh-auth-…，30 天）；cookie 绑定 authority，
  VM 重启后 token/cookie 失效需重新 status+login（DSH 行为，不改引擎）。

### 3.3 restore 安全校验

- 拒绝绝对路径（`rel.startsWith('/')`）与 `..` 分量。
- `@phone/` 前缀**仅允许 `@phone/bridge.json`**，写入独立 tmpfs
  /var/lib/dsh-app/phone；其余写 DSH_HOME。
- 总量 ≤ 64MB（`MAX_FILES_BYTES`），超限 `{"ok":false,"error":"snapshot too large"}`。
- 文件 mode 0600，`mkdirSync recursive`。
- **@phone 不参与 save**（walk 只走 DSH_HOME），每次启动由 App 下发。

### 3.4 host 客户端 VmCtl.kt

- `request()`：Vsock.connect(cid,3081) → 写 `body+"\n"` → **读至 EOF**
  （不 SHUT_WR）→ 取首行 JSON。
- status/save/restore 三方法解析 ok/error；失败抛 IOException（稳定错误）。

## 4. host 侧 vsock 传输层（JNI + Kotlin）

### 4.1 JNI 原语（cpp/vsock_client.c，libvsock_client.so）

| C 符号（包名 dsh_app→dsh_1app） | 签名 | 行为 |
|---|---|---|
| `…_Vsock_connectNative` | (cid,port)→fd | socket(AF_VSOCK)+connect；失败返回 -errno |
| `…_Vsock_read` | (fd,buf,len)→n | read；0=EOF，-errno；GetByteArrayElements/Release |
| `…_Vsock_write` | (fd,buf,off,len)→n | 循环 write 整片，EINTR 重试；-errno |
| `…_Vsock_close` | (fd) | close |
| `…_Vsock_listenNative` | (port)→fd | socket+bind(VMADDR_CID_ANY:port)+listen(16)；-errno |
| `…_Vsock_acceptNative` | (fd)→cfd | blocking accept；-errno（调用方自起线程） |

- **AF_VSOCK=40**（Linux/Android），`struct sockaddr_vm{svm_family,svm_cid,svm_port}`。
- **不 open(/dev/vsock)**：只 socket+connect/listen（麒麟内核放宽，App uid
  在 0600 设备节点下仍可 connect，真机实证）。
- 错误统一 -errno，Kotlin 用 `Os.strerror(-fd)` 转可读消息。

### 4.2 Kotlin 包装（Vsock.kt）

- `object Vsock`：懒加载 `System.loadLibrary("vsock_client")`；available()
  捕获 UnsatisfiedLinkError（缺库表现为启动失败而非隐藏开关）。
- `connect(cid,port):VsockConnection` / `listen(port):VsockListener`。
- **`@JvmName` 固定 JVM 名**：internal external 成员否则被 Kotlin 加模块
  后缀（`$android_app_arm64Debug`），与 JNI 符号不符。read/write/close/
  listenNative/acceptNative 都钉名。
- `VsockConnection`：input/output 流共享 fd；read 把 EINTR 重试、0→-1(EOF)、
  负→IOException；write 整片；close 全关（**永不半关闭**）。
- `VsockListener.accept()`：blocking，调用方在独立线程循环；close 后 accept
  抛 IOException 退出循环。

### 4.3 VsockForwarder（HTTP 转发，loopback:3080 → vsock cid:3080）

- `start(preferred=3080)`：ServerSocket 绑 127.0.0.1:3080，占用则回退自由端口。
- 每连接：Vsock.connect(cid,3080) → 两方向 pump（16KB）。
- **上行（client→guest）EOF 时只停止写，不 close vsock**（半关闭丢响应）；
  下行（guest→client）读到 guest 关闭才结束连接，finally 全关双 fd。
- Dart DshClient 仍对话 loopback，Host 头保持 127.0.0.1:3080（cookie authority）。

## 5. host 侧出网代理 EgressProxy

### 5.1 监听与协议

- `Vsock.listen(3082)` accept 循环（avf-egress-accept 线程），每连接起
  avf-egress 线程 serve。
- **逐字节读请求头**到 `\r\n\r\n`（≤65536）：CONNECT 头很小，其后的 TLS
  ClientHello 必须留在流里给 pump（不能多读）。
- 解析请求行：
  - `CONNECT host:port` → host/port（默认 443）。
  - absolute-form `GET http://host/…` → URL 解析 host/port（默认 80）。
- `Socket().connect(InetSocketAddress(host,port), 10_000)`；`tcpNoDelay=true`。
  失败 → 回 `HTTP/1.1 502 Bad Gateway\r\n\r\n` + close（稳定错误，不静默）。
- CONNECT 成功 → 回 `HTTP/1.1 200 Connection Established\r\n\r\n` 后双向 pump；
  absolute-form → 把已读头转发给上游再 pump。
- **TLS 端到端**：App 只转字节，不解密；继承 App 网络栈（权限/代理/证书）。
- pump 16KB，任一方关闭即全关双 fd（不半关闭 vsock）。

### 5.2 与 guest 的衔接

guest init 设 `HTTP(S)_PROXY=127.0.0.1:13082` + `NODE_USE_ENV_PROXY=1`；
DSH 的 LLM/HTTP 流量 → C 桥 connect(13082→vsock 3082) → EgressProxy。
loopback 目标在 NO_PROXY 内，guest 直连不出 VM。

## 6. host 侧手机回路 PhoneVsockListener + PhoneControl

### 6.1 PhoneVsockListener

- `start(tcpPort)`：Vsock.listen(3083) accept → 每连接 serve：
  `Socket().connect(127.0.0.1:tcpPort, 5_000)`（PhoneBridge 的 loopback TCP）
  → tcpNoDelay → 双向 pump（16KB，全关收尾）。
- 只是字节隧道；**hello token 常数时间比较、NDJSON JSON-RPC、SafetyGate/
  审批/任务结算全在 PhoneBridge（App 端 TCP 之后），语义零改动**。

### 6.2 PhoneControl.prepareAvfLaunch

```
stopBridge() → bridge.start(withTcp=true) → bridge.addVsock()
  （内部 PhoneVsockListener.start(tcpPort)，endpoint.copy(vsockPort=3083)）
  → PhoneGuest.config(ep) 返回 bridge.json 内容（供 vm-ctl restore）
```
失败 → 记日志 + stopBridge + 返回 null（无 phone 工具启动，不阻塞引擎）。

### 6.3 guest relay（PhoneGuest.RELAY_JS）

- targets 优先级：vsockPort 存在 → `{host:'127.0.0.1', port:13083}`
  （C 桥隧道）；否则 abstract unix socket / TCP fallback（PRoot 路径保留）。
- relay/overlay/patch.yml 静态文件构建期从 PhoneGuest.kt 常量提取进镜像
  （唯一源）；bridge.json 动态，每次启动经 `@phone/bridge.json` restore。

### 6.4 checkReached 的 AVF 分支

引擎就绪 5s 后无认证连接：AVF（vsockPort!=null）只记日志「DSH did not
connect over vsock」，**不开 loopback fallback**（relay 直连 vsock，fallback
无意义）；PRoot 路径才加 TCP fallback。

## 7. AvfProcess 启动编排（时序与边界）

```
runStart(gen):
  vm.start(onLog)                     # spawn vm run，drain stdout 解析 CID
  arm exit-watch                      # 必须在 vm.start 之后（否则 awaitExit 拿 null 立即返回）
  cid = awaitCid(gen, 30s, 500ms)     # 超时/进程死 → throw
  status = awaitReady(cid, gen, 180s, 1s)  # vm-ctl status.ready && token
  restoreState(cid)                   # 必须在 awaitReady 之后（CID 刚出时 guest 未起，connect reset）
  startEgress()                       # vsock:3082
  httpPort = forwardHttp(cid)         # loopback:3080 → vsock cid:3080
  canConnect(httpPort)                # HTTP 200..499 探活
  onReady(baseUrl, token)
catch: startupError=msg; vm.stop; cleanupForwarder; onExit(1)
```

边界条件（真机踩坑定论）：
- **exit-watch 竞态**：watch 线程在 vm.start() 之后才 arm；start() 里 arm 会
  awaitExit() 拿 process==null 立即返回 -1 死掉 → 杀 VM 无回退。
- **restore 时序**：必须在 awaitReady 之后；失败不删 K_STATE 快照（下次重试）。
- **generation 防重入**：stopping/generation 检查贯穿各 await，stop 后旧
  gen 的回调静默丢弃。
- **stop()**：saveState（best-effort，死 guest 无可 save）→ cleanupForwarder/
  egress → vm.stop（destroy requester，5s 后 destroyForcibly）。
- **extraRestoreFiles**：@phone/bridge.json 合并进 restore，不进 save。

## 8. AvfVm / AvfImage

### 8.1 AvfVm

- `start`：ProcessBuilder(`/apex/com.android.virt/bin/vm run --name dsh-go
  --debug full --console <vm-console.log> <config>`)，redirectErrorStream，
  drain 线程正则 `with CID (\d+)` 解析 CID，其余行进 `[vm]` 日志。
- `stop`：destroy 自身进程（requester）→ virtmgr teardown guest；不依赖 vm list。
- `supported(context)`：vm 工具 canExecute && findGuestKernel()!=null &&
  MANAGE_VIRTUAL_MACHINE + USE_CUSTOM_VIRTUAL_MACHINE 均 GRANTED。
- 权限：manifest 声明两者（development 级），每次 install -r 后 `pm grant`。

### 8.2 AvfImage

- baseDir=filesDir/runtime-avf；四文件 rootfs.ext4/vm-config.json/
  vm-manifest.json/image.version。
- install：assets 拷贝 + SHA-256 校验（不符删 staging 抛错）+ 设备定位 kernel
  + 重写 vm-config 指向安装路径 + **staging 四文件原子换入**（半写不 current）。
- installedVersion：四文件齐才读 image.version；reset=deleteRecursively。
- findGuestKernel：/apex/com.android.virt/etc/fs/microdroid_kernel（或 etc/）。

## 9. RuntimeController 集成

- `avfEngine()`：DirectLaunch.Preference(saved=K_AVF_ENGINE, available=
  avfEngineAvailable(), failedRuntime=K_AVF_FAILED, runtime=avfBundledVersion)。
- `avfEngineAvailable()=avfBundledVersion!=null && AvfVm.supported(app)`。
- `setAvfEngine(enabled)`：要求停止态；与 direct/tawc 互斥；on 时 remove
  K_AVF_FAILED（清失败标记的免重装途径）。
- status()：bundledVersion 在 avf.requested 时用 avfBundledVersion（否则
  PRoot manifest）→ installedCurrent 语义对 AVF 成立；新增 avfEngine* 键、
  currentExecutionMode="avf"。
- 失败流转：onAvfExit（readyAt==null && !expected && !resetting）→
  K_AVF_FAILED=bundledVersion → restartNow → PRoot 回退（PRoot 已卸载则
  ERROR→web 错误页）。
- 失败卡清法：停 app 后 `sed -i '/avfEngineFailedRuntime/d' dsh_runtime.xml`。

## 10. 构建流水线 build-vm-image.sh

```
source build-runtime.sh（helpers）
ubuntu_base/node_dist/ubuntu_extra_debs（tzdata ca-certificates busybox-static，锁文件钉死）
VM_DSH_PREFIX 复用已验证 prefix（否则 install_dsh）
patch-dsh --target android（runtime/patches，pins）
aarch64-linux-gnu-gcc -static -O2 vsock-tcp-bridge.c
inputs_sha = SHA256(revision+abi+versions+base+node+debs+lock+脚本+init+vm-ctl+桥源码+PhoneGuest.kt+patches/bridge/boot/direct/session-deletion)[:12]
runtimeVersion = r15-dsh0.2.2-rc.1-node24.22.1-ubuntu24.04.5-vm<inputs_sha>
PHONE_DIR：python 从 PhoneGuest.kt 提取 RELAY_JS/PATCH_YAML/DIAGNOSTICS_*（唯一源）
assemble（rtbuild.py，无 pnpm/python）→ tar
fakeroot: tar 解包 + cp init/桥/vm-ctl/phone + mkfs.ext4 -d（blocks=du×5/4）
vm-config.json / vm-manifest.json（含 vsock 端口表+transportContract+tokenLine+build hashes）
assets/runtime-avf/（APK arm64 flavor）
```

JNI 单独构建：`runtime/vm/build-vsock-jni.sh`（NDK 自动探测）→
runtime/out-vm/jniLibs/arm64-v8a/libvsock_client.so；build.gradle.kts
`vmOut=rootProject.file("../../runtime/out-vm")`，jniLibs.srcDir(vmOut/jniLibs)。

## 11. 错误处理与恢复路径汇总

| 失败点 | 检测 | 恢复 |
|---|---|---|
| 镜像 SHA 不符 | install 校验 | 删 staging + 抛错，UI 报错可重装 |
| CID 超时(30s) | awaitCid | startupError + onExit(1) → 失败卡 |
| dsh web 未就绪(180s) | awaitReady | 同上 |
| restore 失败 | VmCtl 异常 | 记日志、保留快照、guest 全新启动（不阻塞） |
| dsh web 崩溃 | guest 监管循环 | 2s 重启，VM 不重启 |
| VM 意外死亡 | exit-watch | onExit → 失败卡 + 重试 / 安全模式回退 PRoot |
| egress 上游不可达 | Socket connect 失败 | 502 稳定错误 |
| phone vsock bind 失败 | addVsock null | 无 phone 工具启动，引擎照常 |
| 权限缺失 | supported() | 开关不可用 + 文案 |

所有失败要么可恢复（重试/回退/重启），要么明确报错；无静默吞、无无限重试。