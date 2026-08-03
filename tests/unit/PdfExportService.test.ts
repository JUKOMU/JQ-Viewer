import { afterEach, describe, expect, it } from 'vitest'
import {
  PDF_SAMPLE_DATA,
  PdfExportService,
  buildChapterRange,
  buildPdfOutputPaths,
  normalizePdfChapters,
} from '@/services/PdfExportService'
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

function downloadTask(sortOrder: number | undefined, id = String(sortOrder)): DownloadTask {
  return {
    taskId: `album-1_${id}`,
    albumId: 'album-1',
    chapterId: id,
    albumTitle: '测试漫画',
    chapterTitle: `章节 ${id}`,
    coverUrl: '',
    chapterSortOrder: sortOrder,
    totalPages: 20,
    downloadedPages: 20,
    status: 'completed',
    createdAt: 1,
  }
}

afterEach(() => {
  localStorage.clear()
})

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

describe('PDF export plan', () => {
  it('sorts numeric chapters while preserving invalid chapter positions and duplicate order', () => {
    const normalized = normalizePdfChapters([
      downloadTask(3, 'chapter-3'),
      downloadTask(undefined, 'extra'),
      downloadTask(2, 'chapter-2-a'),
      downloadTask(2, 'chapter-2-b'),
    ])

    expect(normalized.map((item) => item.chapterId)).toEqual([
      'chapter-2-a',
      'extra',
      'chapter-2-b',
      'chapter-3',
    ])
  })

  it('builds merged template data and a default path with chapterRange', () => {
    const chapters = [downloadTask(3, 'chapter-3'), downloadTask(2, 'chapter-2')]
    const data = PdfExportService.buildMergedTemplateData(chapters, null)

    expect(data.chapterRange).toBe('第2-3话')
    expect(data.pageCount).toBe(40)
    expect(PdfExportService.buildMergedFullPath(chapters, null)).toContain('第2-3话.pdf')
  })

  it('builds one normalized merged task and predicts all split output paths', () => {
    const chapter3 = downloadTask(3, 'chapter-3')
    chapter3.totalPages = 30
    const plan = PdfExportService.buildExportPlan({
      mode: 'merged',
      selectedChapters: [chapter3, downloadTask(2, 'chapter-2')],
      albumDetail: null,
      useOriginal: true,
      compressionRatio: 0.5,
      editedPath: '/exports/merged.pdf',
      splitPages: 25,
    })

    expect(plan.tasks).toEqual([
      expect.objectContaining({
        mode: 'merged',
        albumId: 'album-1',
        chapterTitle: '第2-3话',
        savePath: '/exports/merged.pdf',
      }),
    ])
    expect(plan.tasks[0]).not.toHaveProperty('chapterId')
    expect(plan.tasks[0].chapters?.map((item) => item.chapterId)).toEqual([
      'chapter-2',
      'chapter-3',
    ])
    expect(plan.outputPaths).toEqual(['/exports/merged_001-025.pdf', '/exports/merged_026-050.pdf'])
  })

  it('keeps chapter mode as one task per selected chapter', () => {
    const plan = PdfExportService.buildExportPlan({
      mode: 'chapter',
      selectedChapters: [downloadTask(2, 'chapter-2'), downloadTask(3, 'chapter-3')],
      albumDetail: null,
      useOriginal: false,
      compressionRatio: 0.4,
      editedPath: '/exports/preview.pdf',
      splitPages: 0,
    })

    expect(plan.tasks).toHaveLength(2)
    expect(plan.tasks.map((task) => task.mode)).toEqual(['chapter', 'chapter'])
    expect(plan.tasks.map((task) => task.chapterId)).toEqual(['chapter-2', 'chapter-3'])
    expect(plan.outputPaths).toEqual(plan.tasks.map((task) => task.savePath))
  })

  it('rejects merged mode with fewer than two chapters', () => {
    expect(() =>
      PdfExportService.buildExportPlan({
        mode: 'merged',
        selectedChapters: [downloadTask(2, 'chapter-2')],
        albumDetail: null,
        useOriginal: true,
        compressionRatio: 0.5,
        editedPath: '/exports/merged.pdf',
        splitPages: 0,
      }),
    ).toThrow('合并导出至少需要选择两个章节')
  })
})

describe('buildPdfOutputPaths', () => {
  it('keeps the base path when splitting produces only one volume', () => {
    expect(buildPdfOutputPaths('/exports/chapter.pdf', 100, 100)).toEqual(['/exports/chapter.pdf'])
  })

  it('matches the native range suffix for multiple volumes', () => {
    expect(buildPdfOutputPaths('/exports/chapter.pdf', 101, 100)).toEqual([
      '/exports/chapter_001-100.pdf',
      '/exports/chapter_101-101.pdf',
    ])
  })
})
