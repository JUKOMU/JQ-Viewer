/**
 * 增加模板
 * PdfTemplateData 加字段
 * PDF_SAMPLE_DATA 加示例值
 * TEMPLATE_VARS 加 def(...) 条目
 * buildTemplateData 加字段映射
 */

import type {
  AlbumDetail,
  DownloadTask,
  PdfExportChapter,
  PdfExportMode,
  PdfExportTask,
} from './JmcomicTypes'

const KEY_EXPORT_PATH = 'jq-pdf-export-path'
const KEY_DIR_TEMPLATE = 'jq-pdf-dir-template'
const KEY_NAME_TEMPLATE = 'jq-pdf-name-template'

const DEFAULT_EXPORT_PATH = 'Download/JQ-Viewer/'
const DEFAULT_DIR_TEMPLATE = '{id}'
const DEFAULT_NAME_TEMPLATE = '【{author}】{title}_{id} {chapterRange}'

export interface PdfTemplateData {
  id: string
  title: string
  chapterId: string
  chapterName: string
  chapterTitle: string
  chapterRange: string
  pageCount: number
  author: string
  authors: string
  tags: string[]
  index: number | string
}

export interface PdfExportPlanOptions {
  mode: PdfExportMode
  selectedChapters: readonly DownloadTask[]
  albumDetail: AlbumDetail | null
  useOriginal: boolean
  compressionRatio: number
  editedPath: string
  splitPages: number
}

export interface PdfExportPlan {
  tasks: PdfExportTask[]
  outputPaths: string[]
}

/** 内置示例数据，供预览和设置页渲染值展示复用 */
export const PDF_SAMPLE_DATA: PdfTemplateData = {
  id: '295852',
  title:
    '青梅竹馬絕對不會輸的戀愛喜劇～鄰家四姐妹的溫馨日常～ [綠茶漢化][葵季むつみ/二丸修一/しぐれうい]  幼なじみが絕對に負けないラブコメ お鄰の四姉妹が絕對にほのぼのする日常',
  chapterId: '295852',
  chapterName: '第1话',
  chapterTitle: '',
  chapterRange: '第1话',
  pageCount: 38,
  author: '葵季むつみ',
  authors: '葵季むつみ、二丸修一、しぐれうい',
  tags: ['非H', '劇情向', '蘿莉', '純愛', '中文'],
  index: 1,
}

// ---- 模板变量注册表 ----

