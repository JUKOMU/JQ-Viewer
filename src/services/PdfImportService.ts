import { JmcomicService } from './JmcomicService'
import { parseFilenamesForImport } from '@/utils/importPdfParse'
import type { PdfFileParseItem, ImportPdfParseResult } from '@/utils/importPdfParse'
import type { ImportPdfItem, ImportPdfsResult } from './JmcomicTypes'

// ========== 跨页面数据传递 ==========
// 扫描结果暂存在此模块级变量，避免通过路由 query 传递路径（路径含特殊字符不可靠）

let cachedParseResult: ImportPdfParseResult | null = null

export function getCachedParseResult(): ImportPdfParseResult | null {
  return cachedParseResult
}

export function clearCachedParseResult(): void {
  cachedParseResult = null
}

// ========== 导入流程 ==========

/** 步骤 1：扫描文件夹并解析文件名 */
async function scanAndParse(folderPath: string, treeUri?: string): Promise<ImportPdfParseResult> {
  const result = await JmcomicService.scanPdfFiles(folderPath, treeUri)
  const filePaths = result.files.map((f) => f.filePath)
  const fileNames = result.files.map((f) => f.fileName)
  const parseResult = parseFilenamesForImport(filePaths, fileNames)
  cachedParseResult = parseResult
  return parseResult
}

/** 步骤 2：并发获取相册详情（仅 resolved 文件，网络失败静默跳过） */
async function fetchAlbumDetails(files: PdfFileParseItem[]): Promise<void> {
  const resolvedFiles = files.filter((f) => f.status === 'resolved' && f.extractedIds.length === 1)
  if (resolvedFiles.length === 0) return

  const concurrency = 5
  let index = 0

  async function worker(): Promise<void> {
    while (index < resolvedFiles.length) {
      const i = index++
      const file = resolvedFiles[i]
      const id = file.extractedIds[0]
      try {
        const detail = await JmcomicService.getAlbum(id)
        file.albumDetail = detail && detail.id ? detail : null
      } catch {
        // 网络失败静默跳过，保留 albumId，封面/标题留空
        file.albumDetail = null
      }
    }
  }

  const workers = Array.from({ length: Math.min(concurrency, resolvedFiles.length) }, () =>
    worker(),
  )
  await Promise.all(workers)
}

/** 步骤 3：确认导入 */
async function confirmImport(
  resolvedFiles: PdfFileParseItem[],
  folderId?: string,
): Promise<ImportPdfsResult> {
  const items: ImportPdfItem[] = resolvedFiles
    .filter((f) => f.editedIds && f.editedIds.length === 1)
    .map((f) => {
      const chapter = resolveImportedChapter(f)
      return {
        filePath: f.filePath,
        fileName: f.fileName,
        albumId: f.editedIds![0],
        albumTitle: f.albumDetail?.title || '',
        coverUrl: f.albumDetail?.image || '',
        authors: f.albumDetail?.authors?.join(',') || '',
        chapterId: f.chapterId || chapter?.id || f.editedIds![0],
        chapterTitle: f.chapterTitle || chapter?.title || '',
        chapterSortOrder: f.chapterSortOrder ?? chapter?.sortOrder ?? 0,
        ...(folderId ? { folderId } : {}),
      }
    })

  if (items.length === 0) {
    return { imported: 0, skipped: resolvedFiles.length, duplicateCount: 0, errorCount: 0 }
  }

  // 检查文件是否仍存在
  let validItems = items
  let missingCount = 0
  try {
    const paths = items.map((f) => f.filePath)
    const { existing } = await JmcomicService.checkFilesExist(paths)
    const existingSet = new Set(existing)
    missingCount = items.filter((f) => !existingSet.has(f.filePath)).length
    if (missingCount > 0) {
      validItems = items.filter((f) => existingSet.has(f.filePath))
    }
  } catch {
    // 检查失败则直接放行
  }

  if (validItems.length === 0) {
    return { imported: 0, skipped: items.length, duplicateCount: 0, errorCount: items.length }
  }

  const result = await JmcomicService.importPdfs(validItems)
  if (missingCount > 0) {
    result.errorCount += missingCount
    result.skipped += missingCount
  }

  return result
}

function resolveImportedChapter(file: PdfFileParseItem) {
  const chapters = file.albumDetail?.photoMetas ?? []
  if (file.chapterSortOrder !== undefined) {
    return chapters.find((chapter) => chapter.sortOrder === file.chapterSortOrder) ?? null
  }
  return chapters.length === 1 ? chapters[0] : null
}

export const PdfImportService = {
  scanAndParse,
  fetchAlbumDetails,
  confirmImport,
  getCachedParseResult,
  clearCachedParseResult,
}
