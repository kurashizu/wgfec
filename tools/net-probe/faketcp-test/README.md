# faketcp 对比测试(懒人包)

测的是:从你这台电脑到测试服务器,下面四种方式的丢包和延迟哪种最好。

| 方式 | 说明 |
|---|---|
| 直连 UDP | 什么都不加 |
| FEC | UDPspeeder 纠错 |
| faketcp | udp2raw 把 UDP 伪装成 TCP |
| FEC + faketcp | 两者叠加 |

## 你需要
- Python 3.6 或更新(`python3 --version`)。
- **faketcp 两项需要管理员权限**:Linux/macOS 用 `sudo`,Windows 用"以管理员身份运行"的终端,并先装 [Npcap](https://npcap.com)。没有管理员权限也能跑,只是 faketcp 两项会被跳过。
- 测试时**先关掉 WireGuard 和正在运行的 speederv2 / udp2raw**,不然测到的是隧道。
- 约 8 分钟,期间不要下载、看视频、玩游戏。

## 运行
1. 先查你的公网 IP,**发给服务器的主人,等他回复"已开放"**(服务器只对你的 IP 开放一段时间):
   ```
   python3 compare.py --ip
   ```
2. 开始测试:
   - Linux / macOS:`sudo python3 compare.py`
   - Windows(管理员 PowerShell):`python compare.py`
   - 想先 2 分钟试跑:加 `--quick`
3. 结束后把 **`report.txt` 和 `results.json`** 发回去(只有这两个文件,里面没有账号或密钥)。

## 说明
- 脚本只连 `config.json` 里写的那台服务器,不安装任何东西,不改系统设置、路由或防火墙。Linux 上 udp2raw 会临时加一条 iptables 规则,脚本结束时自己删掉。
- 带的程序在 `bin/` 里,是上游官方发布的原版(udp2raw 20230206.0、UDPspeeder 20230206.0)。
- Windows / macOS 的 faketcp 没在真机上测过。Windows 启动失败时试 `python compare.py --wepoll`;macOS 提示"无法验证开发者"时在本目录运行 `xattr -dr com.apple.quarantine bin`;macOS 需要 libpcap(`brew install libpcap`)。
- 报"服务器没有回应":说明测试窗口已关闭或还没对你的 IP 开放,把屏幕上打印的 IP 告诉服务器主人。
- 报"本地端口被占用":之前有残留的 udp2raw / speederv2,先停掉再试。
