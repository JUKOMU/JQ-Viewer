import { describe, expect, it } from 'vitest'
import { PDF_SAMPLE_DATA, PdfExportService, buildChapterRange } from '@/services/PdfExportService'
import type { DownloadTask, PdfExportChapter } from '@/services/JmcomicTypes'

function chapter(
  sortOrder: number,
  chapterTitle = `第${sortOrder}话`,
  chapterId = `chapter-${sortOrder}`,
): PdfExportChapter {
  return {
    albumId: 'album-1',
    chapterId,
    chapterTitle,
    sortOrder,
  }
}

describe('buildChapterRange', () => {
  it('combines adjacent numeric chapters into one range', () => {
    expect(buildChapterRange([chapter(2), chapter(3), chapter(4)])).toBe('第2-4话')
  })

  it('keeps non-adjacent ranges separate', () => {
    expect(
      buildChapterRange([chapter(2), chapter(3), chapter(5), chapter(6), chapter(7), chapter(9)]),
    ).toBe('第2-3话+第5-7话+第9话')
  })

  it('preserves input order and uses sanitized titles for non-numeric chapters', () => {
    expect(buildChapterRange([chapter(0, '番外/后日谈', 'extra'), chapter(2), chapter(3)])).toBe(
      '番外_后日谈+第2-3话',
    )
  })

  it('keeps duplicate sort orders as separate titled chapters', () => {
    expect(
      buildChapterRange([
        chapter(2, '第2话 上', 'chapter-2-a'),
        chapter(2, '第2话 下', 'chapter-2-b'),
        chapter(3),
      ]),
    ).toBe('第2话 上+第2话 下+第3话')
  })

  it('falls back to a sanitized chapter id when a title becomes empty', () => {
    expect(buildChapterRange([chapter(Number.NaN, '///', 'extra/one')])).toBe('extra_one')
  })

  it('limits the final range segment length', () => {
    const result = buildChapterRange([chapter(0, '番'.repeat(300), 'extra')])

    expect(result).toHaveLength(255)
    expect(result).toBe('番'.repeat(255))
  })
})

describe('chapterRange template variable', () => {
  it('is registered and rendered', () => {
    expect(PdfExportService.TEMPLATE_VAR_KEYS).toContain('{chapterRange}')
    expect(
      PdfExportService.renderTemplate('{title} {chapterRange}', {
        ...PDF_SAMPLE_DATA,
        chapterRange: '第2-3话+第5话',
      }),
    ).toBe(`${PDF_SAMPLE_DATA.title} 第2-3话+第5话`)
  })

  it('uses the single chapter name in template data', () => {
    const downloadTask: DownloadTask = {
      taskId: 'album-1_chapter-2',
      albumId: 'album-1',
      chapterId: 'chapter-2',
      albumTitle: '测试漫画',
      chapterTitle: '第二章',
      coverUrl: '',
      chapterSortOrder: 2,
      totalPages: 20,
      downloadedPages: 20,
      status: 'completed',
      createdAt: 1,
    }

    const data = PdfExportService.buildTemplateData(downloadTask, null)

    expect(data.chapterRange).toBe('第2话')
    expect(PdfExportService.renderTemplate('{chapterRange}', data)).toBe('第2话')
  })
})
