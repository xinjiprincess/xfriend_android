# Xfriend 电脑连接桥

## 原生运行 v2

桥将已配对的 `/mobile/v2/*` 映射到 Go 桌面账号代理的 `/api/mobile/v2/*`。GET 也必须携带精确的桥 Origin 和 `xfriend_mobile` Cookie。配对 POST 保留 303，原生客户端不要自动跟随。
手机 Authorization、Cookie 和 token/access_token 查询参数不会作为上游身份转发；Go 桌面代理注入本地 Octop JWT。能力由真实宿主 API 返回，旧版缺少接口时保留 HTTP 错误，不伪造 capabilities。
稳定合同在 `D:/octop/docs/mobile-runtime-v2-api.md`。桌面内置 Go 连接桥是独立实现，其所有者需要另外添加 v2 路径映射；修改本目录不会自动更新内置桥。

这是 Android 0.4 合并版“连接电脑”模式的配套进程。升级后的桥提供 v1 协议状态接口；电脑版暂时无需重新编译，先在电脑正常登录 Xfriend。电脑版内置同一协议后可直接替代这个独立进程。

## USB 连接步骤

Windows 上可以在此目录手动运行 `./start-usb.ps1`，自动查找正在运行的 Xfriend 桌面端口并创建 USB 转发。下面是对应的手动步骤。脚本本轮仅检查语法，真实电脑联调仍待验收。

1. 手机安装 `Xfriend-Android-0.4.0-debug.apk`，点击登录页或侧栏的“连接电脑”，开启 USB 调试并连接电脑。
2. 在任务管理器或 PowerShell 找到 Xfriend 的本机监听端口：

```powershell
Get-Process Xfriend | ForEach-Object { Get-NetTCPConnection -State Listen -OwningProcess $_.Id }
```

3. 在此目录启动桥，使用上一步的 **Go 桌面端口**，不是 Python 服务端口：

```powershell
uv sync --frozen
uv run bridge.py --desktop http://127.0.0.1:电脑桌面端口
```

4. 另一个终端执行：

```powershell
adb reverse tcp:8765 tcp:8765
```

5. 手机输入 `http://127.0.0.1:8765` 和连接桥终端显示的配对码。手机先请求 `/mobile/status` 检查协议，再提交配对码；配对后显示电脑正在使用的原版页面。

顶栏“断开”返回连接配置页；“此手机”同时断开并切回手机本地模式。两个动作都请求 `/mobile/logout` 并清理本机网页 Cookie、缓存和页面存储。若网络断开导致远端撤销请求失败，桥内会话仍按最长 8 小时到期或由电脑关闭连接服务撤销。

不要把配对码发给不可信的人。停止连接桥进程即撤销全部会话。电脑切换账号或空间后重新配对；手机不能通过此桥切换/退出电脑云账号。

## 局域网或远程网络

```powershell
uv run bridge.py --desktop http://127.0.0.1:电脑桌面端口 --host 0.0.0.0 --port 8765 --cert D:/certs/fullchain.pem --key D:/certs/privkey.pem
```

在手机填写证书匹配的 `https://电脑域名:8765`。必须使用系统信任的证书；不提供“忽略证书错误”。防火墙、域名解析、VPN/路由由部署者配置，本脚本不自动开放公网端口。家庭远程网络建议经过自己的 VPN，桥自身仍使用 HTTPS。

## 功能与边界

同一套电脑版页面及 HTTP/WebSocket 协议使专家、团队、聊天、技能包、知识库直接使用电脑数据。原版工作台/远程桌面/终端功能依赖电脑上已安装、已启用的后端能力。它不是 Windows 系统远程桌面的替代品，不会自动启用未安装的控制组件。

支持手机文件选择器上传资料。当前未额外实现 Android 摄像头/麦克风权限、后台常驻、系统下载管理器或第三方 OAuth 弹窗；依赖这些能力的页面需单独适配。

连接会话最长 8 小时，重启桥立即失效。当前不是离线双向数据合并，不会复制手机本地版的独立知识库或会话到电脑。

测试：`uv run pytest -q`。

## 0.4 资源导入

配对后新增 GET /mobile/export?include_secrets=0|1，必须携带精确 Origin 和配对 Cookie。优先转发电脑版 /api/mobile/export；旧版不存在该接口时仅兼容专家提示词、专家随附技能和 HTTPS 模型配置，并返回范围警告。完整团队、独立技能和知识库需电脑版配合。密钥默认过滤，显式包含时手机仍需第二次确认；不转发云账号 token、本机 Bearer 或其他秘密字段。详情与 Schema 见 ../docs/DESKTOP-INTEGRATION-0.4.html。
