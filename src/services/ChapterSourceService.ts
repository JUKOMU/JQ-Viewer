import type { RouteLocationRaw } from 'vue-router'
import { JmcomicService } from './JmcomicService'
import type { DownloadTask, LocalFileChapter, LocalFileRecord } from './JmcomicTypes'

export type ChapterSourceKind = 'download' | 'cbz' | 'pdf' | 'network'

export interface LocalChapterSource {
  kind: 'cbz' | 'pdf'
  file: LocalFileRecord
  chapter: LocalFileChapter
}

export interface DownloadChapterSource {
  kind: 'download'
  task: DownloadTask
}

export interface NetworkChapterSource {
  kind: 'network'
}

export type ChapterSource = LocalChapterSource | DownloadChapterSource | NetworkChapterSource

export interface ChapterSourceSummary {
  download?: DownloadTask
  cbz?: LocalChapterSource
  pdf?: LocalChapterSource
}

export interface ReaderMetadata {
  albumId: string
  albumTitle: string
  chapterId: string
  chapterTitle: string
  authors: string
  coverUrl: string
  totalPages?: number
}

const LOCAL_PAGE_SIZE = 100

function compareLocalCandidates(left: LocalChapterSource, right: LocalChapterSource): number {
  return right.file.updatedAt - left.file.updatedAt || right.file.id - left.file.id
}

function isUsable(file: LocalFileRecord): boolean {
  return (
    file.availability === 'available' &&
    file.verificationStatus !== 'corrupt' &&
    file.verificationStatus !== 'page_mismatch'
  )
}

function chapterFor(file: LocalFileRecord, albumId: string, chapterId: string) {
  return file.chapters.find(
    (chapter) => chapter.albumId === albumId && chapter.chapterId === chapterId,
  )
}

async function listLocalCandidates(albumId: string): Promise<LocalChapterSource[]> {
  const candidates: LocalChapterSource[] = []
  let cursor: string | undefined
  do {
    const page = await JmcomicService.getLocalFiles({
      formats: ['pdf', 'cbz'],
      albumId,
      cursor,
      limit: LOCAL_PAGE_SIZE,
    })
    for (const file of page.files) {
      if (!isUsable(file)) continue
      for (const chapter of file.chapters) {
        if (chapter.albumId !== albumId) continue
        if (file.format !== 'pdf' && file.format !== 'cbz') continue
        candidates.push({ kind: file.format, file, chapter })
      }
    }
    cursor = page.nextCursor
  } while (cursor)
  return candidates.sort(compareLocalCandidates)
}

async function listChapterCandidates(
  kind: 'cbz' | 'pdf',
  albumId: string,
  chapterId: string,
): Promise<LocalChapterSource[]> {
  const candidates: LocalChapterSource[] = []
  let cursor: string | undefined
  do {
    const page = await JmcomicService.getLocalFiles({
      formats: [kind],
      albumId,
      chapterId,
      cursor,
      limit: LOCAL_PAGE_SIZE,
    })
    for (const file of page.files) {
      const chapter = chapterFor(file, albumId, chapterId)
      if (chapter) candidates.push({ kind, file, chapter })
    }
    cursor = page.nextCursor
  } while (cursor)
  return candidates.sort(compareLocalCandidates)
}

async function listCompletedDownloads(albumId: string): Promise<DownloadTask[]> {
  const result = await JmcomicService.getDownloadTasks()
  return result.tasks
    .filter((task) => task.albumId === albumId && task.status === 'completed')
    .sort(
      (left, right) =>
        (right.completedAt ?? right.createdAt) - (left.completedAt ?? left.createdAt),
    )
}

async function verifyLocalCandidate(
  source: LocalChapterSource,
): Promise<LocalChapterSource | null> {
  try {
    const file = await JmcomicService.verifyLocalFile(source.file.id)
    if (!isUsable(file)) return null
    const chapter = chapterFor(file, source.chapter.albumId, source.chapter.chapterId)
    if (!chapter) return null
    if (file.format === 'cbz') await JmcomicService.getCbzInfo(file.fileRef)
    else await JmcomicService.getPdfInfo(file.fileRef)
    return { kind: source.kind, file, chapter }
  } catch {
    return null
  }
}

