# Xfriend 五次试用网关 0.7

单进程 aiohttp 服务，固定 MiMo 上游，用已有云账号的 Ed25519 签名租约认证。同一 user_id 跨设备与空间共享五次成功任务；失败退款，稳定 request_id 防重复扣次。团队一次分析包含转写/识图、专家分析和汇总；TTS 单独计次。

## 持久复盘 v2（本地实现，未部署）

Android 集成协议：[`review-protocol.json`](review-protocol.json)。v1 的任务、设计师调用和 TTS 接口保留，和 v2 共用原来的五次账号额度账本；不得为新版另建空数据库。

0.7 发布入口：`release/DEPLOYMENT-0.7.txt`。113 主机 hostkey 尚未核验，未部署。

父代理部署交接：[`deployment-checklist.txt`](deployment-checklist.txt)，包含依赖、现有 venv/Certbot 共用环境注意事项、systemd/env、Nginx 大文件上传/SSE、验收和回滚边界。本轮未执行部署。

客户端顺序调用：

1. `POST /v2/reviews` 创建稳定 `request_id`、专家职责和有序 `sources` 清单。
2. `PUT /v2/reviews/{id}/uploads/{source_id}` 上传原始字节，支持 UTF-8 文本、PCM WAV、JPEG/PNG；不使用 base64 或 multipart。图片可以批量列入清单，`asset` 和 `[x,y,width,height]` 的 `region` 用于引用定位。
3. `POST /v2/reviews/{id}/start` 检查上传齐全并预留一次额度。
4. `GET /v2/reviews/{id}` 每两秒轮询，或 `GET .../events` 使用 `Last-Event-ID` / `after` 恢复编号 SSE。SSE 最多连接 120 秒，断开后重连。每个事件是完整状态快照，客户端覆盖保存，不重复追加。
5. `POST .../cancel` 取消；`POST .../resume` 从失败或取消的检查点重试。成功后的 start/resume/cancel 都返回原成功结果，不退款。

进度只显示 `percent`，不要显示内部片段、团队轮次或重试。`partial_report` 是持久化的未完成报告快照：生产上游的 SSE 摘要增量至多每半秒保存一次，完整分析校验后追加事实和引用，合并或重试可能修订内容。`result` 在成功前一直是 null；不能根据部分报告写入画像或行动。提交成功结果和扣次位于同一 SQLite 事务，只有提交后才返回 100%。最终结果同时提供结构化 `report`、带引用的 `reportText`、`rawTranscript`、`correctedText`、`analysisJson` 和 `sourceRanges`。

整个复盘累计音频最长 **60 分钟**，以服务端解析 PCM WAV 后的实际样本数/采样率核验，不接受客户端 duration。建议上传完整 16kHz 单声道 WAV（60 分钟约 115.2 MB）。上传音频单文件上限 128 MiB，图片 10 MiB，文本 2 MiB，最多 100 个源。客户端若自行拆分并重叠，重叠也计入累计源时长；推荐让网关切块。

网关 ASR 窗口最长 90 秒、相邻重叠 2 秒；`finish_reason=length` 会保存拆分计划并递归缩短窗口重试。图片 OCR 截断会递归分割图片。只有完整 stop 响应可存为成功检查点；缺失 finish_reason、过滤、空结果或仍然无法完整识别的最小片段都会使任务保留检查点并最终进入可重试失败，释放额度，绝不把截断内容当作完整结果。原始转写全部保留，边界修正保守地去除逐字重复，不让模型改写原文证据。所有文本块和所有图片都会参与专家分析，分层汇总不再静默丢弃 12,000 字后的内容。

证据 `quote` 由网关从保存的文本/ASR/OCR 原文逐字复制，结构化模型引用必须命中已存在的 evidence ID。`validated=true` 只保证这一来源关系，不能保证识别准确或分析判断正确。音频时间是源文件内保守的块范围，不是强制对齐的逐字时间。画像和行动只是带证据的建议，不会自动修改其他系统。

SQLite 使用 WAL；任务、预留、事件、加密检查点和结果都持久化。后台循环无外部队列，每次 claim 带独立 fencing token；租约 60 秒、每 15 秒续租。旧工作者在取消、恢复或租约被接管后不能提交。重启保留 v2 预留额度，过期租约自动恢复；正常关闭主动释放工作者租约。自动执行最多三次后进入 failed，用户可 resume。所有内部调用、团队轮次和重试只使用同一个预留，账本使用 canonical user_id，因此换租约/设备/空间不能重置额度。

