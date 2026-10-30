/**
 * Headless smoke test for the workspace-files client half.
 *
 * The page supplies React and the Cordis context, so this harness emulates both:
 * a tiny hook runtime that can be seeded, and a fake context that records slot
 * registrations. It runs without a browser, so it catches wiring mistakes and
 * render-time crashes before the page is refreshed.
 */
const assert = require('node:assert')
const fs = require('node:fs')
const path = require('node:path')

const CLIENT = path.join(__dirname, '..', 'client.js')

/* ------------------------------------------------------------------ */
/* minimal React + module loader                                       */
/* ------------------------------------------------------------------ */

let seed = null

const React = {
  createElement(type, props, ...children) {
    const flat = children.length <= 1 ? children[0] : children
    const merged = props === null || props === undefined ? {} : { ...props }
    merged.children = flat
    return { type, props: merged }
  },
  useState(initial) {
    let value = typeof initial === 'function' ? initial() : initial
    if (seed !== null && seed.length > 0) value = seed.shift()
    return [value, () => {}]
  },
  useMemo(fn) {
    return fn()
  },
  useCallback(fn) {
    return fn
  },
  useRef(initial) {
    return { current: initial }
  },
  useEffect() {},
}

let captured = null
globalThis.window = {
  __ModuleLoader__: {
    load(spec) {
      captured = spec
    },
  },
  localStorage: {
    store: new Map(),
    getItem(key) {
      return this.store.has(key) ? this.store.get(key) : null
    },
    setItem(key, value) {
      this.store.set(key, String(value))
    },
  },
  addEventListener() {},
  removeEventListener() {},
  clearTimeout() {},
  setTimeout() {
    return 0
  },
  innerWidth: 1280,
  innerHeight: 800,
}

const styleNodes = []
globalThis.document = {
  querySelector() {
    return null
  },
  createElement(tag) {
    const node = { tag, textContent: '', attributes: {}, removed: false, remove() { this.removed = true }, setAttribute(k, v) { this.attributes[k] = v } }
    return node
  },
  head: {
    appendChild(node) {
      styleNodes.push(node)
    },
  },
  body: { appendChild() {}, removeChild() {} },
}

require(CLIENT)

assert.ok(captured !== null, 'client module never called window.__ModuleLoader__.load')
assert.strictEqual(captured.id, '@local/dsh-workspace-files', 'module id')

const moduleExports = captured.factory((name) => {
  if (name === 'react') return React
  throw new Error(`unexpected require: ${name}`)
})

/* ------------------------------------------------------------------ */
/* contract                                                            */
/* ------------------------------------------------------------------ */

assert.strictEqual(typeof moduleExports.apply, 'function', 'exports.apply')
assert.ok(Array.isArray(moduleExports.inject), 'exports.inject must be an array')
assert.deepStrictEqual(moduleExports.inject, ['slots', 'remote'])

const { __test } = moduleExports
assert.ok(__test !== null && typeof __test === 'object', 'exports.__test')

/* ------------------------------------------------------------------ */
/* pure helpers                                                        */
/* ------------------------------------------------------------------ */

const t = __test
assert.strictEqual(t.joinPath('D:\\xfriend-suite', 'docs'), 'D:\\xfriend-suite\\docs')
assert.strictEqual(t.joinPath('D:\\xfriend-suite\\', 'docs'), 'D:\\xfriend-suite\\docs')
assert.strictEqual(t.joinPath('/home/me/proj', 'a.txt'), '/home/me/proj/a.txt')
assert.strictEqual(t.parentPath('D:\\xfriend-suite\\plugins'), 'D:\\xfriend-suite')
assert.strictEqual(t.parentPath('/home/me/proj'), '/home/me')
assert.strictEqual(t.baseName('D:\\xfriend-suite\\plugins\\x.md'), 'x.md')
assert.strictEqual(t.baseName('D:\\xfriend-suite'), 'xfriend-suite')
assert.strictEqual(t.relativeTo('D:\\xfriend-suite', 'D:\\xfriend-suite\\plugins\\x.md'), 'plugins\\x.md')
assert.strictEqual(t.relativeTo('D:\\xfriend-suite', 'D:\\xfriend-suite'), '')
assert.strictEqual(t.isInside('D:\\xfriend-suite', 'D:\\xfriend-suite\\a'), true)
assert.strictEqual(t.isInside('D:\\xfriend-suite', 'D:\\xfriend-suite-evil\\a'), false)
assert.strictEqual(t.isInside('D:\\xfriend-suite', 'D:\\xfriend-suite'), true)
assert.deepStrictEqual(t.segmentsOf('D:\\xfriend-suite', 'D:\\xfriend-suite\\a\\b'), ['a', 'b'])
assert.strictEqual(t.formatSize(512), '512 B')
assert.strictEqual(t.formatSize(2048), '2.0 KB')
assert.strictEqual(t.formatSize(undefined), '')
assert.strictEqual(t.errorText(new Error('boom')), 'boom')
assert.strictEqual(t.errorText({ code: 'gateway/cancelled' }), 'gateway/cancelled')

/* ------------------------------------------------------------------ */
/* apply(): slot wiring                                                */
/* ------------------------------------------------------------------ */

const registrations = []
const effects = []
const ctx = {
  get(name) {
    return name === 'remote' ? { session: {}, workspaceFiles: {} } : undefined
  },
  effect(fn, label) {
    effects.push(label)
    const disposer = fn()
    return typeof disposer === 'function' ? disposer : () => {}
  },
  slots: {
    inject(key, callback) {
      const dispose = callback()
      return dispose
    },
    register(options, Component) {
      registrations.push({ options, Component })
      return () => {}
    },
  },
}

moduleExports.apply(ctx)

