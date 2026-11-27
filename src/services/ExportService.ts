/**
 * 增加模板
 * ExportTemplateData 加字段
 * EXPORT_SAMPLE_DATA 加示例值
 * TEMPLATE_VARS 加 def(...) 条目
 * buildTemplateData 加字段映射
 */

import type {
  DownloadTask,
  ExportTaskChapter,
  ExportMode,
  ExportTask,
  ExportFormat,
} from './JmcomicTypes'
import type { ExportTarget, FolderRef } from '@/runtime/FileReferences'
import {
  DEFAULT_EXPORT_DIRECTORY_TEMPLATE,
  DEFAULT_EXPORT_PATH,
  DEFAULT_EXPORT_FILE_NAME_TEMPLATE,
  defaultExportPreferences,
  type ExportFolderSelection,
  type ExportPreferences,
  type ExportPreferencesStore,
} from '@/runtime/ExportPreferences'

export type { ExportFolderSelection } from '@/runtime/ExportPreferences'

export interface ExportTemplateData {
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

export interface ExportPlanOptions {
  format: ExportFormat
  mode: ExportMode
  selectedChapters: readonly DownloadTask[]
  useOriginal: boolean
  compressionRatio: number
  editedPath: string
  exportFolder?: FolderRef
  exportFolderDisplayPath?: string
  splitPages: number
  deleteSourceAfterExport: boolean
  retainDownloadEntry: boolean
}

export interface ExportPlan {
  tasks: ExportTask[]
  outputDisplayPaths: string[]
}

let preferences: ExportPreferences = defaultExportPreferences()
let preferencesStore: ExportPreferencesStore | null = null

function requirePreferencesStore(): ExportPreferencesStore {
  if (!preferencesStore) throw new Error('导出设置尚未初始化')
  return preferencesStore
}

/** 内置示例数据，供预览和设置页渲染值展示复用 */
export const EXPORT_SAMPLE_DATA: ExportTemplateData = {
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
  render: (data: ExportTemplateData) => string
}

function def(key: string, desc: string, render: (d: ExportTemplateData) => string): TemplateVarDef {
  return { key, desc, sample: render(EXPORT_SAMPLE_DATA), render }
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

function resolveChapterName(ch: DownloadTask): string {
  if (ch.isSingleEpisode) return ch.albumTitle
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

function sanitizeSegmentValue(segment: string, maxLength = 255): string {
  return segment
    .replace(/[\\/:*?"<>|]/g, '_') // 非法字符 → _
    .replace(/_+/g, '_') // 合并连续 _
    .replace(/^[_.\s]+|[_.\s]+$/g, '') // 去头尾 _ . 空格
    .substring(0, maxLength) // 截断超长
    .replace(/[_.\s]+$/g, '') // 截断后再次清理尾部字符
}

function sanitizeFileNameValue(fileName: string, format?: ExportFormat): string {
  const extension = format ? `.${format}` : ''
  const hasExpectedExtension = Boolean(
    extension && fileName.toLowerCase().endsWith(extension.toLowerCase()),
  )
  const base = hasExpectedExtension ? fileName.slice(0, -extension.length) : fileName
  const cleanBase = sanitizeSegmentValue(
    base,
    Math.max(0, 255 - (hasExpectedExtension ? extension.length : 0)),
  )
  return `${cleanBase}${hasExpectedExtension ? extension : ''}`
}

function sanitizeChapterLabel(chapter: ExportTaskChapter): string {
  const title = sanitizeSegmentValue(chapter.chapterTitle)
  if (title) return title

  const chapterId = sanitizeSegmentValue(chapter.chapterId)
  return chapterId || '章节'
}

/** 按已规范化的输出顺序生成安全的章节范围文件名段。 */
export function buildChapterRange(chapters: readonly ExportTaskChapter[]): string {
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
export function normalizeExportChapters(chapters: readonly DownloadTask[]): DownloadTask[] {
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

export function toExportTaskChapter(chapter: DownloadTask): ExportTaskChapter {
  return {
    albumId: chapter.albumId,
    chapterId: chapter.chapterId,
    chapterTitle: chapter.chapterTitle,
    sortOrder: chapter.chapterSortOrder ?? 0,
  }
}

export function withExportExtension(path: string, format: ExportFormat): string {
  return `${path.replace(/\.(pdf|cbz|zip)$/i, '')}.${format}`
}

function renderTagConditions(
  template: string,
  tags: string[],
  sanitizeValue?: (value: string) => string,
): string {
  return template.replace(/\{tag=([^}]+)\}/g, (_match, expr: string) => {
    const trimmed = expr.trim()
    if (!trimmed) return ''

    const hasOr = trimmed.includes('|')
    const hasAnd = trimmed.includes('&')
    let value = ''

    if (hasOr && hasAnd) return ''

    if (hasOr) {
      const orTags = trimmed
        .split('|')
        .map((s) => s.trim())
        .filter(Boolean)
      const matched = orTags.filter((t) => tags.includes(t))
      value = matched.join('、')
    } else if (hasAnd) {
      const andTags = trimmed
        .split('&')
        .map((s) => s.trim())
        .filter(Boolean)
      const allMatch = andTags.every((t) => tags.includes(t))
      value = allMatch ? andTags.join('、') : ''
    } else {
      value = tags.includes(trimmed) ? trimmed : ''
    }

    return sanitizeValue ? sanitizeValue(value) : value
  })
}

function renderTemplateValue(
  template: string,
  data: ExportTemplateData,
  sanitizeValues = false,
): string {
  let result = template
  for (const v of TEMPLATE_VARS) {
    const value = v.render(data)
    result = result.replace(
      new RegExp(escapeRegex(v.key), 'g'),
      sanitizeValues ? sanitizeSegmentValue(value) : value,
    )
  }
  return renderTagConditions(result, data.tags, sanitizeValues ? sanitizeSegmentValue : undefined)
}

/** 按当前原生卷名规则列出任务可能写入的最终文件路径。 */
export function buildExportOutputPaths(
  savePath: string,
  totalPages: number,
  splitPages: number,
  format: ExportFormat = 'pdf',
): string[] {
  const normalizedPath = withExportExtension(savePath, format)
  if (splitPages <= 0 || totalPages <= splitPages) return [normalizedPath]

  const baseWithoutExt = normalizedPath.slice(0, -(format.length + 1))
  const volumeCount = Math.ceil(totalPages / splitPages)
  return Array.from({ length: volumeCount }, (_, index) => {
    const start = index * splitPages + 1
    const end = Math.min(start + splitPages - 1, totalPages)
    return `${baseWithoutExt}_${String(start).padStart(3, '0')}-${String(end).padStart(3, '0')}.${format}`
  })
}

function buildTemplatePath(
  base: string,
  directoryTemplate: string,
  fileNameTemplate: string,
  data: ExportTemplateData,
  format: ExportFormat,
): string {
  const dir = renderTemplateValue(directoryTemplate, data, true)
  const name = renderTemplateValue(fileNameTemplate, data, true)
  const baseTrimmed = base.replace(/[\\/]+$/, '')
  const dirSegments = dir
    .split('/')
    .map((segment) => sanitizeSegmentValue(segment))
    .filter((segment) => segment.length > 0)
  const dirClean = dirSegments.join('/')
  const nameClean = sanitizeFileNameValue(`${name}.${format}`, format)
  if (dirClean) {
    return `${baseTrimmed}/${dirClean}/${nameClean}`
  }
  return `${baseTrimmed}/${nameClean}`
}

function normalizeExportRelativePath(relativePath: string, format: ExportFormat): string {
  const normalized = relativePath.replace(/\\/g, '/')
  if (!normalized) throw new Error('导出文件名不能为空')

  const rawSegments = normalized.split('/')
  if (rawSegments.some((segment) => !segment || segment === '.' || segment === '..')) {
    throw new Error('导出文件路径包含无效路径段')
  }

  const fileIndex = rawSegments.length - 1
  const cleanSegments = rawSegments
    .map((segment, index) =>
      index === fileIndex
        ? sanitizeFileNameValue(segment, format)
        : sanitizeSegmentValue(segment),
    )
    .filter((segment, index) => index === fileIndex || segment.length > 0)

  const fileName = cleanSegments[cleanSegments.length - 1]
  if (!fileName) throw new Error('导出文件名不能为空')
  return cleanSegments.join('/')
}

interface ExportPathParts {
  folder: FolderRef
  normalizedFolder: string
  relativePath: string
}

function extractExportPathParts(
  displayPath: string,
  folder?: FolderRef,
  folderDisplayPath?: string,
): ExportPathParts {
  if (!folder) throw new Error('请先选择导出目录')

  const normalizedPath = displayPath.replace(/\\/g, '/')
  const rawFolder = (folderDisplayPath || '').replace(/\\/g, '/')
  const normalizedFolder = rawFolder === '/' ? '/' : rawFolder.replace(/\/+$/, '')
  if (!normalizedFolder) throw new Error('导出目录引用缺少展示路径')

  const folderPrefix = normalizedFolder === '/' ? '/' : `${normalizedFolder}/`
  if (!normalizedPath.startsWith(folderPrefix)) {
    throw new Error('导出文件必须位于已选择的导出目录内')
  }

  const relativePath = normalizedPath.slice(folderPrefix.length)
  if (!relativePath) throw new Error('导出文件名不能为空')
  return { folder, normalizedFolder, relativePath }
}

function normalizeExportDisplayPath(
  displayPath: string,
  folder: FolderRef | undefined,
  folderDisplayPath: string | undefined,
  format: ExportFormat,
): { displayPath: string; target: ExportTarget } {
  const parts = extractExportPathParts(displayPath, folder, folderDisplayPath)
  const rawSegments = parts.relativePath.replace(/\\/g, '/').split('/')
  if (rawSegments.some((segment) => !segment || segment === '.' || segment === '..')) {
    throw new Error('导出文件路径包含无效路径段')
  }
  const lastSegment = rawSegments.pop()
  if (!lastSegment) throw new Error('导出文件名不能为空')
  rawSegments.push(withExportExtension(lastSegment, format))
  const relativePath = normalizeExportRelativePath(rawSegments.join('/'), format)
  const normalizedDisplayPath =
    parts.normalizedFolder === '/'
      ? `/${relativePath}`
      : `${parts.normalizedFolder}/${relativePath}`
  return {
    displayPath: normalizedDisplayPath,
    target: { folder: parts.folder, relativePath },
  }
}

/**
 * 根据展示路径与可选导出目录构造 ExportTarget。
 * 未提供目录引用时，仅从展示路径提取父目录和文件名；提供目录引用时，
 * 只计算目录内的逻辑相对路径。实际平台目标的拼接与越界校验留给 adapter。
 */
function buildExportTarget(
  displayPath: string,
  folder?: FolderRef,
  folderDisplayPath?: string,
  format: ExportFormat = 'pdf',
): ExportTarget {
  const parts = extractExportPathParts(displayPath, folder, folderDisplayPath)
  return {
    folder: parts.folder,
    relativePath: normalizeExportRelativePath(parts.relativePath, format),
  }
}

export const ExportService = {
  TEMPLATE_VAR_KEYS,
  TEMPLATE_VAR_DEFS,
  buildChapterRange,
  buildExportOutputPaths,
  withExportExtension,
  buildExportTarget,
  buildTemplatePath,
  normalizeExportChapters,
  toExportTaskChapter,

  // ---- 设置读写 ----

  async initialize(store: ExportPreferencesStore): Promise<void> {
    preferences = await store.get()
    preferencesStore = store
  },

  getExportPath(): string {
    return preferences.exportFolder?.displayPath || DEFAULT_EXPORT_PATH
  },

  getExportFolder(): ExportFolderSelection | null {
    return preferences.exportFolder
  },

  async setExportFolder(selection: ExportFolderSelection): Promise<void> {
    await requirePreferencesStore().setExportFolder(selection)
    preferences = { ...preferences, exportFolder: selection }
  },

  /** 手工编辑只更新当前表单，不把无法绑定 ref 的 raw path 写入持久化设置。 */
  setExportPath(path: string) {
    void path
  },

  async resetExportPath(): Promise<void> {
    await requirePreferencesStore().setExportFolder(null)
    preferences = { ...preferences, exportFolder: null }
  },

  getDirTemplate(): string {
    return preferences.directoryTemplate
  },

  async setDirTemplate(template: string): Promise<void> {
    await requirePreferencesStore().setDirectoryTemplate(template)
    preferences = { ...preferences, directoryTemplate: template }
  },

  async resetDirTemplate(): Promise<void> {
    await requirePreferencesStore().setDirectoryTemplate(null)
    preferences = { ...preferences, directoryTemplate: DEFAULT_EXPORT_DIRECTORY_TEMPLATE }
  },

  getNameTemplate(): string {
    return preferences.fileNameTemplate
  },

  async setNameTemplate(template: string): Promise<void> {
    await requirePreferencesStore().setFileNameTemplate(template)
    preferences = { ...preferences, fileNameTemplate: template }
  },

  async resetNameTemplate(): Promise<void> {
    await requirePreferencesStore().setFileNameTemplate(null)
    preferences = { ...preferences, fileNameTemplate: DEFAULT_EXPORT_FILE_NAME_TEMPLATE }
  },

  getLastFormat(): ExportFormat {
    return preferences.lastFormat
  },

  async setLastFormat(format: ExportFormat): Promise<void> {
    await requirePreferencesStore().setLastFormat(format)
    preferences = { ...preferences, lastFormat: format }
  },

  // ---- 模板渲染 ----

  /**
   * 从下载记录构建模板数据。
   * 唯一的模板数据工厂函数，所有调用方统一使用。
   */
  buildTemplateData(ch: DownloadTask): ExportTemplateData {
    const chapterName = resolveChapterName(ch)
    const authors = ch.authors?.length ? ch.authors : ch.author ? [ch.author] : []
    return {
      id: ch.albumId,
      title: ch.albumTitle,
      chapterId: ch.chapterId,
      chapterName,
      chapterTitle: ch.chapterTitle || '',
      chapterRange: chapterName,
      pageCount: ch.totalPages,
      author: authors[0] ?? '',
      authors: authors.join('、'),
      tags: ch.tags ?? [],
      index: ch.chapterSortOrder || '',
    }
  },

  buildMergedTemplateData(chapters: readonly DownloadTask[]): ExportTemplateData {
    const orderedChapters = normalizeExportChapters(chapters)
    const firstChapter = orderedChapters[0]
    if (!firstChapter) throw new Error('未选择导出章节')

    const exportChapters = orderedChapters.map(toExportTaskChapter)
    return {
      ...ExportService.buildTemplateData(firstChapter),
      chapterRange: buildChapterRange(exportChapters),
      pageCount: orderedChapters.reduce((total, chapter) => total + chapter.totalPages, 0),
    }
  },

  renderTemplate(template: string, data: ExportTemplateData): string {
    return renderTemplateValue(template, data)
  },

  renderDirectoryTemplate(template: string, data: ExportTemplateData): string {
    return renderTemplateValue(template, data, true)
      .split('/')
      .map((segment) => sanitizeSegmentValue(segment))
      .filter((segment) => segment.length > 0)
      .join('/')
  },

  /** 处理 {tag=xxx} 内联条件：匹配到则渲染标签名，否则为空 */
  renderTagConditions(template: string, tags: string[]): string {
    return renderTagConditions(template, tags)
  },

  /**
   * 净化文件名/路径段，替换非法字符。
   */
  sanitizeSegment(segment: string): string {
    return sanitizeSegmentValue(segment)
  },

  /** 构建完整保存路径: {exportPath}/{renderedDir}/{renderedName}.{format} */
  buildFullPath(data: ExportTemplateData, format: ExportFormat = 'pdf'): string {
    return buildTemplatePath(
      ExportService.getExportPath(),
      ExportService.getDirTemplate(),
      ExportService.getNameTemplate(),
      data,
      format,
    )
  },

  buildMergedFullPath(chapters: readonly DownloadTask[], format: ExportFormat = 'pdf'): string {
    return ExportService.buildFullPath(ExportService.buildMergedTemplateData(chapters), format)
  },

  buildExportPlan(options: ExportPlanOptions): ExportPlan {
    const selectedChapters = normalizeExportChapters(options.selectedChapters)
    if (selectedChapters.length === 0) throw new Error('未选择导出章节')
    const useOriginal = options.format === 'pdf' ? options.useOriginal : true
    const compressionRatio = options.format === 'pdf' ? options.compressionRatio : 1

    if (options.mode === 'merged') {
      if (selectedChapters.length < 2) throw new Error('合并导出至少需要选择两个章节')

      const albumId = selectedChapters[0].albumId
      if (selectedChapters.some((chapter) => chapter.albumId !== albumId)) {
        throw new Error('合并导出只能选择同一本漫画的章节')
      }

      const templateData = ExportService.buildMergedTemplateData(selectedChapters)
      const normalizedEditedPath = normalizeExportDisplayPath(
        options.editedPath,
        options.exportFolder,
        options.exportFolderDisplayPath,
        options.format,
      )
      const task: ExportTask = {
        format: options.format,
        mode: 'merged',
        albumId,
        chapters: selectedChapters.map(({ albumId, chapterId }) => ({ albumId, chapterId })),
        target: normalizedEditedPath.target,
        displayPath: normalizedEditedPath.displayPath,
        useOriginal,
        compressionRatio,
        splitPages: options.splitPages,
        deleteSourceAfterExport: options.deleteSourceAfterExport,
        retainDownloadEntry: options.retainDownloadEntry,
      }

      return {
        tasks: [task],
        outputDisplayPaths: buildExportOutputPaths(
          normalizedEditedPath.displayPath,
          templateData.pageCount,
          options.splitPages,
          options.format,
        ),
      }
    }

    const normalizedEditedPath =
      selectedChapters.length === 1
        ? normalizeExportDisplayPath(
            options.editedPath,
            options.exportFolder,
            options.exportFolderDisplayPath,
            options.format,
          )
        : null
    const tasks = selectedChapters.map<ExportTask>((chapter) => {
      const templatePath = ExportService.buildFullPath(
        ExportService.buildTemplateData(chapter),
        options.format,
      )
      const normalizedPath = normalizedEditedPath ?? {
        displayPath: templatePath,
        target: buildExportTarget(
          templatePath,
          options.exportFolder,
          options.exportFolderDisplayPath,
          options.format,
        ),
      }
      return {
        format: options.format,
        mode: 'chapter',
        albumId: chapter.albumId,
        chapterId: chapter.chapterId,
        displayPath: normalizedPath.displayPath,
        target: normalizedPath.target,
        useOriginal,
        compressionRatio,
        splitPages: options.splitPages,
        deleteSourceAfterExport: options.deleteSourceAfterExport,
        retainDownloadEntry: options.retainDownloadEntry,
      }
    })

    return {
      tasks,
      outputDisplayPaths: tasks.flatMap((task, index) =>
        buildExportOutputPaths(
          task.displayPath,
          selectedChapters[index].totalPages,
          options.splitPages,
          options.format,
        ),
      ),
    }
  },

  /** 用示例数据生成预览 */
  previewPath(format: ExportFormat = 'pdf'): string {
    return ExportService.buildFullPath(EXPORT_SAMPLE_DATA, format)
  },
}
