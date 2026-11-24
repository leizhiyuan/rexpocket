# 通过 SSH 添加主机

本文说明 RexPocket 的 SSH 主机功能：为什么需要它、用户看到什么、连接如何建立、错误和密钥如何处理，以及验证方式。

## 1. 目标

Rex 桌面版支持用 SSH 添加主机：机器上不需要额外开放 Rex 端口，客户端通过这台机器已有的 SSH 服务连进去。RexPocket 增加同样的能力，使手机可以连接只开了 SSH 的机器。

成功标准：

- 用户能在手机上添加 `ssh://host[:port]`，填写账号，选择密码或私钥；
- 第一次连接会确认主机密钥，之后固定该密钥；
- 连接建立后，现有的会话列表、终端、输入、文件发送全部照常工作；
- 不需要在远端开放 Rex 的 TCP/WebSocket 端口；
- tailnet 地址仍然可以走 App 内置的 Tailscale 节点；
- 密码和私钥不以明文落盘。

非目标：

- 不做通用 SSH 客户端：不提供交互 shell、端口转发、SFTP、agent forwarding；
- 不读取手机上的 `~/.ssh/config`（Android 没有这样的约定），也不依赖 SSH agent；
- 不实现 known_hosts 文件格式，主机密钥按单台主机保存在 App 设置中；
- 不修改远端 Rex 的安装方式；远端仍需已安装 Rex。

## 2. 用户模型

### 2.1 地址与账号

地址支持两种写法：

```text
ssh://192.0.2.10
ssh://192.0.2.10:2222
ssh://me@192.0.2.10          # 账号也可以写在地址里
ssh://my-mac.tail1234.ts.net # tailnet 地址
```

如果地址里没有 `user@`，界面会显示独立的 **Username** 字段。这样手机键盘上不用输入 `ssh://user@host` 这种长串；两种写法等价。

端口默认 22。地址是 MagicDNS 名字、`100.64/10` 地址或 Tailscale IPv6 时，自动标记为 tailnet。

### 2.2 登录方式

两种，二者选一：

- **Password**：账号 + 密码；
- **Private key**：OpenSSH 或 PEM 私钥，可粘贴，也可从文件选择；有口令时填写 passphrase。

界面会在保存前检查账号和凭据是否齐全。密码和私钥不会同时保存：切换登录方式后，另一种凭据在保存时清空。

### 2.3 主机密钥

第一次连接时，App 显示机器 SSH 主机密钥的 SHA-256 指纹（OpenSSH 的 `SHA256:...` 格式），由用户和电脑上的密钥核对后确认。确认后指纹按主机保存。

之后每次连接都比较该指纹：

- 一致：继续；
- 不一致：停止重试，显示红色警告，要求用户明确确认新密钥。可能的正常原因是重装或更换主机密钥；也可能是有人在冒充这台机器。

App 不会自动接受未知主机密钥，也不会静默接受变化后的密钥。

也可以一次导入多台主机，并预先固定各自的主机密钥（第一次连接即被校验，不再询问），格式与信任规则见 [host-import.md](host-import.md)。

### 2.4 凭据存储

密码、私钥和 passphrase 使用 Android Keystore 中的 AES-256-GCM 密钥加密，再写入 App 的 SharedPreferences。存储格式带 `v1:` 前缀，内容为 `IV + ciphertext` 的 Base64。

因此：

- SharedPreferences 或 Android 备份中只有密文；
- Keystore 密钥不会随备份迁移；换机恢复后密文无法解开，App 将其视为“尚未填写凭据”，要求重新输入；
- 解密失败不会被当作空密码继续连接，而是让用户重新填写。

## 3. 连接模型

### 3.1 总览

Rex 的客户端协议没有改变：控制面仍是 `/api/control` 的 WebSocket，终端仍是 `/api/data` 的 WebSocket。改变的是这些连接的承载方式。

```text
OkHttp (control WS / data WS / upload)
        │  java.net.Socket
        ▼
SshTunnel.ChannelSocket
        │  每条 HTTP/WebSocket 连接一条 SSH exec channel
        ▼
JSch Session（每台主机一条）
        │  ssh://user@host:22
        ▼
远端 sshd
        │  exec: rex __internal dial-stdio
        ▼
远端 Rex 的 Unix socket
```

关键点：

- **每条** HTTP/WebSocket 连接对应一条 SSH exec channel；
- 同一台主机只有**一条** SSH session，channel 复用它；
- 远端命令是 `rex __internal dial-stdio`，它把标准输入输出接到 Rex 自己的 Unix socket；
- 手机侧没有监听任何 Rex 端口，远端也不需要开放 Rex 端口。