assert.strictEqual(styleNodes.length, 1, 'one style element inserted')
assert.ok(styleNodes[0].attributes['data-dsh-workspace-files'] !== undefined, 'style marker attribute')
assert.ok(styleNodes[0].textContent.includes('.wsf-root'), 'styles content')
assert.ok(effects.some((label) => /styles/.test(String(label))), 'styles effect registered')

assert.strictEqual(registrations.length, 2, 'two slot registrations')
const [panellist, main] = registrations
assert.strictEqual(panellist.options.name, 'sidebar.panellist')
assert.strictEqual(panellist.options.id, 'workspace-files')
assert.strictEqual(typeof panellist.options.label, 'function')
assert.strictEqual(panellist.options.label(), '工作区文件')
assert.strictEqual(typeof panellist.Component, 'function')
assert.strictEqual(main.options.name, 'main')
assert.strictEqual(main.options.key, 'workspace-files')
assert.strictEqual(typeof main.options.inject, 'function')
const injected = main.options.inject()
assert.ok(injected.api !== undefined, 'main panel receives an api prop')
assert.strictEqual(panellist.Component({ size: 16, active: true }).type, 'svg')

/* ------------------------------------------------------------------ */
/* render: empty first paint                                           */
/* ------------------------------------------------------------------ */

seed = null
const api = {
  available: () => true,
  sessions: async () => [],
  list: async () => ({ path: 'D:\\xfriend-suite', entries: [], truncated: false }),
  open: async () => ({ opened: true }),
  reveal: async () => ({ opened: true }),
  openWith: async () => ({ opened: true }),
  applications: async () => [],
}

const first = main.Component({ api })
assert.strictEqual(first.props.className, 'wsf-root')
const firstText = JSON.stringify(first)
assert.ok(!firstText.includes('undefined'), 'first paint must not leak undefined into the tree')

/* ------------------------------------------------------------------ */
/* render: a populated directory with a context menu                    */
/* ------------------------------------------------------------------ */

const root = { sessionId: 'session-1', workspaceRoot: 'D:\\xfriend-suite' }
const listing = {
  path: 'D:\\xfriend-suite\\plugins',
  truncated: false,
  entries: [
    { name: 'dsh-workspace-files', type: 'directory' },
    { name: 'README.md', type: 'file', size: 1536 },
    { name: 'app.ts', type: 'file', size: 20480 },
  ],
}
seed = [
  [root], // roots
  root, // root
  'D:\\xfriend-suite\\plugins', // dir
  listing, // listing
  'ready', // phase
  null, // failure
  '', // filter
  null, // menu
  null, // apps
  null, // toast
]
const ready = main.Component({ api })
const readyText = JSON.stringify(ready)
assert.ok(!readyText.includes('undefined'), 'populated paint must not leak undefined')
assert.ok(readyText.includes('dsh-workspace-files'), 'directory row rendered')
assert.ok(readyText.includes('README.md'), 'file row rendered')
assert.ok(readyText.includes('1.5 KB'), 'file size formatted')
assert.ok(readyText.includes('plugins'), 'breadcrumb rendered')

/* ------------------------------------------------------------------ */
/* context menu (rendered directly: the fake runtime cannot recurse)   */
/* ------------------------------------------------------------------ */

seed = null
const noop = () => {}
const fileMenu = JSON.stringify(
  t.Menu({ state: { x: 40, y: 60, entry: { name: 'README.md', type: 'file' }, mode: 'main' }, apps: null, onClose: noop, onAction: noop }),
)
for (const label of ['打开（默认程序）', '选择打开方式…', '在资源管理器中显示', '复制路径', '复制相对路径', '复制文件名']) {
  assert.ok(fileMenu.includes(label), `file menu has ${label}`)
}

const dirMenu = JSON.stringify(
  t.Menu({ state: { x: 40, y: 60, entry: { name: 'plugins', type: 'directory' }, mode: 'main' }, apps: null, onClose: noop, onAction: noop }),
)
for (const label of ['进入目录', '用默认程序打开', '在资源管理器中显示', '复制路径', '复制相对路径', '复制名称']) {
  assert.ok(dirMenu.includes(label), `directory menu has ${label}`)
}

const blankMenu = JSON.stringify(
  t.Menu({ state: { x: 40, y: 60, entry: null, mode: 'main' }, apps: null, onClose: noop, onAction: noop }),
)
assert.ok(blankMenu.includes('刷新'), 'blank menu has refresh')
assert.ok(blankMenu.includes('复制当前目录路径'), 'blank menu copies directory path')

const appsMenu = JSON.stringify(
  t.Menu({
    state: { x: 40, y: 60, entry: { name: 'a.png', type: 'file' }, mode: 'apps' },
    apps: [{ id: 'paint', name: '画图', default: true }, { id: 'photos', name: '照片' }],
    onClose: noop,
    onAction: noop,
  }),
)
assert.ok(appsMenu.includes('画图'), 'open-with lists registered app')
assert.ok(appsMenu.includes('默认'), 'open-with marks the default app')
assert.ok(appsMenu.includes('返回'), 'open-with can go back')

/* ------------------------------------------------------------------ */
/* render: error and empty phases                                      */
/* ------------------------------------------------------------------ */

seed = [null, null, null, null, 'error', '远程调用失败', '', null, null, null]
const failed = main.Component({ api })
assert.ok(JSON.stringify(failed).includes('远程调用失败'), 'error text shown')

seed = [null, null, null, null, 'empty', '没有找到带工作区目录的会话。', '', null, null, null]
const empty = main.Component({ api })
assert.ok(JSON.stringify(empty).includes('没有找到带工作区目录的会话'), 'empty text shown')
assert.strictEqual(empty.props.className, 'wsf-root')

console.log('client.js smoke test: PASS')
