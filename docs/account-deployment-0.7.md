# sync-20261013-1 生产交付记录

记录日期：2026-10-13。证据来源：用户转述父任务的实际部署与验收结果。
本地打包任务未 SSH，未独立复查远端；以下区分远端执行结果与本地验证。

## 已报告的生产状态

| 项目 | 结果 |
|---|---|
| 服务器 | `43.136.175.19`；主机指纹此前已由用户控制台确认 |
| 容器 | `xfriendmanager-manager-1` |
| 项目目录 | `/opt/xfriendmanager` |
| release | `/opt/xfriendmanager/releases/sync-20261013-1` |
| 新镜像 ID | `sha256:6805e0e48d2bd282337c395aeb50ad8d5724ef445fba2c91e091081a8e85a9bd` |
| 旧镜像 | 用户提供 ID 前缀 `218825...`；完整 ID 未在本地交付消息中提供，不补写猜测值 |
| 回滚标签 | `xfriend-manager:before-sync-20261013-1` 已保存旧镜像 |
| Compose | 生产默认 image 已改为新版；不再依赖仅临时 override 才选中新版 |
| 冷备 | release 下 `production-backup/data.tar`，父任务报告 `tar --compare` 通过 |
| 配置备份 | 原 Compose 与 Nginx 片段已保存；具体备份文件名未提供 |
| 原私钥 | 升级前后 SHA 比较一致；不记录私钥内容或其具体哈希 |

`data.tar` 是父任务执行的停机冷备，不等同于本应用 CLI 生成的
`backup-manifest.json` 格式。`tar --compare` 通过证明冷备与当时源文件一致，
本记录不将其表述为 CLI `verify-backup` 或恢复演练通过。

## 验收范围

- 生产 `/ready` 返回 HTTP 200。
- 生产未携带 token 的 sync pull 返回 HTTP 401。
- Nginx `-t` 和 reload 均通过。
- 隔离容器中使用模拟注册进行了 push、pull、媒体 E2E 验收，报告通过，共 43 routes。
- **未向生产写入测试用户或用户业务数据**；隔离验收不冒充生产账号读写验收。
- 本地此前完整 `uv run pytest -q`：77 项通过；Ruff/格式检查通过。
  功能相关 6 个模块 mypy 通过；全项目原有 `server_licenses.py` 6 个类型错误未改。

## 不变的运行交付物

业务包版本仍为 `0.2.2`，运行依赖未改。已上传并用于升级的 wheel：

```text
xfriend_activation-0.2.2-py3-none-any.whl
SHA256 269f993e6ee8e6d409386e3e19257e0053306677b580948fee16592a2e5f7d73
```

本地位置：`dist/sync-20261013-1/xfriend_activation-0.2.2-py3-none-any.whl`。
本轮只更新交付文档与源码打包器，不重建、不替换该 wheel，不修改运行代码。

升级前源码归档保留不动：

```text
xfriend-activation-sync-20261013-1-source.tar
SHA256 1347ddad609c5963f722f5a77c6fee88e31fcb14c103bfd6878e84c3dd48c7cb
```

包含本记录的最终归档另命名为
`dist/xfriend-activation-sync-20261013-1-source-delivery.tar`，SHA256 见同名
`.sha256` 文件及最终交付回复；包内含逐文件 `SOURCE-MANIFEST.json`。
不能用新归档冒充旧哈希的原包。两份源码包均不包含生产备份、`.env`、数据、私钥或凭据。

## 后续操作与回滚依据

生产默认 Compose 已选中新镜像；源码中的 `compose.yaml` 是通用旧版模板，
不得覆盖现有生产 Compose。`SYNC-RELEASE-COMMANDS.md` 保留升级前参考流程，
其中“默认 Compose 仍是旧镜像”和临时 override 的描述不是当前生产事实，不能直接重跑。

若需回滚，应由父任务先进入维护窗口并保存故障时数据快照，核对已保存旧镜像完整 ID，
将生产 image 切到 `xfriend-manager:before-sync-20261013-1`，保持原数据目录和原私钥。
不要仅恢复旧 Compose 而未核对其所有绑定/配置；不要删除新增同步表或回退用户记录。

`production-backup/data.tar` 仅在确需数据恢复且已评估升级后真实写入时使用。
它不能传给应用 CLI `restore --backup-dir`；应先校验备份和路径安全性，在全新受限目录
恢复、验证，再另行批准切换。禁止直接解压覆盖运行 data 目录。
原配置片段的回退仍需 `nginx -t` 通过后按实际服务方式 reload。

精确 API 契约见 [SYNC-API.md](../SYNC-API.md)；通用恢复约束见
[SYNC-UPGRADE.md](SYNC-UPGRADE.md)。本地打包任务未新增任何远端操作。
