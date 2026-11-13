import { beforeEach, describe, expect, it } from 'vitest'
import { createLocalReaderSession } from './LocalReaderSession'

describe('LocalReaderSession retained export volumes', () => {
  beforeEach(() => localStorage.clear())

  it('maps chapter pages to each volume file and its physical page', () => {
    const session = createLocalReaderSession(
      {
        fileId: '7',
        fileRef: 'file:first',
        readingContext: 'chapter',
        albumId: 'album',
        chapterId: 'chapter',
        chapterStartPage: '1',
        chapterPageCount: '5',
        retainedVolumes: JSON.stringify([
          { fileRef: 'file:first', startPage: 2, pageCount: 2 },
          { fileRef: 'file:second', startPage: 1, pageCount: 3 },
        ]),
      },
      5,
    )

    expect(session.totalPages).toBe(5)
    expect([1, 2, 3, 5].map((page) => session.fileRefForPage(page))).toEqual([
      'file:first',
      'file:first',
      'file:second',
      'file:second',
    ])
    expect([1, 2, 3, 5].map((page) => session.physicalPage(page))).toEqual([2, 3, 1, 3])
  })
})
