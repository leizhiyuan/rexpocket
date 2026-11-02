<p align="center">
  <a href="README.md">English</a> · <b>简体中文</b>
</p>

# RexPocket

手机上的 Rex 客户端：一张主机列表，会话就挂在各自的主机下面；终端铺满屏幕，底部一条浮动的 dock。只连你自己的 Rex，不经过任何第三方。

- **画面是推过来的，不轮询。** Rex 通过 `/api/data`（`rex.data.terminal.v1`）提供窗格：流一开始是整个终端的 `GHOSTSNP` 快照——回滚、颜色、光标，以及程序停在一半的那个转义序列——之后按到达顺序推输出。静止的窗格不花任何代价：手机上实测每 20 秒 2 个 CPU tick、0.3 KB 流量，轮询时是 107 tick 和 500 KB。[libghostty-vt](https://github.com/sagernet/libghostty-android) 恢复快照，再把之后的内容按顺序喂进去，所以重连后看到的是窗格当前的样子，而不是重放的历史。
- **输入严格按顺序到达。** 窗格里的输入、dock 里的按键和粘贴走同一个队列；Rex 只保证部分方法的顺序，所以它们一次发一个，每个都等前一个的回复。
- **在后台是安静的。** 不在前台时不轮询任何东西，连接保持。
- **出错会说清楚。** 连不上、无响应、连接断开、Rex 拒绝、未加入 tailnet，各有各的说法，两个页面都有 **立即重试**。网络切换会立刻重连。
- **你看的窗格归你。** 它被 `session.own_block` 占住并按手机尺寸显示。如果桌面端把它抢回去（Rex 在窗口获得焦点时会这么做），App 会在几秒内取回并重新应用手机尺寸，不会再出现按桌面宽度显示、右侧被裁掉的情况。菜单里说明现在是哪一边——手机占着时是 **交还**，交回桌面后是 **接管**——桌面拿着时窗格图标上有一个蓝点。交还之后取回循环不会再自作主张地抢回来；交还属于这个窗格，切到别的标签、别的会话再回来也不会重新接管，窗格一直归桌面，直到你再次接管。

## 截图

| 主机与会话 | 终端 | 夜间终端 |
| --- | --- | --- |
| ![主机与会话](docs/screenshots/home-zh.png) | ![终端](docs/screenshots/terminal-zh.png) | ![夜间终端](docs/screenshots/terminal-dark-zh.png) |

## 在电脑上

只在你信任的网络上开放明文端口。能连上的人拥有这台 Rex 的全部权限。

```sh
rex server start --extra-listen :7297 --allow-anonymous
```

手机和电脑要在同一个网络，或在同一个 VPN / Tailscale 路由里，也可以让 App 自己加入你的 tailnet（见 [Tailscale](#tailscale)）。控制连接是 `ws://host:7297/api/control`，子协议 `rex.control.v1`；窗格的数据流是同一主机上的 `/api/data`。

## 在手机上

1. **主页。** 一张列表，三层。主机是一节，会话是它下面的分组，会话里的标签页是行：图标、标签名、窗格数量，右边一个箭头。会话那一行带着标签页数量、新建标签的加号，和折叠箭头。主机那一行说明它是否在线；正在连接的那台还会写出 tailnet 走的是哪条路。点标签页就打开它——挂上会话、聚焦那个标签；主机还没连接时，点一下会先连接，并显示 *正在打开*。还没加载标签页的会话显示 *打开以查看标签页*。眼睛只保留当前有活动的：有窗格在工作或在等回答。搜索按钮过滤主机、会话和标签；齿轮打开设置——Tailscale、添加主机、源码。主机的更多菜单新建会话、编辑主机或断开连接。主机就是名字加地址：`192.0.2.10` 是 7297 端口的明文连接，`host:7300` 指定端口，`.ts.net` 名字或 `100.x` 地址会走 Tailscale、443 端口、TLS；输入框下面那行会显示 App 解析出的结果，删除主机会先确认。
2. **顶部。** 会话页的控制浮在终端上方：圆形返回键，然后是窗格图标、标签名和它所在的目录，下面一行是会话、状态和箭头。点这一整块打开当前会话的标签与窗格，最上面是会话列表；右侧的更多按钮里是操作。图标上的蓝点表示这个窗格现在不归手机。
3. **终端。** 铺满屏幕，输入直接进窗格：点一下拉起键盘。拖动查看回滚，双指缩放改字号，长按选中后在按键面板点 `Copy` 复制。在 Rex 送来第一份快照之前，这块区域显示 *正在读取画面…*；等太久会给重试——历史很长的窗格要传好几兆。
4. **Dock。** 底部一条浮动的胶囊：键盘（键盘弹出时变成收起键盘）、回车和按键。回车发送 Enter 键；按键面板里是 `esc`、`ctrl`（双击锁定）、Tab、`/`、方向键、`C-c`、`C-d`、`C-z`、`C-l`、粘贴、复制和方向键盘，用完可以收起，不占常驻一行。胶囊右边的圆形按钮新建标签。`esc`、Tab、方向键、`/` 都是发给窗格里程序的按键，所以 `/` 打开 pi 的命令菜单；程序在运行或在等你时，`C-c` 会显示成 `停止` 并变红。`ctrl` 对按键面板里接下来按下的那一个键生效。
5. **手机上的多个窗格。** 手机一次只画一个窗格，所以桌面上的分屏在终端下面有一条指示条：每个窗格一段，带序号和名字，当前那段高亮。点某一段，或在窗格上左右滑，可以在窗格之间切换。滑动手势是终端唯一读取的手势，滚动和选中仍然是终端的。
6. **交还与接管。** 在更多菜单里，按窗格生效：交还把窗格让给桌面，停止手机改尺寸，关掉取回循环；切标签、切会话再回来都仍然保持，只有 **接管** 会结束它。`own_block` / `disown_block` 串行化，正在进行的接管不会撤销一次交还。
7. **外观。** 跟随手机：白天是浅色分组纸面，夜里是近黑色。终端纸面、系统栏都跟着变。

## Tailscale

App 可以自己加入你的 tailnet，于是那里的主机可以用 MagicDNS 名字（`host.tailnet-xxxx.ts.net`）或 `100.x` 地址访问，不需要装 Tailscale，也不占手机唯一的 VPN 槽位。节点跑在 App 里（sing-box 的 Tailscale endpoint）：只有 App 为 Rex 打开的连接走它，手机其余流量不受影响。

1. 首页的 `Tailscale` 行会让你填这台手机的节点名；保存即加入 tailnet：App 启动节点，如果从未授权过，会自己用浏览器打开授权页。登录状态留在 App 里，所以只需要一次。
2. 这一行会说清节点在做什么：启动中、运行中、需要登录、等待审批、启动失败——具体原因点进去就能看到。如果启动卡在 libbox 的缓存文件上（进程被杀时可能留下），App 会丢掉那个缓存再试一次，不会一直停着直到清数据。
3. tailnet 连接通过回环上的 SOCKS5 入口拨出，由 sing-box 路由进 tailnet，所以 MagicDNS 名字由 Tailscale 解析，`100.x` 地址也由它拨号。窗格的数据流和控制连接走同一条路。
4. **节点的 UDP 绕过手机上的 VPN。** 否则 Clash 这类代理 VPN 会把节点的 WireGuard 和打洞报文也一并转发：节点会把代理的出口当成自己的地址，直连建不起来，经代理的握手一直卡住，窗格永远等不到画面。所以节点把 UDP socket 绑到手机自己的网络（Wi-Fi 或移动数据），而 TCP——连控制面和 DERP 的——仍走默认路由，被墙的 `tailscale.com` 就靠代理访问。Android 只有在 VPN 允许 App 绕过时才放行，Clash 是允许的。这部分报文因此不受 VPN 规则约束。
5. **会显示走的是哪条路。** tailnet 上的主机，主机列表里它名字下面那行会写手机怎么到它——`直连`、`经 peer relay 中转`、`经 DERP sfo 中转`——并附上往返毫秒数，点一下重新测量。同一个 Wi-Fi 下是直连；从移动数据连家里路由器后面的电脑，通常是经国外 DERP 中转，几百毫秒，窗格的第一屏要好几秒。
6. **能不能直连取决于网络。** 电脑上的节点要能从外面访问：家里路由器开 UPnP 或 NAT-PMP，或者给它的 Tailscale UDP 端口做端口映射，手机就能打通。做不到的话，在国内放一台 DERP 或 peer relay，中转就从国外拉回来，降到几十毫秒；这两者都在 tailnet 里配置，App 不用改。

这个库只有 arm64-v8a：`app/libs/libbox.aar`，解包后约 57 MB，由 [tools/build-libbox.sh](tools/build-libbox.sh) 从 sing-box 1.14.4 构建。需要别的 ABI 或其他协议特性时重新构建它。

## 致谢

- [Rex](https://rex.app) 由 [Superlogical](https://superlogical.com) 开发，是这个客户端连接的终端多路复用器；它的 RPC、会话模型和窗格归属是这整个应用存在的理由。
- [libghostty-android](https://github.com/SagerNet/libghostty-android) 由 [SagerNet](https://github.com/SagerNet) 提供，窗格绘制所依赖的 VT 绑定和终端组件都来自它。
- [Ghostty](https://ghostty.org)（[ghostty-org/ghostty](https://github.com/ghostty-org/ghostty)）由 Mitchell Hashimoto 及贡献者开发，是最底层的终端模拟器。
- [Material Symbols](https://github.com/google/material-design-icons)（Rounded），由 Google 以 Apache License 2.0 发布，是界面里的图标。
- [sing-box](https://github.com/SagerNet/sing-box) 以及它的 [libbox](https://github.com/SagerNet/sing-box/tree/dev/platform) Android 库，承载应用为 tailnet 主机运行的那个 Tailscale 节点。

## 语言

界面提供英文和简体中文。英文是默认（`values/`），中文在 `values-zh/`，所以英文设备开机是英文，中文设备开机是中文。Android 13 及以上支持按应用设置语言。

## 构建

需要 Android SDK 36 和 JDK 17。

```sh
./gradlew :core:test :app:assembleDebug
```

`app/libs/libbox.aar` 已随仓库提供（见 [Tailscale](#tailscale)），终端的模拟器 `libghostty-android` 从 Maven Central 取，所以克隆下来直接就能构建，不需要额外工具链。

调试包在 `app/build/outputs/apk/debug/app-debug.apk`；`:app:assembleRelease` 产出的包用调试密钥签名，供个人使用。两者都只有 arm64-v8a，应用 ID 是 `me.leaver.rexpocket`——手机上如果还装着旧的 `app.rexpocket`，它是另一个应用，有它自己的主机和 tailnet 登录。
