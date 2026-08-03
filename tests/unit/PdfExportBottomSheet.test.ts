import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import PdfExportBottomSheet from '@/components/download/PdfExportBottomSheet.vue'
import { JmcomicService } from '@/services/JmcomicService'
import type { DownloadTask, PdfExportMode } from '@/services/JmcomicTypes'

type ConfirmPayload = {
  selectedChapters: DownloadTask[]
  mode: PdfExportMode
  useOriginal: boolean
  compressionRatio: number
  editedPath: string
  splitPages: number
}

vi.mock('@ionic/vue', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@ionic/vue')
  return { ...actual, useBackButton: vi.fn() }
})

function downloadTask(sortOrder: number, chapterId: string): DownloadTask {
  return {
    taskId: `album-1_${chapterId}`,
    albumId: 'album-1',
    chapterId,
    albumTitle: '测试漫画',
    chapterTitle: `章节 ${chapterId}`,
    coverUrl: '',
    chapterSortOrder: sortOrder,
    totalPages: 20,
    downloadedPages: 20,
    status: 'completed',
    createdAt: 1,
  }
}

const chapters = [
  downloadTask(3, 'chapter-3'),
  downloadTask(2, 'chapter-2'),
  downloadTask(5, 'chapter-5'),
]

async function mountSheet() {
  vi.spyOn(JmcomicService, 'getAlbum').mockRejectedValue(new Error('offline'))
  const wrapper = mount(PdfExportBottomSheet, {
    props: { modelValue: false, chapters },
    global: {
      stubs: {
        Teleport: true,
        IonRange: true,
        IonToggle: true,
      },
    },
  })
  await wrapper.setProps({ modelValue: true })
  return wrapper
}

afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
  document.body.innerHTML = ''
})

describe('PdfExportBottomSheet', () => {
  test('默认使用单章模式并按章节序号显示选择列表', async () => {
    const wrapper = await mountSheet()

    expect(wrapper.get<HTMLInputElement>('input[value="chapter"]').element.checked).toBe(true)
    expect(wrapper.findAll('.chapter-name').map((item) => item.text())).toEqual([
      'chapter-2',
      'chapter-3',
      'chapter-5',
    ])
  })

  test('合并模式预览章节范围并按规范顺序发出确认事件', async () => {
    const wrapper = await mountSheet()

    await wrapper.get('input[value="merged"]').trigger('change')

    expect(wrapper.get<HTMLInputElement>('.path-input').element.value).toContain(
      '第2-3话+第5话.pdf',
    )
    await wrapper.get('.btn-confirm').trigger('click')

    const payload = wrapper.emitted('confirm')?.[0]?.[0] as ConfirmPayload | undefined
    expect(payload).toEqual(
      expect.objectContaining({
        mode: 'merged',
        selectedChapters: expect.any(Array),
      }),
    )
    expect(payload?.selectedChapters.map((item) => item.chapterId)).toEqual([
      'chapter-2',
      'chapter-3',
      'chapter-5',
    ])
  })

  test('合并模式少于两个章节时自动回退到单章模式', async () => {
    const wrapper = await mountSheet()

    await wrapper.get('input[value="merged"]').trigger('change')
    const checkboxes = wrapper.findAll('.chapter-check')
    await checkboxes[0].trigger('change')
    await checkboxes[1].trigger('change')

    expect(wrapper.get<HTMLInputElement>('input[value="chapter"]').element.checked).toBe(true)
    expect(wrapper.get<HTMLInputElement>('input[value="merged"]').element.disabled).toBe(true)
    await wrapper.get('.btn-confirm').trigger('click')
    expect(wrapper.emitted('confirm')?.[0]?.[0]).toEqual(
      expect.objectContaining({ mode: 'chapter' }),
    )
  })
})
