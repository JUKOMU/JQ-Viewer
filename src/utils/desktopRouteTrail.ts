export type DesktopRouteTrailItem =
  | {
      path: string
      label: string
      trailIndex: number
      isEllipsis?: false
    }
  | {
      path: null
      label: '…'
      trailIndex: null
      isEllipsis: true
    }

export type DesktopRouteNavigationKind = 'forward' | 'back' | 'trail'

const routeLabels: Array<{ pattern: RegExp | string; label: string }> = [
  { pattern: '/home', label: '首页' },
  { pattern: '/category', label: '分类' },
  { pattern: '/search', label: '搜索' },
  { pattern: '/favorite', label: '收藏夹' },
  { pattern: '/download', label: '下载' },
  { pattern: '/history', label: '历史' },
  { pattern: '/setting', label: '设置' },
  { pattern: '/cache', label: '缓存' },
  { pattern: '/user', label: '账号' },
  { pattern: '/login', label: '登录' },
  { pattern: '/network-status', label: '网络状态' },
  { pattern: '/about', label: '关于' },
  { pattern: '/pdf-template-help', label: 'PDF 模板说明' },
  { pattern: '/batch-parse', label: '批量解析' },
  { pattern: '/import-review', label: '导入预览' },
  { pattern: /^\/album\/[^/]+\/preview\/[^/]+$/, label: '章节预览' },
  { pattern: /^\/album\/[^/]+\/download-chapters$/, label: '章节下载' },
  { pattern: /^\/album\/[^/]+\/read\/[^/]+$/, label: '阅读器' },
  { pattern: '/pdf-reader', label: 'PDF 阅读器' },
  { pattern: '/cbz-reader', label: 'CBZ 阅读器' },
  { pattern: '/logs', label: '日志' },
]

const routePath = (fullPath: string) => fullPath.split(/[?#]/, 1)[0] || '/'

export function getDesktopRouteLabel(fullPath: string): string {
  const path = routePath(fullPath)
  const albumDetailMatch = path.match(/^\/album\/([^/]+)$/)
  if (albumDetailMatch) {
    return `本子详情(${albumDetailMatch[1]})`
  }

  const match = routeLabels.find(({ pattern }) =>
    typeof pattern === 'string' ? path === pattern : pattern.test(path),
  )
  return match?.label ?? '页面'
}

const createItem = (path: string, trailIndex: number): DesktopRouteTrailItem => ({
  path,
  label: getDesktopRouteLabel(path),
  trailIndex,
})

export function buildDesktopRouteTrail(
  routeStack: readonly string[],
  currentPath: string,
): DesktopRouteTrailItem[] {
  const paths = [...routeStack, currentPath].filter(Boolean).filter((path, index, all) => {
    return index === 0 || path !== all[index - 1]
  })

  if (paths.length <= 9) return paths.map((path, index) => createItem(path, index))

  return [
    createItem(paths[0], 0),
    { path: null, label: '…', trailIndex: null, isEllipsis: true },
    ...paths.slice(-8).map((path, index) => createItem(path, paths.length - 8 + index)),
  ]
}

export function truncateDesktopRouteStack(
  routeStack: readonly string[],
  currentPath: string,
  trailIndex: number,
): string[] {
  const paths = [...routeStack, currentPath].filter(Boolean).filter((path, index, all) => {
    return index === 0 || path !== all[index - 1]
  })
  return paths.slice(0, Math.max(0, trailIndex))
}

export function updateDesktopRouteStack(
  routeStack: readonly string[],
  fromPath: string,
  toPath: string,
  navigationKind: DesktopRouteNavigationKind,
): string[] {
  if (navigationKind === 'forward') {
    return fromPath ? [...routeStack, fromPath] : [...routeStack]
  }

  const targetIndex = routeStack.lastIndexOf(toPath)
  if (targetIndex >= 0) return routeStack.slice(0, targetIndex)
  return routeStack.slice(0, -1)
}