地址栏的 `ws://` 在这条路径上仍然存在，但它只存在于 SSH 通道内部。网络上看不到 WebSocket 端口。

### 3.2 把 SSH channel 伪装成 Socket

`SshTunnel` 提供一个自定义 `javax.net.SocketFactory`。OkHttp 以为自己在连接 `127.0.0.1:7297`，实际拿到的是 `ChannelSocket`：

- `connect()`：打开一条 `session.openChannel("exec")`，设置命令并连接；
- `getInputStream()` / `getOutputStream()`：读写 channel；
- `close()`：断开 channel；
- `isClosed()`：channel 关闭后让 OkHttp 的健康检查失败；
- `setSoTimeout()`：有意忽略。Rex 的 WebSocket 自己每 30 秒 ping，读超时会错误地切断空闲终端。

`Endpoint` 在 SSH 模式下把 URL 固定为 `ws://127.0.0.1:7297/...`。这个地址不用于 TCP 连接，只用于让 OkHttp 构造请求；DNS 和实际路由由 `SshTunnel` 的 SSH socket 负责。

### 3.3 输出必须显式 flush

JSch 的 channel 输出流是带缓冲的：`write()` 只把数据放进缓冲区，`flush()` 才真正发出去。普通 socket 的 `OutputStream` 没有这个行为。

如果直接返回 JSch 的流，OkHttp 写完 HTTP 请求或 WebSocket 帧后，数据可能还停在 JSch 缓冲区，远端收不到，表现为握手后一直等。

因此 `ChannelSocket` 的 `OutputStream` 在每次 `write(byte[], off, len)` 后调用 `flush()`。OkHttp 一次写一个请求或一个帧，这个粒度足够，也不会把一个大帧拆成很多次 flush。

### 3.4 连接池

SSH 的 OkHttpClient 关闭了 HTTP 连接池（`ConnectionPool(0, 1, NANOSECONDS)`）。

原因：池里的连接对应的远端 `rex __internal dial-stdio` 进程可能已经结束。把这种连接复用给下一条请求，会在一个已经死掉的 channel 上写数据。每条连接一条 channel 的成本可以接受：控制连接 1 条、终端 1 条、上传 1 条，远低于 sshd 默认的 `MaxSessions`。

### 3.5 tailnet

SSH 的 TCP 握手由 JSch 的 `SocketFactory` 建立。如果主机在 tailnet 上，使用 App 内置 Tailscale 节点提供的本地 SOCKS5 端口：

```text
Socket(Proxy.SOCKS, 127.0.0.1:<tailscale socks port>)
connect(InetSocketAddress.createUnresolved(host, port))
```

地址保持 unresolved，让 Tailscale 自己解析 MagicDNS。这样 `host.tailnet-xxxx.ts.net` 和 `100.x` 地址都能用。

## 4. 重连与错误

### 4.1 重连层次

```text
PocketSession.start
  └─ 连接失败：按退避重试（现有逻辑）
       └─ SshTunnel.connectLocked
            └─ SSH session 不存在或已断开：重建 session
       └─ openChannel
            └─ channel 打不开：替换 session，再试一次
```

`SshTunnel` 只负责传输；`PocketSession` 仍按原有逻辑重连控制连接，`TerminalStream` 仍按原有逻辑续传终端流。SSH 层不重复实现这些重试。

远端网络变化后，JSch 的 `isConnected()` 可能短暂返回 true。打开 channel 失败时，`SshTunnel` 会主动断开旧 session、重建，再试一次，避免卡在“看起来还连着”的会话上。

### 4.2 哪些错误不重试

以下错误由用户修正，重试没有意义：

| 异常 | 界面类型 | 含义 |
| --- | --- | --- |
| `SshHostKeyException(changed=false)` | `SshHostKeyUnknown` | 主机密钥尚未确认 |
| `SshHostKeyException(changed=true)` | `SshHostKeyChanged` | 主机密钥变了 |
| `SshAuthException` | `SshAuth` | 密码/私钥被拒绝，或私钥读不出来 |
| `SshCommandException` | `SshCommand` | 远端找不到 `rex`，或 Rex 版本不支持该命令 |

`PocketSession` 通过 `isFatalConnectionProblem()` 判断并停止重试。用户修正凭据或确认主机密钥后，再手动重连。

网络类错误（连不上、超时、连接被断）仍按退避重试。

### 4.3 OkHttp 会吞掉传输原因

在握手阶段，OkHttp 可能把底层错误归并成 `IOException("exhausted all routes")`，原始异常只作为被抑制异常存在，`cause` 链里没有。

为此：

- `SshTunnel` 记录最近一次传输失败（`lastFailure()`）；
- `RexClient.forSsh()` 把该失败传给 `connect()`；
- `connect()` 发现 OkHttp 的失败是笼统的 `Other` / `Unreachable` / `TimedOut` 时，改用传输层异常。

