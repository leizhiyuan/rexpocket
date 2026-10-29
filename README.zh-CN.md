<p align="center">
  <a href="README.md">English</a> · <b>简体中文</b>
</p>

# RexPocket

手机上的 Rex 客户端。交互参照 [Moshi](https://getmoshi.app/docs/rex)：主机列表、会话选择、侧栏里的会话 / 标签页 / 窗格、底栏特殊键。只连你自己的 Rex，不经过第三方。

屏幕内容用服务器文档里的 `com.superlogical.terminal.format`（`vt`）重放，大约每 180ms 拉一次。转储是整个回滚加屏幕的线性重放，所以渲染交给 [libghostty-vt](https://github.com/sagernet/libghostty-android)：第一次到达时先 `RIS` 再整份回放，之后的刷新只把新增的尾部喂进去，这样打字或输出时原生滚动位置不会被顶回底部。正在查看的窗格会被 `session.own_block` 占住，尺寸跟手机走；离开会话或断开后会 `disown`，桌面端可以拿回尺寸。

发送一行是「写文字」再加一次独立的回车按键，不把换行拼进粘贴内容里。pi 这类全屏 TUI 会把粘贴内容里的换行当成自己输入框的换行，所以拼在一起只换行不提交。

## 截图

| 主机 | 会话 | 终端 |
| --- | --- | --- |
| ![主机](docs/screenshots/home-zh.png) | ![会话](docs/screenshots/sessions-zh.png) | ![终端](docs/screenshots/terminal-zh.png) |

## 电脑上

只在你信任的网络上开明文端口。连上的人拥有这台 Rex 的全部权限。

```sh
rex server start --extra-listen :7297 --allow-anonymous
```

手机和电脑要在同一网络，或者你自己的 VPN / Tailscale 路由里。应用默认 `ws://主机:7297/api/control`，子协议 `rex.control.v1`。

## 手机上

1. 添加主机：名称、IP 或域名、端口 `7297`、明文打开。
2. 点主机后总是先进会话列表。进入会话时显示 Rex 当前聚焦的标签页和窗格，和桌面端停在同一处。
3. 会话 → 标签页 → 窗格。Rex 里的 window 就是标签页。顶栏下面的标签栏列出会话的所有标签页，点一下就切换，Rex 会回到这个标签页上次聚焦的窗格。有多个窗格的标签页会标出数量，下面一行窗格条按编号列出这些窗格。手机任何时刻只画一个窗格，所以桌面上的分屏在这里是一排编号，不会并排显示。两行末尾的「+」分别新建标签页和窗格。长按标签或窗格可以重命名或关闭，关闭前会确认。
4. 终端：向下拖看回滚，右下角的箭头回到跟随输出；双指捏合改字号，长按选词后点底栏「复制」。
5. 底栏：`Esc`、`Ctrl`（双击锁定）、`Tab`、`/`、方向键、`C-c` `C-d` `C-z` `C-l`、粘贴和复制。
6. 输入框把文字写进终端。点「发送」是文字加回车；长按「发送」只发一个回车，不发输入框里的文字。
7. 「会话」打开侧栏，列出所有会话、当前会话的标签页和其中的窗格。「···」按窗格 / 标签页 / 会话分组列出操作。进行中或等待你时，顶栏有「停」，发送 `Ctrl-C`。

## 语言

界面提供英文和简体中文。英文是默认语言（`values/`），中文在 `values-zh/`，所以英文系统启动就是英文，中文系统启动就是中文。Android 13 及以上支持单独切换应用语言。

## 构建

需要 Android SDK 36 和 JDK 17。

```sh
./gradlew :core:test :app:assembleDebug
```

调试包在 `app/build/outputs/apk/debug/app-debug.apk`。
