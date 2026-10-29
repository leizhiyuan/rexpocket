# RexPocket

手机上的 Rex 客户端。交互参照 [Moshi](https://getmoshi.app/docs/rex)：主机列表、会话选择、侧栏里的会话 / 窗口 / 窗格、底栏特殊键、滑动切窗口。只连你自己的 Rex，不经过第三方。

屏幕内容用服务器文档里的 `com.superlogical.terminal.format`（`vt`）重放，大约每 180ms 拉一次。没有内置 Ghostty，所以不解析数据流开头的 `GHOSTSNP` 快照。正在查看的窗格会被 `session.own_block` 占住，尺寸跟手机走；离开会话或断开后会 `disown`，桌面端可以拿回尺寸。

## 电脑上

只在你信任的网络上开明文端口。连上的人拥有这台 Rex 的全部权限。

```sh
rex server start --extra-listen :7297 --allow-anonymous
```

手机和电脑要在同一网络，或者你自己的 VPN / Tailscale 路由里。应用默认 `ws://主机:7297/api/control`，子协议 `rex.control.v1`。

## 手机上

1. 添加主机：名称、IP 或域名、端口 `7297`、明文打开。
2. 点主机。有一个会话会直接进入，多个会话先出列表。
3. 底栏：`Esc` `Ctrl`（双击锁定）`Tab` `C-c` 方向键、粘贴。双击终端粘贴，左右滑切窗口，双指捏合改字号，长按选词后点复制。
4. 输入框把文字写进终端。`↵` 表示发送时带换行，点一下改成不带回车。
5. 「会话」打开侧栏，点窗口或窗格就会 `focus`。进行中或等待你时，顶栏有「停」，发送 `Ctrl-C`。

## 构建

需要 Android SDK 35 和 JDK 17。

```sh
./gradlew :core:test :app:assembleDebug
```

调试包在 `app/build/outputs/apk/debug/app-debug.apk`。