这样“主机未确认”“密码错误”“远端没有 rex”仍然能到达界面。

### 4.4 远端 stderr 与“没有 rex”

远端的 stderr 被收集到有上限的缓冲区。以下文本会立即被识别为致命命令错误：

- `rex was not found`
- `command not found`
- `no such file`
- `unknown command`
- `unknown flag`
- `dial-stdio`

识别发生在 OkHttp 归并错误之前，所以“远端没装 Rex”不会变成一个含糊的“连接失败”。

远端命令本身带路径回退：

```sh
exec /bin/sh -c '
  command -v rex >/dev/null 2>&1 && exec rex __internal dial-stdio;
  for p in "$HOME/.local/bin/rex" /usr/local/bin/rex /usr/bin/rex; do
    if [ -x "$p" ]; then exec "$p" __internal dial-stdio; fi;
  done;
  echo "rex was not found on this machine" >&2; exit 127
'
```

`rex server install` 默认把二进制放到 `~/.local/bin`，非交互 SSH 的 PATH 不一定包含它，所以命令自己找这三个位置。

## 5. 密码与密钥认证

### 5.1 认证方式按凭据选择

JSch 默认会按 `PreferredAuthentications` 尝试多种方式。密码主机如果仍尝试 publickey，会浪费服务器的认证次数；服务器 `MaxAuthTries` 较小时会直接断开，报：

```text
SSH_MSG_DISCONNECT: 2 too many authentication failures
```

因此 `SshTunnel` 根据保存的凭据设置：

| 凭据 | PreferredAuthentications |
| --- | --- |
| 只有密码 | `password,keyboard-interactive` |
| 只有私钥 | `publickey` |
| 两者都有 | `publickey,password,keyboard-interactive` |

密码同时通过 `Session.setPassword(byte[])` 和 `UserInfo` 提供，兼容使用 keyboard-interactive 的服务器。

### 5.2 Ed25519 在 Android 上的实现

JSch 2.28.x 的 Ed25519 默认实现依赖 Java 15 的 `java.security` API；Android 上没有这些类，X25519 的 JCE 实现也不可用。

App 因此：

- 引入纯 Java 的 `net.i2p.crypto:eddsa`；
- 实现 `Ed25519Signature`（JSch `SignatureEdDSA`）和 `Ed25519KeyPairGen`（JSch `KeyPairGenEdDSA`）；
- 在 JSch 初始化时替换：

```kotlin
JSch.setConfig("ssh-ed25519", Ed25519Signature::class.java.name)
JSch.setConfig("keypairgen.eddsa", Ed25519KeyPairGen::class.java.name)
JSch.setConfig("keypairgen_fromprivate.eddsa", Ed25519KeyPairGen::class.java.name)
```

注意 JSch 的配置键是算法名 `ssh-ed25519`，不是 `signature.ed25519`。使用错误的键时，`CheckSignatures` 会把 `ssh-ed25519` 从服务器主机密钥算法列表中移除，表现为和只提供 Ed25519 主机密钥的服务器“算法协商失败”。

密钥交换不使用 X25519，而是限制为 Android 上可用的：

```text
ecdh-sha2-nistp256, ecdh-sha2-nistp384, ecdh-sha2-nistp521,
diffie-hellman-group14-sha256, diffie-hellman-group16-sha512,
diffie-hellman-group18-sha512
```

所有 Rex 支持运行的 Linux/macOS 服务器都提供这些算法。

### 5.3 主机密钥指纹

指纹按 OpenSSH 的格式计算：

```text
SHA256:base64(sha256(SSH public key blob))   // 无 padding
```

和 `ssh-keygen -lf` 输出一致，用户可以直接核对。

## 6. 生命周期与并发

- `SshTunnel` 每台主机一个，在 `RexViewModel.connect()` 中创建；
- 控制连接、终端流、上传共用同一个 `SshTunnel.http()`；
- 断开主机时，先让 `PocketSession.stop()` 完成 disown 的最后一轮 RPC，再关闭 SSH session；
- `SshTunnel` 的 session 连接、重建和关闭由一把锁串行化；
- 不同 HTTP 连接可以并发打开 channel，JSch session 支持多 channel；
- channel 输出流每次写入后 flush；
- 读超时被忽略，依赖 Rex WebSocket 的 ping 检测空闲连接。

## 7. 终端流的性能修复

SSH 会把终端快照拆成比直连更小的片段。最初的 `StreamDecoder` 用：

```kotlin
pending = pending + bytes
```

