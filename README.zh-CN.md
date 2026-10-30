<p align="center">
  <a href="README.md">English</a> · <b>简体中文</b>
</p>

# RexPocket

手机上的 Rex 客户端。交互参照 [Moshi](https://getmoshi.app/docs/rex)：主机列表、会话选择、终端顶部的标签页和窗格、底栏特殊键。只连你自己的 Rex，不经过第三方。

屏幕内容由 Rex 推过来。Rex 通过 `/api/data`（`rex.data.terminal.v1`）提供某个窗格：流一开始是整个终端的 `GHOSTSNP` 快照——回滚、颜色、光标，以及程序停在一半的那个转义序列——之后按到达顺序推它的输出，所以不再轮询，静止的窗格不花任何代价。[libghostty-vt](https://github.com/sagernet/libghostty-android) 恢复这个快照，再把之后的内容按顺序喂进去；连接断开会从断点续传，Rex 无法续传时重新开一条流并给一份新的快照。正在查看的窗格会被 `session.own_block` 占住，尺寸跟手机走；离开会话或断开后会 `disown`，桌面端可以拿回尺寸。

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

手机和电脑要在同一网络、你自己的 VPN / Tailscale 路由里，或者让应用自己加入 tailnet（见 [Tailscale](#tailscale)）。应用默认 `ws://主机:7297/api/control`，子协议 `rex.control.v1`。

## 手机上

1. 添加主机：名称加一个地址就行。`9.134.131.226` 是 7297 明文，`host:7300` 指定端口，`.ts.net` 名字或 `100.x` 地址自动走 Tailscale（443 + TLS）；输入框下面会显示推断结果。
2. 会话列表先画出这台主机上次的会话，连接建立后再换成实时数据，所以点进主机是立刻有内容的。点主机后总是先进会话列表。进入会话时回到手机上次在这个会话里看的窗格；第一次进入或那个窗格已经关掉时，显示 Rex 当前聚焦的标签页和窗格，和桌面端停在同一处。
3. 会话 → 标签页 → 窗格。Rex 里的 window 就是标签页。顶栏下面的标签栏列出会话的所有标签页，点一下就切换，Rex 会回到这个标签页上次聚焦的窗格。有多个窗格的标签页会标出数量，下面一行窗格条按编号列出这些窗格。手机任何时刻只画一个窗格，所以桌面上的分屏在这里是一排编号，不会并排显示。两行末尾的「+」分别新建标签页和窗格。长按标签或窗格会从底部弹出操作，可以重命名或关闭，关闭前会确认。
4. 终端：向下拖看回滚，右下角的箭头回到跟随输出；双指捏合改字号，长按选词后点底栏「复制」。
5. 底栏：`Esc`、`Ctrl`（双击锁定）、`Tab`、`⏎`、`/`、方向键、`C-c` `C-d` `C-z` `C-l`、粘贴和复制。
6. 输入框把文字写进终端。点「发送」或键盘自己的发送键，都是先写文字再按一次回车；输入框为空时只按回车，pi 这类全屏程序就靠这个回车确认它菜单里选中项。长按「发送」只发回车，不发文字。
7. `Esc`、`Tab`、方向键、`⏎` 和 `/` 发给窗格里跑的程序，所以 `/` 能打开 pi 的命令菜单、方向键能在菜单里走。在输入框里打 `/model` 再发送，文字会进到 pi 里，但紧随其后的那个回车被命令菜单吃掉，只用来补全候选，所以再点一下 `⏎` 才真正执行。
8. 「会话」列出这台 Rex 上的所有会话，点一下就切过去。「···」从底部弹出操作，按窗格 / 标签页 / 会话分组。进行中或等待你时，顶栏有「停」，发送 `Ctrl-C`。

## Tailscale

应用可以自己加入 tailnet：tailnet 里的主机用 MagicDNS 名字（`host.tailnet-xxxx.ts.net`）或 `100.x` 地址就能连，不用装 Tailscale 应用，也不占手机唯一的 VPN 位。节点跑在应用里（sing-box 的 Tailscale 端点），只有本应用连 Rex 的流量走它，手机其余流量不受影响。

1. 首页的 `Tailscale` 一行填这台手机的节点名，保存即可加入：应用会启动内置节点，没授权过就自己打开浏览器，授权完回来就是「运行中」。登录状态留在应用里，之后只有改节点名会重新注册。
2. 添加主机只要填一个地址：`192.0.2.10`、`host:7300`、`wss://host:443` 都行。端口、明文还是 TLS、要不要走 Tailscale，都由地址推出来——`.ts.net` 名字和 `100.x` 地址走 Tailscale（默认 443 + TLS），其它默认 7297 明文；字段下面会把推断结果写出来。
3. 地址在 tailnet 上但还没加入时，对话框会提示先加入；主机照常保存，点提示就能当场去加入。
4. 走 tailnet 的连接交给回环地址上的 SOCKS5 入站，由 sing-box 路由进 tailnet：名字由 MagicDNS 解析，`100.x` 地址由 Tailscale 拨号。节点在应用运行期间一直保持「运行中」，退出应用才停。

库只有 arm64-v8a：`app/libs/libbox.aar`，解包后约 57 MB，由 sing-box 1.14.4 用 [tools/build-libbox.sh](tools/build-libbox.sh) 构建。要别的 ABI 或别的协议集，改脚本重编。

## 语言

界面提供英文和简体中文。英文是默认语言（`values/`），中文在 `values-zh/`，所以英文系统启动就是英文，中文系统启动就是中文。Android 13 及以上支持单独切换应用语言。

## 构建

需要 Android SDK 36 和 JDK 17。

```sh
./gradlew :core:test :app:assembleDebug
```

`app/libs/libbox.aar` 已随仓库提供（见 [Tailscale](#tailscale)）。终端需要能恢复 `GHOSTSNP` 快照的
`libghostty-android`，已发布的那个 alpha 做不到，所以 `app/build.gradle.kts` 用的是 `0.1.0-SNAPSHOT`，
从本地 Maven 仓库解析：先在 [libghostty-android](https://github.com/sagernet/libghostty-android)
里执行 `./gradlew publishToMavenLocal`。

调试包在 `app/build/outputs/apk/debug/app-debug.apk`。
