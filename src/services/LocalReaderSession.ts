import { ReadingProgressService } from './ReadingProgressService'
import type { LocalFileChapter } from './JmcomicTypes'

export interface LocalReaderSession {
  fileId: number
  scope: 'chapter' | 'file'
  chapter?: LocalFileChapter
  totalPages: number
  initialPage(routePage: unknown): number
  fileRefForPage(page: number): string
  physicalPage(page: number): number
  recordPage(page: number): void
}

interface RetainedVolume {
  fileRef: string
  startPage: number
  pageCount: number
}

function positiveInteger(value: unknown): number {
  const number = Number(value)
  return Number.isInteger(number) && number > 0 ? number : 0
}

export function createLocalReaderSession(
  query: Record<string, unknown>,
  fileTotalPages: number,
): LocalReaderSession {
  const fileId = positiveInteger(query.fileId)
  const albumId = String(query.albumId ?? '')
  const chapterId = String(query.chapterId ?? query.albumId ?? '')
  const explicitChapterStartPage = positiveInteger(query.chapterStartPage)
  const explicitChapterPageCount = positiveInteger(query.chapterPageCount)
  const retainedVolumes = JSON.parse(String(query.retainedVolumes ?? '[]')) as RetainedVolume[]
  const legacyChapterRoute = !query.readingContext && Boolean(albumId && chapterId)
  const chapterStartPage = explicitChapterStartPage || (legacyChapterRoute ? 1 : 0)
  const requestedChapterPages =
    explicitChapterPageCount || (legacyChapterRoute ? fileTotalPages : 0)
  const availableChapterPages = retainedVolumes.length
    ? retainedVolumes.reduce((count, volume) => count + volume.pageCount, 0)
    : Math.max(0, fileTotalPages - chapterStartPage + 1)
  const chapterPageCount = Math.min(requestedChapterPages, availableChapterPages)
  const hasChapter = Boolean(albumId && chapterId && chapterStartPage && chapterPageCount)
  const chapter: LocalFileChapter | undefined = hasChapter
    ? {
        sequence: 0,
        albumId,
        chapterId,
        chapterTitle: String(query.chapterTitle ?? ''),
        sortOrder: 0,
        startPage: chapterStartPage,
        endPage: chapterStartPage + chapterPageCount - 1,
        pageCount: chapterPageCount,
      }
    : undefined
  const scope = query.readingContext !== 'file' && chapter ? 'chapter' : 'file'
  const totalPages = scope === 'chapter' ? chapter!.pageCount : fileTotalPages

  return {
    fileId,
    scope,
    chapter,
    totalPages,
    initialPage(routePage: unknown) {
      if (scope === 'chapter') {
        return ReadingProgressService.getInitialChapterPage(
          routePage,
          albumId,
          chapterId,
          totalPages,
        )
      }
      return ReadingProgressService.getInitialFilePage({
        routePage,
        fileId,
        totalPages,
        chapter,
      })
    },
    fileRefForPage(page: number) {
      if (!retainedVolumes.length) return String(query.fileRef ?? '')
      // 分卷的起始页各自属于对应文件，按章节逻辑页定位文件后再换算卷内页码。
      let volumeStart = 1
      for (const volume of retainedVolumes) {
        if (page < volumeStart + volume.pageCount) return volume.fileRef
        volumeStart += volume.pageCount
      }
      return retainedVolumes[retainedVolumes.length - 1].fileRef
    },
    physicalPage(page: number) {
      if (retainedVolumes.length && scope === 'chapter') {
        let volumeStart = 1
        for (const volume of retainedVolumes) {
          if (page < volumeStart + volume.pageCount) {
            return volume.startPage + page - volumeStart
          }
          volumeStart += volume.pageCount
        }
        return page
      }
      return scope === 'chapter' ? ReadingProgressService.chapterToFilePage(chapter!, page) : page
    },
    recordPage(page: number) {
      if (scope === 'chapter') {
        ReadingProgressService.recordChapter(albumId, chapterId, page, totalPages)
      } else {
        ReadingProgressService.recordFile(fileId, page, totalPages)
      }
    },
  }
}
