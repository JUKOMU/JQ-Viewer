import type { AlbumDetail } from '@/services/JmcomicTypes'

/**
 * 从文件名提取 ID 的解析结果。
 * 与 batchParse.ts 不同：每个文件独立解析，不跨行合并数字串。
 */

export interface PdfFileParseItem {
  /** 原始文件名（不含路径） */
  fileName: string
  /** 完整文件路径 */
  filePath: string
  /** 提取的所有有效数字串（已过滤 0 开头和 <3 位） */
  extractedIds: string[]
  /** 每个 ID 在文件名中的起止位置 */
  idPositions: { id: string; start: number; end: number }[]
  /** 解析状态 */
  status: 'resolved' | 'ambiguous' | 'missing'
  /** 与其他文件重复的 ID */
  duplicateIds: string[]
  /** 从 API 获取的相册详情 */
  albumDetail?: AlbumDetail | null
  /** 用户编辑后的修订文本（编辑模式下使用） */
  editedLine?: string
  /** 用户编辑后重新解析出的 ID 列表 */
  editedIds?: string[]
  /** 文件名或编辑文本中解析出的章节序号线索 */
  chapterSortOrderHint?: number
  /** 已匹配或用户选择的章节序号 */
  chapterSortOrder?: number
  /** 匹配到的实际章节 ID */
  chapterId?: string
  /** 匹配到的章节标题 */
  chapterTitle?: string
}

export interface ImportPdfParseResult {
  files: PdfFileParseItem[]
}

/**
 * 从文件名列表中提取 ID。
 * 每个文件独立解析，不跨文件合并数字串。
 */
export function parseFilenamesForImport(filePaths: string[], fileNames?: string[]): ImportPdfParseResult {
  const files: PdfFileParseItem[] = []
  const idToFileIndices = new Map<string, number[]>()

  for (let i = 0; i < filePaths.length; i++) {
    const filePath = filePaths[i]
    const fileName = fileNames?.[i] ?? extractFileName(filePath)
    const { text, chapterSortOrderHint } = extractChapterHint(fileName)
    const { ids, idPositions } = extractValidIds(text)

    let status: 'resolved' | 'ambiguous' | 'missing'
    if (ids.length === 0) {
      status = 'missing'
    } else if (ids.length === 1) {
      status = 'resolved'
    } else {
      status = 'ambiguous'
    }

    for (const id of new Set(ids)) {
      const indices = idToFileIndices.get(id) || []
      indices.push(files.length)
      idToFileIndices.set(id, indices)
    }

    files.push({
      fileName,
      filePath,
      extractedIds: ids,
      idPositions,
      status,
      duplicateIds: [],
      chapterSortOrderHint,
    })
  }

  // 全局去重检测
  for (const [id, indices] of idToFileIndices) {
    if (indices.length > 1) {
      for (const idx of indices) {
        files[idx].duplicateIds.push(id)
      }
    }
  }

  return { files }
}

/**
 * 从用户编辑后的行文本中解析 ID 和章节序号。
 * 格式: `id文本 [chapterSortOrder]`
 * 章节序号为行末尾空格后的数字。
 */
export function parseEditedLine(
  lineText: string,
): { ids: string[]; chapterSortOrderHint?: number } {
  // 尝试从末尾提取章节序号
  const match = lineText.match(/^(.+?)\s+(\d{1,4})$/)
  let idText: string
  let chapterSortOrderHint: number | undefined

  if (match) {
    idText = match[1]
    chapterSortOrderHint = parseInt(match[2], 10)
  } else {
    const extracted = extractChapterHint(lineText)
    idText = extracted.text
    chapterSortOrderHint = extracted.chapterSortOrderHint
  }

  const { ids } = extractValidIds(idText)
  return { ids, chapterSortOrderHint }
}

// ---- 内部辅助 ----

function extractFileName(filePath: string): string {
  const lastSep = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'))
  return lastSep >= 0 ? filePath.substring(lastSep + 1) : filePath
}

function extractValidIds(text: string): {
  ids: string[]
  idPositions: { id: string; start: number; end: number }[]
} {
  const ids: string[] = []
  const idPositions: { id: string; start: number; end: number }[] = []
  const regex = /\d+/g
  let match: RegExpExecArray | null

  while ((match = regex.exec(text)) !== null) {
    const numStr = match[0]
    // 过滤以 '0' 开头的数字串
    if (numStr.startsWith('0')) continue
    // 过滤长度 < 3 的数字串
    if (numStr.length < 3) continue
    if (!ids.includes(numStr)) {
      ids.push(numStr)
      idPositions.push({
        id: numStr,
        start: match.index,
        end: match.index + numStr.length,
      })
    }
  }

  return { ids, idPositions }
}

function extractChapterHint(text: string): {
  text: string
  chapterSortOrderHint?: number
} {
  const match = text.match(/第\s*([0-9０-９]{1,4})\s*[话話]/)
  if (!match || match.index === undefined) {
    return { text }
  }

  const normalized = normalizeDigits(match[1])
  const value = parseInt(normalized, 10)
  if (!Number.isFinite(value) || value <= 0) {
    return { text }
  }

  return {
    text: text.slice(0, match.index) + text.slice(match.index + match[0].length),
    chapterSortOrderHint: value,
  }
}

function normalizeDigits(text: string): string {
  return text.replace(/[０-９]/g, (char) =>
    String.fromCharCode(char.charCodeAt(0) - 0xfee0),
  )
}