function escapeRegex(str: string): string {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

export interface TemplateVarDef {
  key: string
  desc: string
  sample: string
  render: (data: PdfTemplateData) => string
}

function def(key: string, desc: string, render: (d: PdfTemplateData) => string): TemplateVarDef {
  return { key, desc, sample: render(PDF_SAMPLE_DATA), render }
}

const TEMPLATE_VARS: TemplateVarDef[] = [
  def('{id}', '本子ID', (d) => d.id),
  def('{title}', '本子标题', (d) => d.title),
  def('{chapterId}', '章节ID', (d) => d.chapterId),
  def('{chapterName}', '章节序号（单行本则为标题）', (d) => d.chapterName),
  def('{chapterRange}', '选择的章节范围', (d) => d.chapterRange),
  def('{index}', '章节原始序号（纯数字）', (d) => String(d.index)),
  def('{chapterTitle}', '章节原始标题', (d) => d.chapterTitle),
  def('{pageCount}', '章节页数', (d) => String(d.pageCount)),
  def('{author}', '首位作者', (d) => d.author),
  def('{authors}', '全部作者，用顿号连接', (d) => d.authors),
  def('{tags}', '全部标签，用顿号连接', (d) => d.tags.join('、')),
]

export const TEMPLATE_VAR_KEYS = TEMPLATE_VARS.map((v) => v.key)
export const TEMPLATE_VAR_DEFS = TEMPLATE_VARS

function resolveChapterName(ch: DownloadTask, album: AlbumDetail | null): string {
  if (album?.seriesId === '0') return album.title || ch.albumTitle
  const order = ch.chapterSortOrder
  if (order && order > 0) return `第${order}话`
  return ch.chapterTitle || ''
}

function isRangeOrder(sortOrder: number | undefined): sortOrder is number {
  return typeof sortOrder === 'number' && Number.isInteger(sortOrder) && sortOrder > 0
}

function formatNumericRange(start: number, end: number): string {
  return start === end ? `第${start}话` : `第${start}-${end}话`
}

function sanitizeSegmentValue(segment: string): string {
  return segment
    .replace(/[\\:*?"<>|]/g, '_') // 非法字符 → _
    .replace(/_+/g, '_') // 合并连续 _
    .replace(/^[_.\s]+|[_.\s]+$/g, '') // 去头尾 _ . 空格
    .substring(0, 255) // 截断超长
}

function sanitizeChapterLabel(chapter: PdfExportChapter): string {
  const title = sanitizeSegmentValue(chapter.chapterTitle.replace(/\//g, '_'))
  if (title) return title

  const chapterId = sanitizeSegmentValue(chapter.chapterId.replace(/\//g, '_'))
  return chapterId || '章节'
}

/** 按已规范化的输出顺序生成安全的章节范围文件名段。 */
export function buildChapterRange(chapters: readonly PdfExportChapter[]): string {
  const orderCounts = new Map<number, number>()
  for (const chapter of chapters) {
    if (!isRangeOrder(chapter.sortOrder)) continue
    orderCounts.set(chapter.sortOrder, (orderCounts.get(chapter.sortOrder) ?? 0) + 1)
  }

  const segments: string[] = []
  let rangeStart: number | undefined
  let rangeEnd: number | undefined

  const flushRange = () => {
    if (rangeStart === undefined || rangeEnd === undefined) return
    segments.push(formatNumericRange(rangeStart, rangeEnd))
    rangeStart = undefined
    rangeEnd = undefined
  }

  for (const chapter of chapters) {
    const order = chapter.sortOrder
    const isUniqueNumericOrder = isRangeOrder(order) && orderCounts.get(order) === 1

    if (!isUniqueNumericOrder) {
      flushRange()
      segments.push(sanitizeChapterLabel(chapter))
      continue
    }

    if (rangeEnd !== undefined && order === rangeEnd + 1) {
      rangeEnd = order
      continue
    }

    flushRange()
    rangeStart = order
    rangeEnd = order
  }

  flushRange()
  return sanitizeSegmentValue(segments.join('+').replace(/\//g, '_'))
}

/** 只重排有效数字章节，无效序号章节保留原位置。 */
export function normalizePdfChapters(chapters: readonly DownloadTask[]): DownloadTask[] {
  const sortedNumericChapters = chapters
    .map((chapter, index) => ({ chapter, index }))
    .filter(({ chapter }) => isRangeOrder(chapter.chapterSortOrder))
    .sort((a, b) => {
      const orderDiff = a.chapter.chapterSortOrder! - b.chapter.chapterSortOrder!
      return orderDiff || a.index - b.index
    })

  let numericIndex = 0
  return chapters.map((chapter) => {
    if (!isRangeOrder(chapter.chapterSortOrder)) return chapter
    return sortedNumericChapters[numericIndex++].chapter
  })
}

export function toPdfExportChapter(chapter: DownloadTask): PdfExportChapter {
  return {
    albumId: chapter.albumId,
    chapterId: chapter.chapterId,
    chapterTitle: chapter.chapterTitle,
    sortOrder: chapter.chapterSortOrder ?? 0,
  }
}

/** 按当前原生卷名规则列出任务可能写入的最终 PDF 路径。 */
export function buildPdfOutputPaths(
  savePath: string,
  totalPages: number,
  splitPages: number,
): string[] {
  if (splitPages <= 0 || totalPages <= splitPages) return [savePath]

  const baseWithoutExt = savePath.endsWith('.pdf') ? savePath.slice(0, -4) : savePath
  const volumeCount = Math.ceil(totalPages / splitPages)
  return Array.from({ length: volumeCount }, (_, index) => {
    const start = index * splitPages + 1
    const end = Math.min(start + splitPages - 1, totalPages)
    return `${baseWithoutExt}_${String(start).padStart(3, '0')}-${String(end).padStart(3, '0')}.pdf`
  })
}

export const PdfExportService = {
  TEMPLATE_VAR_KEYS,
  TEMPLATE_VAR_DEFS,
  buildChapterRange,
  buildPdfOutputPaths,
  normalizePdfChapters,
  toPdfExportChapter,

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

  /**
   * 从 DownloadTask + AlbumDetail 构建 PdfTemplateData。
   * 唯一的模板数据工厂函数，所有调用方统一使用。
   */
  buildTemplateData(ch: DownloadTask, album: AlbumDetail | null): PdfTemplateData {
    const chapterName = resolveChapterName(ch, album)
    return {
      id: ch.albumId,
      title: ch.albumTitle,
      chapterId: ch.chapterId,
      chapterName,
      chapterTitle: ch.chapterTitle || '',
      chapterRange: chapterName,
      pageCount: ch.totalPages,
      author: album?.authors?.[0] ?? '',
      authors: album?.authors?.join('、') ?? '',
      tags: album?.tags ?? [],
      index: ch.chapterSortOrder || '',
    }
  },

  buildMergedTemplateData(
    chapters: readonly DownloadTask[],
    album: AlbumDetail | null,
  ): PdfTemplateData {
    const orderedChapters = normalizePdfChapters(chapters)
    const firstChapter = orderedChapters[0]
    if (!firstChapter) throw new Error('未选择导出章节')

    const exportChapters = orderedChapters.map(toPdfExportChapter)
    return {
      ...PdfExportService.buildTemplateData(firstChapter, album),
      chapterRange: buildChapterRange(exportChapters),
      pageCount: orderedChapters.reduce((total, chapter) => total + chapter.totalPages, 0),
    }
  },

  renderTemplate(template: string, data: PdfTemplateData): string {
    let result = template
    for (const v of TEMPLATE_VARS) {
      result = result.replace(new RegExp(escapeRegex(v.key), 'g'), v.render(data))
    }
    return PdfExportService.renderTagConditions(result, data.tags)
  },

  /** 处理 {tag=xxx} 内联条件：匹配到则渲染标签名，否则为空 */
  renderTagConditions(template: string, tags: string[]): string {
    return template.replace(/\{tag=([^}]+)\}/g, (_match, expr: string) => {
      const trimmed = expr.trim()
      if (!trimmed) return ''

      const hasOr = trimmed.includes('|')
      const hasAnd = trimmed.includes('&')

      if (hasOr && hasAnd) return ''

      if (hasOr) {
        const orTags = trimmed
          .split('|')
          .map((s) => s.trim())
          .filter(Boolean)
        const matched = orTags.filter((t) => tags.includes(t))
        return matched.join('、')
      }

      if (hasAnd) {
        const andTags = trimmed
          .split('&')
          .map((s) => s.trim())
          .filter(Boolean)
        const allMatch = andTags.every((t) => tags.includes(t))
        return allMatch ? andTags.join('、') : ''
      }

      return tags.includes(trimmed) ? trimmed : ''
    })
  },

  /**
   * 净化文件名/路径段，替换非法字符。
   * 保留 / 作为目录分隔符（仅用于目录模板）。
   */
  sanitizeSegment(segment: string): string {
    return sanitizeSegmentValue(segment)
  },

  /** 构建完整保存路径: {exportPath}/{renderedDir}/{renderedName}.pdf */
  buildFullPath(data: PdfTemplateData): string {
    const base = PdfExportService.getExportPath()
    const dir = PdfExportService.renderTemplate(PdfExportService.getDirTemplate(), data)
    const name = PdfExportService.renderTemplate(PdfExportService.getNameTemplate(), data)
    const baseTrimmed = base.replace(/\/+$/, '')
    // 对目录模板的每一段进行净化
    const dirSegments = dir
      .split('/')
      .map((s) => PdfExportService.sanitizeSegment(s))
      .filter((s) => s.length > 0)
    const dirClean = dirSegments.join('/')
    const nameClean = PdfExportService.sanitizeSegment(name)
    if (dirClean) {
      return `${baseTrimmed}/${dirClean}/${nameClean}.pdf`
    }
    return `${baseTrimmed}/${nameClean}.pdf`
  },

  buildMergedFullPath(chapters: readonly DownloadTask[], album: AlbumDetail | null): string {
    return PdfExportService.buildFullPath(PdfExportService.buildMergedTemplateData(chapters, album))
  },

  buildExportPlan(options: PdfExportPlanOptions): PdfExportPlan {
    const selectedChapters = normalizePdfChapters(options.selectedChapters)
    if (selectedChapters.length === 0) throw new Error('未选择导出章节')

    if (options.mode === 'merged') {
      if (selectedChapters.length < 2) throw new Error('合并导出至少需要选择两个章节')

      const albumId = selectedChapters[0].albumId
      if (selectedChapters.some((chapter) => chapter.albumId !== albumId)) {
        throw new Error('合并导出只能选择同一本漫画的章节')
      }

      const templateData = PdfExportService.buildMergedTemplateData(
        selectedChapters,
        options.albumDetail,
      )
      const savePath = options.editedPath
      const task: PdfExportTask = {
        mode: 'merged',
        albumId,
        albumTitle: selectedChapters[0].albumTitle,
        coverUrl: selectedChapters[0].coverUrl,
        authors: templateData.authors,
        isSingleEpisode: selectedChapters[0].isSingleEpisode,
        chapterTitle: templateData.chapterRange,
        chapters: selectedChapters.map(toPdfExportChapter),
        savePath,
        useOriginal: options.useOriginal,
        compressionRatio: options.compressionRatio,
        splitPages: options.splitPages,
      }

      return {
        tasks: [task],
        outputPaths: buildPdfOutputPaths(savePath, templateData.pageCount, options.splitPages),
      }
    }

    const tasks = selectedChapters.map<PdfExportTask>((chapter) => ({
      mode: 'chapter',
      albumId: chapter.albumId,
      albumTitle: chapter.albumTitle,
      coverUrl: chapter.coverUrl,
      authors: options.albumDetail?.authors?.join('、') ?? '',
      isSingleEpisode: chapter.isSingleEpisode,
      chapterId: chapter.chapterId,
      chapterTitle: chapter.chapterTitle,
      savePath:
        selectedChapters.length === 1
          ? options.editedPath
          : PdfExportService.buildFullPath(
              PdfExportService.buildTemplateData(chapter, options.albumDetail),
            ),
      useOriginal: options.useOriginal,
      compressionRatio: options.compressionRatio,
      splitPages: options.splitPages,
    }))

    return {
      tasks,
      outputPaths: tasks.flatMap((task, index) =>
        buildPdfOutputPaths(task.savePath, selectedChapters[index].totalPages, options.splitPages),
      ),
    }
  },

  /** 用示例数据生成预览 */
  previewPath(): string {
    return PdfExportService.buildFullPath(PDF_SAMPLE_DATA)
  },
}
