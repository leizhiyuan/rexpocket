<p align="center">
  <a href="README.md">English</a> · <b>简体中文</b>
</p>

# RexPocket

手机上的 Rex 客户端。交互参照 [Moshi](https://getmoshi.app/docs/rex)：主机列表、会话选择、侧栏里的会话 / 窗口 / 窗格、底栏特殊键。只连你自己的 Rex，不经过第三方。

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
2. 点主机。有一个会话会直接进入，多个会话先出列表。
3. 终端：向下拖看回滚，右下角的箭头回到跟随输出；双指捏合改字号，长按选词后点底栏「复制」。侧栏「会话」打开会话 / 窗口 / 窗格，切换窗口用顶部的窗口标签。
4. 底栏：`Esc`、`Ctrl`（双击锁定）、`Tab`、`/`、方向键、`C-c` `C-d` `C-z` `C-l`、粘贴和复制。
5. 输入框把文字写进终端。点「发送」是文字加回车；长按「发送」只发一个回车，不发输入框里的文字。
6. 「会话」打开侧栏，点窗口或窗格就会 `focus`。进行中或等待你时，顶栏有「停」，发送 `Ctrl-C`。

## 语言

界面提供英文和简体中文。英文是默认语言（`values/`），中文在 `values-zh/`，所以英文系统启动就是英文，中文系统启动就是中文。Android 13 及以上支持单独切换应用语言。

## 构建

需要 Android SDK 36 和 JDK 17。

```sh
./gradlew :core:test :app:assembleDebug
```

调试包在 `app/build/outputs/apk/debug/app-debug.apk`。
