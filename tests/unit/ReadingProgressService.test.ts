import { beforeEach, describe, expect, test } from 'vitest'
import { ReadingProgressService } from '@/services/ReadingProgressService'

describe('ReadingProgressService', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  test('restores saved progress when route does not specify page', () => {
    ReadingProgressService.recordChapter('album-a', 'chapter-a', 10, 20)

    expect(
      ReadingProgressService.getInitialChapterPage(undefined, 'album-a', 'chapter-a', 20),
    ).toBe(10)
  })

  test('route page overrides saved progress', () => {
    ReadingProgressService.recordChapter('album-a', 'chapter-a', 10, 20)

    expect(ReadingProgressService.getInitialChapterPage('3', 'album-a', 'chapter-a', 20)).toBe(3)
  })

  test('keeps chapters isolated within the same album', () => {
    ReadingProgressService.recordChapter('album-a', 'chapter-a', 8, 20)

    expect(
      ReadingProgressService.getInitialChapterPage(undefined, 'album-a', 'chapter-b', 20),
    ).toBe(1)
  })

  test('clamps page by total pages', () => {
    ReadingProgressService.recordChapter('album-a', 'chapter-a', 99, 12)

    expect(ReadingProgressService.getChapter('album-a', 'chapter-a')?.page).toBe(12)
    expect(ReadingProgressService.getInitialChapterPage('99', 'album-a', 'chapter-a', 12)).toBe(12)
  })

  test('maps logical chapter pages to merged file pages', () => {
    const chapter = {
      albumId: 'album-a',
      chapterId: 'chapter-b',
      startPage: 11,
      endPage: 15,
      pageCount: 5,
    }

    expect(ReadingProgressService.chapterToFilePage(chapter, 3)).toBe(13)
    expect(ReadingProgressService.fileToChapterPage(chapter, 13)).toBe(3)
    expect(ReadingProgressService.fileToChapterPage(chapter, 10)).toBeNull()
  })

  test('seeds a single-file progress once from legacy chapter progress', () => {
    const chapter = {
      sequence: 0,
      albumId: 'album-a',
      chapterId: 'chapter-a',
      chapterTitle: 'Chapter',
      sortOrder: 1,
      startPage: 6,
      endPage: 15,
      pageCount: 10,
    }
    ReadingProgressService.recordChapter('album-a', 'chapter-a', 4, 10)

    expect(
      ReadingProgressService.getInitialFilePage({
        routePage: undefined,
        fileId: 7,
        totalPages: 20,
        chapter,
      }),
    ).toBe(9)

    ReadingProgressService.recordChapter('album-a', 'chapter-a', 8, 10)
    expect(
      ReadingProgressService.getInitialFilePage({
        routePage: undefined,
        fileId: 7,
        totalPages: 20,
        chapter,
      }),
    ).toBe(9)
  })
})
