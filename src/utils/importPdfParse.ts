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
  /** 章节序号（行末尾 ` 数字`） */
  chapterSortOrder?: number
}

export interface ImportPdfParseResult {
  files: PdfFileParseItem[]
}

/**
 * 从文件名列表中提取 ID。
 * 每个文件独立解析，不跨文件合并数字串。
 */
export function parseFilenamesForImport(filePaths: string[]): ImportPdfParseResult {
  const files: PdfFileParseItem[] = []
  const idToFileIndices = new Map<string, number[]>()

  for (const filePath of filePaths) {
    const fileName = extractFileName(filePath)
    const { ids, idPositions } = extractValidIds(fileName)

    let status: 'resolved' | 'ambiguous' | 'missing'
    if (ids.length === 0) {
      status = 'missing'
    } else if (ids.length === 1) {
      status = 'resolved'
    } else {
      status = 'ambiguous'
    }

    for (const id of ids) {
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
): { ids: string[]; chapterSortOrder?: number } {
  // 尝试从末尾提取章节序号
  const match = lineText.match(/^(.+?)\s+(\d{1,4})$/)
  let idText: string
  let chapterSortOrder: number | undefined

  if (match) {
    idText = match[1]
    chapterSortOrder = parseInt(match[2], 10)
  } else {
    idText = lineText
    chapterSortOrder = undefined
  }

  const { ids } = extractValidIds(idText)
  return { ids, chapterSortOrder }
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
    ids.push(numStr)
    idPositions.push({
      id: numStr,
      start: match.index,
      end: match.index + numStr.length,
    })
  }

  return { ids, idPositions }
}