export const ChapterSourceService = {
  async getAlbumSources(albumId: string): Promise<Map<string, ChapterSourceSummary>> {
    const [downloads, localCandidates] = await Promise.all([
      listCompletedDownloads(albumId).catch(() => []),
      listLocalCandidates(albumId).catch(() => []),
    ])
    const result = new Map<string, ChapterSourceSummary>()
    for (const task of downloads) {
      const summary = result.get(task.chapterId) ?? {}
      summary.download ??= task
      result.set(task.chapterId, summary)
    }
    for (const source of localCandidates) {
      const summary = result.get(source.chapter.chapterId) ?? {}
      if (source.kind === 'cbz') summary.cbz ??= source
      else summary.pdf ??= source
      result.set(source.chapter.chapterId, summary)
    }
    return result
  },

  async resolve(
    albumId: string,
    chapterId: string,
    requested: ChapterSourceKind | 'preferred' = 'preferred',
  ): Promise<ChapterSource | null> {
    if (requested === 'network') return { kind: 'network' }

    const summaries = await this.getAlbumSources(albumId)
    const summary = summaries.get(chapterId)
    if (!summary) return requested === 'preferred' ? { kind: 'network' } : null

    if ((requested === 'preferred' || requested === 'download') && summary.download) {
      try {
        const photo = await JmcomicService.getDownloadedPhoto(albumId, chapterId)
        if (photo.images.length > 0) return { kind: 'download', task: summary.download }
      } catch {
        if (requested === 'download') return null
      }
    }

    for (const kind of ['cbz', 'pdf'] as const) {
      if (requested !== 'preferred' && requested !== kind) continue
      const preferred = summary[kind]
      if (!preferred) {
        if (requested === kind) return null
        continue
      }
      const candidates = await listChapterCandidates(kind, albumId, chapterId)
      for (const candidate of candidates) {
        const verified = await verifyLocalCandidate(candidate)
        if (verified) return verified
      }
      if (requested === kind) return null
    }

    return requested === 'preferred' ? { kind: 'network' } : null
  },

  async resolveFile(fileId: number): Promise<LocalFileRecord | null> {
    try {
      const page = await JmcomicService.getLocalFiles({ fileId, limit: 1 })
      const file = page.files[0]
      if (!file || file.format === 'zip') return null
      const verified = await JmcomicService.verifyLocalFile(file.id)
      if (!isUsable(verified)) return null
      if (verified.format === 'cbz') await JmcomicService.getCbzInfo(verified.fileRef)
      else await JmcomicService.getPdfInfo(verified.fileRef)
      return verified
    } catch {
      return null
    }
  },

  readerLocation(source: ChapterSource, metadata: ReaderMetadata, page?: number): RouteLocationRaw {
    if (source.kind === 'network' || source.kind === 'download') {
      const totalPages =
        metadata.totalPages ?? (source.kind === 'download' ? source.task.totalPages : 0)
      return {
        path: `/album/${metadata.albumId}/read/${metadata.chapterId}`,
        query: {
          title: metadata.chapterTitle || metadata.albumTitle,
          total: String(totalPages),
          ...(page ? { page: String(page) } : {}),
          ...(source.kind === 'download' ? { source: 'download' } : {}),
        },
      }
    }
    return {
      path: source.kind === 'cbz' ? '/cbz-reader' : '/pdf-reader',
      query: {
        fileRef: String(source.file.fileRef),
        fileId: String(source.file.id),
        readingContext: 'chapter',
        title: metadata.chapterTitle || source.chapter.chapterTitle || source.file.fileName,
        albumId: metadata.albumId,
        albumTitle: metadata.albumTitle || source.file.albumTitle,
        authors: metadata.authors || source.file.authors,
        coverUrl: metadata.coverUrl || source.file.coverUrl,
        chapterId: metadata.chapterId,
        chapterTitle: metadata.chapterTitle || source.chapter.chapterTitle,
        chapterStartPage: String(source.chapter.startPage),
        chapterPageCount: String(source.chapter.pageCount),
        ...(page ? { page: String(page) } : {}),
      },
    }
  },

  fileReaderLocation(file: LocalFileRecord, page?: number): RouteLocationRaw {
    const chapter = file.chapters.length === 1 ? file.chapters[0] : undefined
    return {
      path: file.format === 'cbz' ? '/cbz-reader' : '/pdf-reader',
      query: {
        fileRef: String(file.fileRef),
        fileId: String(file.id),
        readingContext: 'file',
        title: file.fileName,
        albumId: file.albumId,
        albumTitle: file.albumTitle,
        authors: file.authors,
        coverUrl: file.coverUrl,
        chapterId: chapter?.chapterId ?? '',
        chapterTitle: chapter?.chapterTitle ?? '',
        chapterStartPage: chapter ? String(chapter.startPage) : '',
        chapterPageCount: chapter ? String(chapter.pageCount) : '',
        ...(page ? { page: String(page) } : {}),
      },
    }
  },
}