每来一个片段就复制整个已累积快照。快照是 MB 级时，这是 O(n²) 的复制和 GC；而这些解码又发生在主线程（`watchPane` 的 flow 由 UI 收集），最终触发 ANR。

修复分两部分：

1. `StreamDecoder` 使用一个按需几何增长的 `ByteArray` 和一个 `size`，追加时只在容量不足时扩容，不再每个片段复制整个缓冲区；
2. `TerminalStream.feeds()` 加上 `flowOn(Dispatchers.Default)`，解码在网络/默认调度器上完成，UI 线程只接收最终 feed 并绘制。

这两点对直连同样有益，只是 SSH 的小片段更容易暴露问题。

## 8. 安全边界

- 主机密钥首次使用必须人工确认，之后固定；
- 密钥变化必须人工确认，且界面用危险色提示；
- 密码、私钥、passphrase 只以 Keystore 加密后的形式落盘；
- 诊断报告只包含主机名和用户名（会被替换为 `<hidden>`），不包含密码、私钥、终端内容；
- 不做 host key 自动接受；
- 不做 agent forwarding、端口转发等额外能力，减少攻击面。

已知限制：

- 没有 known_hosts 文件，也不与系统 SSH 共享主机密钥；
- 没有 SSH agent、ProxyCommand、跳板机、SSH config；
- 远端必须是类 Unix 系统（`/bin/sh` 存在），且已安装 Rex；
- 旧版 Rex 如果没有 `__internal dial-stdio`，会得到明确的“更新 Rex”错误，而不是静默失败；
- 换机恢复备份后，加密凭据需要重新输入。

## 9. 测试

单元测试：

- `HostAddressTest`：SSH 地址、端口、IPv6、tailnet、缺少账号、非法端口；
- `SshTunnelTest`：
  - RFC 8032 Ed25519 签名/验签向量；
  - OpenSSH 格式的主机密钥指纹；
  - 远端 stderr 的“没有 rex”识别；
  - 主机密钥/认证/命令错误到界面类型的映射；
- `StreamDecoderTest`：快照分片、Rex 消息、逐字节输入。

集成测试（默认跳过，设置环境变量后运行）：

```sh
REX_SSH_HOST=127.0.0.1 \
REX_SSH_PORT=2222 \
REX_SSH_USER="$USER" \
REX_SSH_KEY="$HOME/.ssh/id_ed25519" \
./gradlew :core:test --tests '*SshTunnelTest*'
```

也支持 `REX_SSH_PASSWORD`、`REX_SSH_KEY_PASSPHRASE`、`REX_SSH_HOST_KEY` 和 `REX_SSH_SOCKS`。

本地验证覆盖：

- 本机 OpenSSH sshd + 真实 Rex：私钥、主机密钥首次确认、控制连接、bootstrap；
- 本地密码 SSH 服务 + 真实 Rex：账号密码、主机密钥确认、连接成功；
- Android 模拟器：添加 SSH 主机、填写账号密码、确认主机密钥、加载会话列表、打开终端窗格；
- 用直连时相同的终端数据流验证 SSH 分片下不再 ANR。

## 10. 取舍与备选方案

**为什么不用 SSH streamlocal 转发（`direct-streamlocal`）？**

理论上可以 `ssh -L local.sock:remote.sock`，再让 OkHttp 连接本地 Unix socket。但远端 Rex socket 的路径随平台、`XDG_RUNTIME_DIR`、自定义 `REX_SERVER` 变化；`rex __internal dial-stdio` 由 Rex 自己解析，天然兼容这些情况。exec channel 也不需要 Android 支持本地 Unix socket。

**为什么选 JSch 而不是 Apache MINA SSHD？**

JSch 体积小、无额外运行时依赖，且自带可注入的 `SocketFactory`，接 Tailscale SOCKS5 很自然。MINA 的 Ed25519 支持更完整，但需要额外的 I/O 适配和代理转发实现。JSch 缺的 Ed25519，用约 100 行的 eddsa 适配补齐。

**为什么仍然使用 WebSocket 协议？**

Rex 的客户端 API 就是 WebSocket。SSH 功能改变的是承载层，不是协议层。这样做可以复用现有的控制面、终端流、上传、会话模型和所有测试，不需要再实现一套 Rex 协议。

**为什么不把 SSH 连接做成全局共享？**

不同主机、不同凭据、不同 tailnet 状态需要独立 session；按主机隔离更简单，也不会让一台主机的故障影响另一台。

## 11. 后续可做

- 支持从文件选择 known_hosts / 导入已有主机密钥；
- 连接级 keepalive 配置（目前依赖 WebSocket ping）；
- SSH agent / 硬件密钥；
- 跳板机、ProxyJump；
- 对远端 Rex 版本做一次显式能力探测，给出更精确的升级提示。
