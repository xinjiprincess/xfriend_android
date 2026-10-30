# @local/dsh-workspace-files

DSH GUI 的**工作区文件浏览器**：在左侧栏多一个「工作区文件」图标，点开后是当前会话工作区的文件列表。

- **单击文件** → 用系统默认程序打开（和资源管理器里双击一样）。
- **单击目录** → 进入目录；面包屑、返回/前进、上一级、过滤框都在顶部。
- **右键菜单**（文件）：打开（默认程序）、选择打开方式…、在资源管理器中显示、复制路径、复制相对路径、复制文件名。
- **右键菜单**（目录）：进入目录、用默认程序打开、选择打开方式…、在资源管理器中显示、复制路径、复制相对路径、复制名称。
- **右键空白处**：刷新、复制当前目录路径、在资源管理器中打开。
- 多会话时左上角出现下拉框，可在不同会话的工作区之间切换；最后浏览的目录会记住。

## 设计要点

这个插件**只有 Client 半边**，不需要自带 HTTP 路由，也不需要在宿主重启后生效。它调用部署里已经挂好的官方 Host Remote：

| 用途 | Remote |
| --- | --- |
| 取会话与工作区根目录 | `remote.session.list` → `SessionSummary.cwd` |
| 列出一层目录 | `remote.workspaceFiles.list({ sessionId, workspaceRoot }, path)` |
| 打开 / 定位 | `remote.session.openWorkspacePath({ path })`、`{ path, action: 'reveal' }` |
| 选择打开方式 | `remote.session.workspacePathApplications({ path })` |

因此「允许打开哪些路径」由 Host 的 Session Controller 判定（它要求路径能被 composed filesystem 映射），插件自己不会 spawn 任何进程，也不会越出工作区。

`index.js`（Host 半边）故意是空的：它只用来让这个包成为合法的 bundle 行。

## 安装

```powershell
# 在 DSH 里：plugin_manager install_bundle  target = 本目录绝对路径
```

安装后**刷新一次页面**，左侧栏就会出现文件夹图标。

## 开发

```powershell
node test\smoke.cjs     # 无浏览器的冒烟测试：模块契约、路径工具、slot 注册、首屏/正常/空/错误渲染
```

改动 `client.js` 后刷新页面即可生效（包是指向本目录的 junction，无需重装）。
