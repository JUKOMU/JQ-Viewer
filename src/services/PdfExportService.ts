const KEY_EXPORT_PATH = 'jq-pdf-export-path'
const KEY_DIR_TEMPLATE = 'jq-pdf-dir-template'
const KEY_NAME_TEMPLATE = 'jq-pdf-name-template'

const DEFAULT_EXPORT_PATH = 'Download/JQ-Viewer/'
const DEFAULT_DIR_TEMPLATE = '{id}{title}'
const DEFAULT_NAME_TEMPLATE = '{chapterId}_{chapterName}'

export interface PdfTemplateData {
  id: string
  title: string
  chapterId: string
  chapterName: string
  pageCount: number
}

export const PdfExportService = {
  // ---- 设置读写 ----

  /**
   * 确保导出路径是绝对路径。首次加载时若为默认相对路径，则基于外部存储根目录解析。
   * 应在设置页加载时调用一次。
   */
  ensureAbsolutePath(externalStorageRoot: string) {
    const stored = localStorage.getItem(KEY_EXPORT_PATH)
    if (stored && stored.startsWith('/')) return // 已是绝对路径
    // 无存储 或 为相对路径 → 解析为绝对路径
    const base = externalStorageRoot.replace(/\/+$/, '')
    const relative = (stored || DEFAULT_EXPORT_PATH).replace(/^\/+/, '')
    const absPath = base + '/' + relative
    localStorage.setItem(KEY_EXPORT_PATH, absPath)
  },

  getExportPath(): string {
    try {
      return localStorage.getItem(KEY_EXPORT_PATH) || DEFAULT_EXPORT_PATH
    } catch {
      return DEFAULT_EXPORT_PATH
    }
  },

  setExportPath(path: string) {
    localStorage.setItem(KEY_EXPORT_PATH, path)
  },

  resetExportPath() {
    localStorage.removeItem(KEY_EXPORT_PATH)
  },

  getDirTemplate(): string {
    try {
      return localStorage.getItem(KEY_DIR_TEMPLATE) || DEFAULT_DIR_TEMPLATE
    } catch {
      return DEFAULT_DIR_TEMPLATE
    }
  },

  setDirTemplate(template: string) {
    localStorage.setItem(KEY_DIR_TEMPLATE, template)
  },

  resetDirTemplate() {
    localStorage.removeItem(KEY_DIR_TEMPLATE)
  },

  getNameTemplate(): string {
    try {
      return localStorage.getItem(KEY_NAME_TEMPLATE) || DEFAULT_NAME_TEMPLATE
    } catch {
      return DEFAULT_NAME_TEMPLATE
    }
  },

  setNameTemplate(template: string) {
    localStorage.setItem(KEY_NAME_TEMPLATE, template)
  },

  resetNameTemplate() {
    localStorage.removeItem(KEY_NAME_TEMPLATE)
  },

  // ---- 模板渲染 ----

  renderTemplate(template: string, data: PdfTemplateData): string {
    return template
      .replace(/\{id\}/g, data.id)
      .replace(/\{title\}/g, data.title)
      .replace(/\{chapterId\}/g, data.chapterId)
      .replace(/\{chapterName\}/g, data.chapterName)
      .replace(/\{pageCount\}/g, String(data.pageCount))
  },

  /**
   * 净化文件名/路径段，替换非法字符。
   * 保留 / 作为目录分隔符（仅用于目录模板）。
   */
  sanitizeSegment(segment: string): string {
    return segment
      .replace(/[\\:*?"<>|]/g, '_')  // 非法字符 → _
      .replace(/_+/g, '_')             // 合并连续 _
      .replace(/^[_.\s]+|[_.\s]+$/g, '') // 去头尾 _ . 空格
      .substring(0, 255)               // 截断超长
  },

  /** 构建完整保存路径: {exportPath}/{renderedDir}/{renderedName}.pdf */
  buildFullPath(data: PdfTemplateData): string {
    const base = PdfExportService.getExportPath()
    const dir = PdfExportService.renderTemplate(PdfExportService.getDirTemplate(), data)
    const name = PdfExportService.renderTemplate(PdfExportService.getNameTemplate(), data)
    const baseTrimmed = base.replace(/\/+$/, '')
    // 对目录模板的每一段进行净化
    const dirSegments = dir.split('/').map(s => PdfExportService.sanitizeSegment(s)).filter(s => s.length > 0)
    const dirClean = dirSegments.join('/')
    const nameClean = PdfExportService.sanitizeSegment(name)
    if (dirClean) {
      return `${baseTrimmed}/${dirClean}/${nameClean}.pdf`
    }
    return `${baseTrimmed}/${nameClean}.pdf`
  },

  /** 用示例数据生成预览 */
  previewPath(): string {
    const sample: PdfTemplateData = {
      id: '123456',
      title: 'サンプル漫画タイトル',
      chapterId: '789012',
      chapterName: '第1話',
      pageCount: 42,
    }
    return PdfExportService.buildFullPath(sample)
  },
}
