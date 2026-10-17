import { ReadingProgressService } from './ReadingProgressService'
import type { LocalFileChapter } from './JmcomicTypes'

export interface LocalReaderSession {
  fileId: number
  scope: 'chapter' | 'file'
  chapter?: LocalFileChapter
  totalPages: number
  initialPage(routePage: unknown): number
  physicalPage(page: number): number
  recordPage(page: number): void
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
  const legacyChapterRoute = !query.readingContext && Boolean(albumId && chapterId)
  const chapterStartPage = explicitChapterStartPage || (legacyChapterRoute ? 1 : 0)
  const requestedChapterPages =
    explicitChapterPageCount || (legacyChapterRoute ? fileTotalPages : 0)
  const availableChapterPages = Math.max(0, fileTotalPages - chapterStartPage + 1)
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
    physicalPage(page: number) {
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