原始上传加密保存在数据库旁的 `<db-path>.media/`，终态 24 小时后由每 30 秒运行的清理循环删除；离线期间无法清理，重启后补做。未启动草稿 24 小时过期。最终结果、转写证据、事件和额度账本长期保留，便于回放与防重复扣费；这不是全量数据删除接口。保留原数据库和 `.cache-key`，并让服务账户独占数据库及媒体目录。只支持一个网关服务进程，共享盘多进程部署不在此实现范围内。

默认仍用 `X-Xfriend-Lease` 和现有签名验证器。可选新会话通过 `XFRIEND_SESSION_INTROSPECTION_URL` 配置受信任 HTTPS 内省端点：同时配置仅服务端保存的 `XFRIEND_GATEWAY_INTROSPECTION_SECRET`（与激活服务一致，32..200字符无空白ASCII）。网关 POST `{"token":"客户端访问token"}`，携带 `Authorization: Bearer <服务secret>`；对端必须返回 `{ "active": true, "user_id": "与租约相同的账号ID" }`。接口为激活服务 `/api/v2/auth/introspect`，公网前缀由部署配置确定。禁用跳转，不输出上游错误或令牌；缺少服务secret或服务凭据被拒返回503，不影响租约认证。未配置时不接受新 bearer；同时携带两种凭证时账号必须一致。此实现没有修改或新增激活服务。

验证（合成媒体、签名和上游，无真实凭据）：`uv run pytest -q`。新增依赖 Pillow 用于校验与拆分图片，`uv.lock` 已同步；运行环境需要 Python 3.12+，无需 ffmpeg 或外部任务队列。

## 本地运行

```sh
uv sync --frozen --no-dev
# 从系统 Secret 注入 MIMO_API_KEY；不要写进代码、APK 或命令行参数。
export XFRIEND_TRIAL_DB=/persistent/xfriend/trial.sqlite
uv run --no-sync python gateway.py
```

默认监听 127.0.0.1:8787，仅允许受信任 HTTPS 反向代理对外提供服务。一个进程独占数据库。升级时保留数据库、WAL 及相邻缓存密钥，不要初始化新账本。备份需使用 SQLite backup API，一并保存 trial.cache-key。

## 当前服务器部署

服务目录 `/opt/xfriend-trial`，非 root 用户 `xfriend-trial`，单元 `xfriend-trial.service`。共享密钥位于 `/etc/xfriend-trial/gateway.env`（root:root 0600），账本 `/var/lib/xfriend-trial/trial.sqlite`。交付包不包含这些密钥或数据库。

手机版预置 `https://113.219.170.192`。受信任证书必须先签发；公网 TCP 80/443 需实际可达。服务器已准备 `activate-https.sh` 和 `nginx.conf`。端口可达后以 root 执行：

```sh
bash /opt/xfriend-trial/activate-https.sh
systemctl is-active xfriend-trial
systemctl list-timers xfriend-trial-cert-renew.timer
curl --fail https://113.219.170.192/health
```

脚本使用 Certbot 5.4+ 签发 Let's Encrypt shortlived IP 证书，设置每天两次检查续期与 Nginx reload。证书只有六天有效期，续期失败需处理，不能关闭 TLS 验证。签发前不会用自签证书或明文传输租约/客户数据。

## API

- `GET /health`：匿名健康检查。
- `GET /v1/quota`：`X-Xfriend-Lease`，返回 remaining/limit。
- `POST /v1/tasks`：同一请求头；operation analyze/speak，带 request_id、kind、text/media、prompt、roles。
- 状态：401 授权失效、402 额度用完、409 重试冲突、502 上游失败且退款。

租约是 Bearer 凭据，不记录请求访问日志。结果加密缓存用于重试，启动时清理 24 小时前缓存；额度记录长期保留。当前不查询账号即时撤销，最长按 72 小时离线租约验证。订阅仅预留手机入口，支付和服务端权益尚未实现。

测试使用合成签名和上游，不需要共享密钥：`uv run pytest test_gateway.py -q`。部署实测结果见 `../docs/DEPLOYMENT-0.4.html`。
