/**
 * Workspace files — Client half.
 *
 * A file browser for the Session workspace: one click opens a file with the
 * operating system's default application, and the context menu copies paths,
 * reveals entries in the file manager, or opens a file with a chosen handler.
 *
 * Everything is served by Host Remotes that this deployment already mounts, so
 * the plugin needs no route of its own:
 *   - `remote.session.list`            → Session workspace roots (`cwd`)
 *   - `remote.workspaceFiles.list`     → one directory level with file types
 *   - `remote.session.openWorkspacePath`        → open / reveal a path
 *   - `remote.session.workspacePathApplications` → registered handlers ("open with")
 */
window.__ModuleLoader__.load({
  id: '@local/dsh-workspace-files',
  factory: (require) => {
    const React = require('react')
    const module = { exports: {} }
    const exports = module.exports

    const h = React.createElement
    const { useCallback, useEffect, useMemo, useRef, useState } = React

    const PANEL_ID = 'workspace-files'
    const ROOT_KEY = 'dsh.workspace-files.root'
    const DIR_KEY = 'dsh.workspace-files.dir'

    /* ------------------------------------------------------------------ */
    /* path helpers (Host paths arrive in native syntax, usually Windows)  */
    /* ------------------------------------------------------------------ */

    const isSep = (ch) => ch === '\\' || ch === '/'
    const sepOf = (value) => (value.includes('\\') ? '\\' : '/')
    const trimEnd = (value) => {
      let out = value
      while (out.length > 1 && isSep(out[out.length - 1])) out = out.slice(0, -1)
      return out
    }
    const lastBreak = (value) => Math.max(value.lastIndexOf('\\'), value.lastIndexOf('/'))

    const joinPath = (dir, name) => {
      const base = trimEnd(dir)
      return isSep(base[base.length - 1]) ? base + name : base + sepOf(base) + name
    }

    const parentPath = (value) => {
      const trimmed = trimEnd(value)
      const idx = lastBreak(trimmed)
      if (idx <= 0) return trimmed
      const next = trimmed.slice(0, idx)
      return next.endsWith(':') ? next + sepOf(next) : next
    }

    const baseName = (value) => {
      const trimmed = trimEnd(value)
      const idx = lastBreak(trimmed)
      return idx === -1 ? trimmed : trimmed.slice(idx + 1)
    }

    const relativeTo = (root, path) => {
      const base = trimEnd(root)
      const target = trimEnd(path)
      if (target === base) return ''
      if (target.toLowerCase().startsWith(base.toLowerCase() + sepOf(base))) {
        return target.slice(base.length + 1)
      }
      return target
    }

    const isInside = (root, path) => {
      const base = trimEnd(root).toLowerCase()
      const target = trimEnd(path).toLowerCase()
      if (target === base) return true
      return target.startsWith(base + (base.includes('\\') ? '\\' : '/'))
    }

    const segmentsOf = (root, dir) => {
      const rel = relativeTo(root, dir)
      if (rel === '' || rel === dir) return []
      return rel.split(/[\\/]+/).filter(Boolean)
    }

    const formatSize = (bytes) => {
      if (typeof bytes !== 'number' || !Number.isFinite(bytes)) return ''
      if (bytes < 1024) return `${bytes} B`
      const units = ['KB', 'MB', 'GB', 'TB']
      let value = bytes / 1024
      let unit = 0
      while (value >= 1024 && unit < units.length - 1) {
        value /= 1024
        unit += 1
      }
      return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`
    }

    const readJson = (key) => {
      try {
        const raw = window.localStorage.getItem(key)
        return raw === null ? undefined : JSON.parse(raw)
      } catch {
        return undefined
      }
    }

    const writeJson = (key, value) => {
      try {
        window.localStorage.setItem(key, JSON.stringify(value))
      } catch {
        /* storage may be unavailable; the browser keeps working without it */
      }
    }

    const storeText = (key, value) => {
      try {
        window.localStorage.setItem(key, value)
      } catch {
        /* ignore */
      }
    }

    const loadText = (key) => {
      try {
        return window.localStorage.getItem(key)
      } catch {
        return null
      }
    }

    const errorText = (error) => {
      if (error === undefined || error === null) return '未知错误'
      if (typeof error === 'string') return error
      if (typeof error.message === 'string' && error.message !== '') return error.message
      if (typeof error.code === 'string' && error.code !== '') return error.code
      return String(error)
    }

    /** Clipboard first, then the legacy selection trick the shell still honours. */
    async function copyText(text) {
      try {
        if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
          await navigator.clipboard.writeText(text)
          return true
        }
      } catch {
        /* fall through */
      }
      try {
        const area = document.createElement('textarea')
        area.value = text
        area.setAttribute('readonly', '')
        area.style.position = 'fixed'
        area.style.opacity = '0'
        document.body.appendChild(area)
        area.select()
        const ok = document.execCommand('copy')
        area.remove()
        return ok
      } catch {
        return false
      }
    }

    /* ------------------------------------------------------------------ */
    /* Host Remote adapter                                                 */
    /* ------------------------------------------------------------------ */

    function createApi(ctx) {
      const readRemote = () => {
        try {
          return ctx.get('remote')
        } catch {
          return undefined
        }
      }

      /** Remote calls answer `{ ok, value } | { ok: false, error }`. */
      const unwrap = (result, what) => {
        if (result !== null && typeof result === 'object' && 'ok' in result) {
          if (result.ok) return result.value
          throw new Error(errorText(result.error) || `${what}失败`)
        }
        return result
      }

      const sessionRemote = () => {
        try {
          const remote = readRemote()
          return remote !== undefined && remote !== null ? remote.session : undefined
        } catch {
          return undefined
        }
      }

      const filesRemote = () => {
        try {
          const remote = readRemote()
          return remote !== undefined && remote !== null ? remote.workspaceFiles : undefined
        } catch {
          return undefined
        }
      }

      return {
        available() {
          const session = sessionRemote()
          const files = filesRemote()
          return Boolean(
            session &&
              typeof session.openWorkspacePath === 'function' &&
              files &&
              typeof files.list === 'function',
          )
        },

        /** Every Session that carries a workspace root, newest first. */
        async sessions() {
          const session = sessionRemote()
          if (session === undefined || typeof session.list !== 'function') return []
          const value = unwrap(await session.list({}), '读取会话')
          const items = Array.isArray(value && value.items) ? value.items : []
          return items
            .filter((item) => item && typeof item.cwd === 'string' && item.cwd !== '')
            .sort((left, right) => (right.updatedAt || 0) - (left.updatedAt || 0))
        },

        /**
         * One directory level. The Client Remote takes the Session id and an
         * absolute path; the workspace root rides along as a wire header
         * derived from that Session identity, so no scope object is passed.
         */
        async list(root, dir, signal) {
          const files = filesRemote()
          if (files === undefined) throw new Error('当前部署没有工作区文件服务')
          return unwrap(await files.list(root.sessionId, dir, signal), '列出目录')
        },

        async open(path) {
          const session = sessionRemote()
          if (session === undefined) throw new Error('当前部署不支持在桌面打开文件')
          return unwrap(await session.openWorkspacePath({ path }), '打开文件')
        },

        async reveal(path) {
          const session = sessionRemote()
          if (session === undefined) throw new Error('当前部署不支持在文件管理器中显示')
          return unwrap(await session.openWorkspacePath({ path, action: 'reveal' }), '定位文件')
        },

        async openWith(path, application) {
          const session = sessionRemote()
          if (session === undefined) throw new Error('当前部署不支持在桌面打开文件')
          return unwrap(await session.openWorkspacePath({ path, application }), '打开文件')
        },

        async applications(path) {
          const session = sessionRemote()
          if (session === undefined || typeof session.workspacePathApplications !== 'function') return []
          const value = unwrap(await session.workspacePathApplications({ path }), '读取打开方式')
          return Array.isArray(value) ? value : []
        },
      }
    }

    /* ------------------------------------------------------------------ */
    /* Styles                                                              */
    /* ------------------------------------------------------------------ */

    const CSS = `
.wsf-root {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  width: 100%;
  font-size: 13px;
  color: var(--dsw-alias-label-primary, #1f2329);
  background: var(--dsw-alias-bg-base, transparent);
}
.wsf-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px 6px;
  flex: none;
}
.wsf-title {
  font-weight: 600;
  font-size: 13px;
  margin-right: auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wsf-rootselect {
  max-width: 190px;
  flex: none;
  font: inherit;
  font-size: 12px;
  color: var(--dsw-alias-label-secondary, #61666d);
  background: var(--dsw-alias-bg-elevated, rgba(0, 0, 0, 0.03));
  border: 1px solid var(--dsw-alias-border-l4, rgba(0, 0, 0, 0.1));
  border-radius: 6px;
  padding: 2px 4px;
}
.wsf-btn {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  height: 24px;
  padding: 0 6px;
  font: inherit;
  font-size: 12px;
  color: var(--dsw-alias-label-secondary, #61666d);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
}
.wsf-btn:hover:not(:disabled) {
  background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, 0.06));
  color: var(--dsw-alias-label-primary, #1f2329);
}
.wsf-btn:disabled { opacity: 0.4; cursor: default; }
.wsf-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 10px 6px;
  flex: none;
}
.wsf-crumbs {
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
  flex: 1;
  overflow: hidden;
  white-space: nowrap;
  font-size: 12px;
  color: var(--dsw-alias-label-caption, #8a9099);
}
.wsf-crumb {
  background: transparent;
  border: none;
  padding: 2px 4px;
  font: inherit;
  color: inherit;
  border-radius: 4px;
  cursor: pointer;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.wsf-crumb:hover { background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, 0.06)); }
.wsf-crumb[data-last="true"] { color: var(--dsw-alias-label-primary, #1f2329); }
.wsf-filter {
  flex: none;
  width: 130px;
  font: inherit;
  font-size: 12px;
  padding: 3px 6px;
  border-radius: 6px;
  border: 1px solid var(--dsw-alias-border-l4, rgba(0, 0, 0, 0.12));
  background: transparent;
  color: inherit;
}
.wsf-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 0 6px 10px;
}
.wsf-row {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 26px;
  padding: 0 8px;
  border-radius: 6px;
  cursor: pointer;
  user-select: none;
}
.wsf-row:hover { background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, 0.06)); }
.wsf-icon {
  flex: none;
  width: 16px;
  text-align: center;
  font-size: 12px;
  color: var(--dsw-alias-label-caption, #8a9099);
}
.wsf-row[data-dir="true"] .wsf-icon { color: var(--dsw-alias-state-business-primary, #4d6bfe); }
.wsf-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wsf-meta {
  flex: none;
  font-size: 11px;
  color: var(--dsw-alias-label-caption, #8a9099);
  font-variant-numeric: tabular-nums;
}
.wsf-note {
  padding: 14px 12px;
  color: var(--dsw-alias-label-caption, #8a9099);
  font-size: 12px;
  line-height: 1.6;
}
.wsf-note[data-tone="error"] { color: var(--dsw-alias-state-error-primary, #d92d20); }
.wsf-menu {
  position: fixed;
  z-index: 60;
  min-width: 178px;
  max-width: 320px;
  padding: 4px;
  border-radius: 8px;
  background: var(--dsw-alias-bg-elevated, #ffffff);
  border: 1px solid var(--dsw-alias-border-l4, rgba(0, 0, 0, 0.12));
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.16);
  font-size: 12.5px;
}
.wsf-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 8px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.wsf-item:hover { background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, 0.06)); }
.wsf-item[data-tone="danger"] { color: var(--dsw-alias-state-error-primary, #d92d20); }
.wsf-sep { height: 1px; margin: 4px 6px; background: var(--dsw-alias-border-l4, rgba(0, 0, 0, 0.08)); }
.wsf-label {
  padding: 4px 8px 2px;
  font-size: 11px;
  color: var(--dsw-alias-label-caption, #8a9099);
}
.wsf-toast {
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: 12px;
  padding: 6px 10px;
  border-radius: 8px;
  font-size: 12px;
  background: var(--dsw-alias-bg-elevated, #ffffff);
  border: 1px solid var(--dsw-alias-border-l4, rgba(0, 0, 0, 0.12));
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.14);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wsf-toast[data-tone="error"] { color: var(--dsw-alias-state-error-primary, #d92d20); }
`

    /* ------------------------------------------------------------------ */
    /* Sidebar icon                                                        */
    /* ------------------------------------------------------------------ */

    function FolderIcon(props) {
      const size = typeof props.size === 'number' && props.size > 0 ? props.size : 16
      return h(
        'svg',
        {
          width: size,
          height: size,
          viewBox: '0 0 16 16',
          fill: 'none',
          stroke: 'currentColor',
          strokeWidth: 1.4,
          strokeLinecap: 'round',
          strokeLinejoin: 'round',
          'aria-hidden': 'true',
        },
        h('path', { d: 'M1.75 4.25c0-.55.45-1 1-1h3.1c.27 0 .52.11.7.3l.9.95h5.8c.55 0 1 .45 1 1v6.25c0 .55-.45 1-1 1H2.75c-.55 0-1-.45-1-1z' }),
        h('path', { d: 'M1.9 6.4h12.2' }),
      )
    }

    /* ------------------------------------------------------------------ */
    /* Context menu                                                        */
    /* ------------------------------------------------------------------ */

    function Menu(props) {
      const { state, onClose, onAction, apps } = props
      const ref = useRef(null)
      const [position, setPosition] = useState({ left: state.x, top: state.y })

      // Keep the menu inside the viewport once its real size is known.
      useEffect(() => {
        const node = ref.current
        if (node === null) return
        const rect = node.getBoundingClientRect()
        const left = Math.max(6, Math.min(state.x, window.innerWidth - rect.width - 6))
        const top = Math.max(6, Math.min(state.y, window.innerHeight - rect.height - 6))
        setPosition({ left, top })
      }, [state.x, state.y, state.mode, apps])

      useEffect(() => {
        const onDown = (event) => {
          if (ref.current !== null && !ref.current.contains(event.target)) onClose()
        }
        const onKey = (event) => {
          if (event.key === 'Escape') onClose()
        }
        const onScroll = () => onClose()
        window.addEventListener('mousedown', onDown, true)
        window.addEventListener('keydown', onKey, true)
        window.addEventListener('resize', onScroll)
        return () => {
          window.removeEventListener('mousedown', onDown, true)
          window.removeEventListener('keydown', onKey, true)
          window.removeEventListener('resize', onScroll)
        }
      }, [onClose])

      const items = []
      const entry = state.entry
      const isDir = entry !== null && entry.type === 'directory'

      if (state.mode === 'apps') {
        items.push(h('div', { key: 'label', className: 'wsf-label' }, '选择打开方式'))
        items.push(
          h(
            'button',
            { key: 'default', className: 'wsf-item', onClick: () => onAction('open', { application: undefined }) },
            h('span', null, '默认程序'),
          ),
        )
        if (apps === null) {
          items.push(h('div', { key: 'loading', className: 'wsf-label' }, '正在读取已注册的程序…'))
        } else if (apps.length === 0) {
          items.push(h('div', { key: 'none', className: 'wsf-label' }, '系统没有返回已注册的程序'))
        } else {
          for (const app of apps) {
            const id = app && typeof app.id === 'string' ? app.id : undefined
            if (id === undefined) continue
            const name = typeof app.name === 'string' && app.name !== '' ? app.name : id
            items.push(
              h(
                'button',
                {
                  key: id,
                  className: 'wsf-item',
                  title: id,
                  onClick: () => onAction('open', { application: id }),
                },
                h('span', null, name),
                app.default === true ? h('span', { className: 'wsf-meta' }, '默认') : null,
              ),
            )
          }
        }
        items.push(h('div', { key: 'sep', className: 'wsf-sep' }))
        items.push(
          h('button', { key: 'back', className: 'wsf-item', onClick: () => onAction('back') }, '返回'),
        )
        return h('div', { ref, className: 'wsf-menu', style: position, role: 'menu' }, items)
      }

      if (entry === null) {
        items.push(
          h('button', { key: 'refresh', className: 'wsf-item', onClick: () => onAction('refresh') }, '刷新'),
          h('div', { key: 'sep1', className: 'wsf-sep' }),
          h('button', { key: 'copy', className: 'wsf-item', onClick: () => onAction('copy-dir') }, '复制当前目录路径'),
          h('button', { key: 'reveal', className: 'wsf-item', onClick: () => onAction('reveal-dir') }, '在资源管理器中打开'),
        )
        return h('div', { ref, className: 'wsf-menu', style: position, role: 'menu' }, items)
      }

      if (isDir) {
        items.push(
          h('button', { key: 'enter', className: 'wsf-item', onClick: () => onAction('enter') }, '进入目录'),
          h('button', { key: 'open', className: 'wsf-item', onClick: () => onAction('open') }, '用默认程序打开'),
          h('button', { key: 'apps', className: 'wsf-item', onClick: () => onAction('apps') }, '选择打开方式…'),
          h('button', { key: 'reveal', className: 'wsf-item', onClick: () => onAction('reveal') }, '在资源管理器中显示'),
          h('div', { key: 'sep', className: 'wsf-sep' }),
          h('button', { key: 'copy', className: 'wsf-item', onClick: () => onAction('copy') }, '复制路径'),
          h('button', { key: 'copyrel', className: 'wsf-item', onClick: () => onAction('copy-rel') }, '复制相对路径'),
          h('button', { key: 'copyname', className: 'wsf-item', onClick: () => onAction('copy-name') }, '复制名称'),
        )
      } else {
        items.push(
          h('button', { key: 'open', className: 'wsf-item', onClick: () => onAction('open') }, '打开（默认程序）'),
          h('button', { key: 'apps', className: 'wsf-item', onClick: () => onAction('apps') }, '选择打开方式…'),
          h('button', { key: 'reveal', className: 'wsf-item', onClick: () => onAction('reveal') }, '在资源管理器中显示'),
          h('div', { key: 'sep', className: 'wsf-sep' }),
          h('button', { key: 'copy', className: 'wsf-item', onClick: () => onAction('copy') }, '复制路径'),
          h('button', { key: 'copyrel', className: 'wsf-item', onClick: () => onAction('copy-rel') }, '复制相对路径'),
          h('button', { key: 'copyname', className: 'wsf-item', onClick: () => onAction('copy-name') }, '复制文件名'),
        )
      }
      return h('div', { ref, className: 'wsf-menu', style: position, role: 'menu' }, items)
    }

    /* ------------------------------------------------------------------ */
    /* File panel                                                          */
    /* ------------------------------------------------------------------ */

    function FilePanel(props) {
      const api = props.api
      const [roots, setRoots] = useState(null)
      const [root, setRoot] = useState(null)
      const [dir, setDir] = useState(null)
      const [listing, setListing] = useState(null)
      const [phase, setPhase] = useState('idle')
      const [failure, setFailure] = useState(null)
      const [filter, setFilter] = useState('')
      const [menu, setMenu] = useState(null)
      const [apps, setApps] = useState(null)
      const [toast, setToast] = useState(null)
      const history = useRef({ entries: [], index: -1 })
      const abort = useRef(null)
      const alive = useRef(true)
      const toastTimer = useRef(null)

      const notify = useCallback((text, tone) => {
        if (toastTimer.current !== null) window.clearTimeout(toastTimer.current)
        setToast({ text, tone: tone || 'info' })
        toastTimer.current = window.setTimeout(() => {
          if (alive.current) setToast(null)
        }, 2600)
      }, [])

      useEffect(
        () => () => {
          alive.current = false
          if (toastTimer.current !== null) window.clearTimeout(toastTimer.current)
          if (abort.current !== null) abort.current.abort()
        },
        [],
      )

      /* ---- boot: discover workspace roots, restore the last directory ---- */
      useEffect(() => {
        let live = true
        const run = async () => {
          if (api === undefined) {
            setFailure('插件没有拿到 Host 适配器')
            setPhase('error')
            return
          }
          if (!api.available()) {
            setFailure('当前部署没有挂载 Host Remote（session / workspaceFiles），无法浏览工作区文件。')
            setPhase('error')
            return
          }
          try {
            const items = await api.sessions()
            if (!live) return
            setRoots(items)
            if (items.length === 0) {
              setFailure('没有找到带工作区目录的会话。')
              setPhase('empty')
              return
            }
            const saved = readJson(ROOT_KEY)
            let chosen
            if (saved !== null && typeof saved === 'object') {
              chosen = items.find((item) => item.sessionId === saved.sessionId)
              if (chosen === undefined) chosen = items.find((item) => item.cwd === saved.cwd)
            }
            if (chosen === undefined) chosen = items[0]
            const next = { sessionId: chosen.sessionId, workspaceRoot: chosen.cwd }
            setRoot(next)
            const savedDir = loadText(DIR_KEY)
            setDir(
              typeof savedDir === 'string' && savedDir !== '' && isInside(next.workspaceRoot, savedDir)
                ? savedDir
                : next.workspaceRoot,
            )
          } catch (error) {
            if (!live) return
            setFailure(errorText(error))
            setPhase('error')
          }
        }
        void run()
        return () => {
          live = false
        }
      }, [api])

      /* ---- load one directory ---- */
      useEffect(() => {
        if (root === null || dir === null) return undefined
        let live = true
        if (abort.current !== null) abort.current.abort()
        const controller = new AbortController()
        abort.current = controller
        setPhase('loading')
        setFailure(null)
        const run = async () => {
          try {
            const value = await api.list(root, dir, controller.signal)
            if (!live) return
            const entries = Array.isArray(value && value.entries) ? value.entries : []
            setListing({ path: dir, entries, truncated: value !== null && value !== undefined && value.truncated === true })
            setPhase('ready')
            storeText(DIR_KEY, dir)
            writeJson(ROOT_KEY, root)
            const trail = history.current.entries
            if (trail[history.current.index] !== dir) {
              const kept = trail.slice(0, history.current.index + 1)
              kept.push(dir)
              history.current = { entries: kept, index: kept.length - 1 }
            }
          } catch (error) {
            if (!live) return
            if (error && (error.name === 'AbortError' || controller.signal.aborted)) return
            setFailure(errorText(error))
            setPhase('error')
          }
        }
        void run()
        return () => {
          live = false
          controller.abort()
        }
      }, [api, root, dir])

      const go = useCallback(
        (next) => {
          if (typeof next !== 'string' || next === '') return
          setDir(next)
          setFilter('')
          setMenu(null)
        },
        [],
      )

      const back = useCallback(() => {
        const state = history.current
        if (state.index <= 0) return
        state.index -= 1
        setDir(state.entries[state.index])
        setFilter('')
      }, [])

      const forward = useCallback(() => {
        const state = history.current
        if (state.index >= state.entries.length - 1) return
        state.index += 1
        setDir(state.entries[state.index])
        setFilter('')
      }, [])

      const refresh = useCallback(() => {
        if (dir === null) return
        const current = dir
        setDir(null)
        window.setTimeout(() => setDir(current), 0)
      }, [dir])

      const entryPath = useCallback(
        (entry) => {
          if (entry === null) return ''
          const base = typeof dir === 'string' ? dir : ''
          const name = typeof entry.name === 'string' ? entry.name : ''
          if (base === '' || name === '') return ''
          // The Host resolves mixed separators, so "/" is the stable join.
          return `${base.replace(/[\\/]+$/, '')}/${name}`
        },
        [dir],
      )

      const act = useCallback(
        async (kind, target) => {
          const entry = target === undefined ? null : target
          const path = entry === null ? dir : entryPath(entry)
          setMenu(null)
          if (path === null || path === undefined || path === '') return
          try {
            if (kind === 'open') {
              notify('正在打开…')
              await api.open(path)
              return
            }
            if (kind === 'reveal') {
              await api.reveal(path)
              return
            }
            if (kind === 'copy' || kind === 'copy-name' || kind === 'copy-rel') {
              let text = path
              if (kind === 'copy-name') text = entry !== null ? entry.name : baseName(path)
              if (kind === 'copy-rel' && root !== null) text = relativeTo(root.workspaceRoot, path)
              const ok = await copyText(text)
              notify(ok ? `已复制：${text}` : '复制失败，请手动选择文本', ok ? 'info' : 'error')
              return
            }
            if (kind === 'copy-dir') {
              const ok = await copyText(dir)
              notify(ok ? `已复制：${dir}` : '复制失败', ok ? 'info' : 'error')
              return
            }
            if (kind === 'reveal-dir') {
              await api.reveal(dir)
              return
            }
            if (kind === 'refresh') {
              refresh()
            }
          } catch (error) {
            notify(errorText(error), 'error')
          }
        },
        [api, dir, entryPath, listing, notify, refresh, root],
      )

      /* ---- menu actions ---- */
      const onMenuAction = useCallback(
        async (kind, options) => {
          if (menu === null) return
          const entry = menu.entry
          if (kind === 'back') {
            setMenu({ ...menu, mode: 'main' })
            return
          }
          if (kind === 'enter') {
            setMenu(null)
            if (entry !== null) go(entryPath(entry))
            return
          }
          if (kind === 'apps') {
            setApps(null)
            setMenu({ ...menu, mode: 'apps' })
            try {
              const path = entryPath(entry)
              const list = await api.applications(path)
              if (alive.current) setApps(list)
            } catch (error) {
              if (alive.current) {
                setApps([])
                notify(errorText(error), 'error')
              }
            }
            return
          }
          if (kind === 'open') {
            const application = options === undefined ? undefined : options.application
            const path = entryPath(entry)
            setMenu(null)
            try {
              if (application === undefined) await api.open(path)
              else await api.openWith(path, application)
            } catch (error) {
              notify(errorText(error), 'error')
            }
            return
          }
          void act(kind, entry === null ? null : entry)
        },
        [act, api, entryPath, go, menu, notify],
      )

      const rows = useMemo(() => {
        const entries = listing !== null && Array.isArray(listing.entries) ? listing.entries : []
        const needle = filter.trim().toLowerCase()
        const visible = needle === '' ? entries : entries.filter((entry) => String(entry.name || '').toLowerCase().includes(needle))
        const sorted = visible.slice().sort((left, right) => {
          const leftDir = left.type === 'directory'
          const rightDir = right.type === 'directory'
          if (leftDir !== rightDir) return leftDir ? -1 : 1
          return String(left.name || '').localeCompare(String(right.name || ''), 'zh-Hans-CN')
        })
        return sorted
      }, [listing, filter])

      const crumbs = useMemo(() => {
        if (root === null || dir === null) return []
        const parts = segmentsOf(root.workspaceRoot, dir)
        const nodes = [{ label: baseName(root.workspaceRoot) || root.workspaceRoot, path: root.workspaceRoot }]
        let walked = root.workspaceRoot
        for (const part of parts) {
          walked = joinPath(walked, part)
          nodes.push({ label: part, path: walked })
        }
        return nodes
      }, [root, dir])

      if (phase === 'error' || phase === 'empty') {
        return h(
          'div',
          { className: 'wsf-root' },
          h('div', { className: 'wsf-note', 'data-tone': 'error' }, failure || '无法浏览工作区文件。'),
        )
      }

      const canBack = history.current.index > 0
      const canForward = history.current.index < history.current.entries.length - 1
      const rootValue = root === null ? '' : root.sessionId

      return h(
        'div',
        {
          className: 'wsf-root',
          style: { position: 'relative' },
          onContextMenu: (event) => {
            event.preventDefault()
            setMenu({ x: event.clientX, y: event.clientY, entry: null, mode: 'main' })
          },
        },
        h(
          'div',
          { className: 'wsf-head' },
          h('span', { className: 'wsf-title' }, '工作区文件'),
          roots !== null && roots.length > 1
            ? h(
                'select',
                {
                  className: 'wsf-rootselect',
                  value: rootValue,
                  title: '切换会话工作区',
                  onChange: (event) => {
                    const picked = roots.find((item) => item.sessionId === event.target.value)
                    if (picked === undefined) return
                    const next = { sessionId: picked.sessionId, workspaceRoot: picked.cwd }
                    history.current = { entries: [], index: -1 }
                    setRoot(next)
                    setDir(picked.cwd)
                  },
                },
                roots.map((item) =>
                  h('option', { key: item.sessionId, value: item.sessionId }, item.cwd),
                ),
              )
            : null,
          h(
            'button',
            { className: 'wsf-btn', title: '刷新', onClick: refresh },
            '⟳',
          ),
        ),
        h(
          'div',
          { className: 'wsf-bar' },
          h('button', { className: 'wsf-btn', title: '返回', disabled: !canBack, onClick: back }, '←'),
          h('button', { className: 'wsf-btn', title: '前进', disabled: !canForward, onClick: forward }, '→'),
          h(
            'button',
            {
              className: 'wsf-btn',
              title: '上一级',
              disabled: root === null || dir === null || trimEnd(dir).toLowerCase() === trimEnd(root.workspaceRoot).toLowerCase(),
              onClick: () => go(parentPath(dir)),
            },
            '↑',
          ),
          h(
            'div',
            { className: 'wsf-crumbs' },
            crumbs.map((node, index) =>
              h(
                'button',
                {
                  key: node.path,
                  className: 'wsf-crumb',
                  title: node.path,
                  'data-last': index === crumbs.length - 1 ? 'true' : 'false',
                  onClick: () => go(node.path),
                },
                node.label,
              ),
            ),
          ),
          h('input', {
            className: 'wsf-filter',
            placeholder: '过滤…',
            value: filter,
            onChange: (event) => setFilter(event.target.value),
          }),
        ),
        h(
          'div',
          { className: 'wsf-list' },
          phase === 'loading' && listing === null
            ? h('div', { className: 'wsf-note' }, '正在读取目录…')
            : null,
          phase === 'error' && listing !== null
            ? h('div', { className: 'wsf-note', 'data-tone': 'error' }, failure || '读取失败')
            : null,
          rows.length === 0 && phase !== 'loading'
            ? h('div', { className: 'wsf-note' }, filter.trim() === '' ? '这个目录是空的。' : '没有匹配的条目。')
            : null,
          rows.map((entry) => {
            const isDir = entry.type === 'directory'
            const name = typeof entry.name === 'string' ? entry.name : ''
            return h(
              'div',
              {
                key: name,
                className: 'wsf-row',
                'data-dir': isDir ? 'true' : 'false',
                title: entryPath(entry),
                onClick: () => {
                  if (isDir) go(entryPath(entry))
                  else void act('open', entry)
                },
                onContextMenu: (event) => {
                  event.preventDefault()
                  event.stopPropagation()
                  setMenu({ x: event.clientX, y: event.clientY, entry, mode: 'main' })
                },
              },
              h('span', { className: 'wsf-icon' }, isDir ? '▸' : '·'),
              h('span', { className: 'wsf-name' }, name),
              h('span', { className: 'wsf-meta' }, isDir ? '' : formatSize(entry.size)),
            )
          }),
          listing !== null && listing.truncated === true
            ? h('div', { className: 'wsf-note' }, '目录条目过多，只显示了前一部分。')
            : null,
        ),
        toast !== null ? h('div', { className: 'wsf-toast', 'data-tone': toast.tone }, toast.text) : null,
        menu !== null
          ? h(Menu, { state: menu, apps, onClose: () => setMenu(null), onAction: (kind, options) => void onMenuAction(kind, options) })
          : null,
      )
    }

    /* ------------------------------------------------------------------ */
    /* Plugin wiring                                                       */
    /* ------------------------------------------------------------------ */

    /*
     * Only the two services this module cannot work without are declared: the
     * slot registry and the Remote client. `session` / `workspaceFiles` are
     * looked up lazily from `ctx.get('remote')`, so a deployment that does not
     * mount one of those Host Remotes still shows the panel and explains itself
     * instead of the whole module failing to apply.
     */
    const inject = ['slots', 'remote']

    /** One style element per page, removed with the plugin effect. */
    function insertStyles(ctx) {
      return ctx.effect(() => {
        const existing = document.querySelector('style[data-dsh-workspace-files]')
        if (existing !== null) return () => {}
        const element = document.createElement('style')
        element.setAttribute('data-dsh-workspace-files', '')
        element.textContent = CSS
        document.head.appendChild(element)
        return () => {
          element.remove()
        }
      }, 'workspace-files: styles')
    }

    function apply(ctx) {
      insertStyles(ctx)
      const api = createApi(ctx)

      ctx.slots.inject('sidebar.panellist', () =>
        ctx.slots.register(
          {
            name: 'sidebar.panellist',
            id: PANEL_ID,
            order: 12,
            label: () => '工作区文件',
          },
          FolderIcon,
        ),
      )

      ctx.slots.inject('main', () =>
        ctx.slots.register(
          {
            name: 'main',
            key: PANEL_ID,
            inject: () => ({ api }),
          },
          FilePanel,
        ),
      )
    }

    exports.apply = apply
    exports.inject = inject
    /** Pure helpers, exported for the unit harness (the loader ignores extras). */
    exports.__test = {
      joinPath,
      parentPath,
      baseName,
      relativeTo,
      isInside,
      segmentsOf,
      formatSize,
      errorText,
      FilePanel,
      Menu,
      FolderIcon,
    }
    return module.exports
  },
})
