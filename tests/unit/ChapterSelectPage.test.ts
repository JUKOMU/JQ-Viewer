import { flushPromises, mount } from '@vue/test-utils'
import { expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ sources: vi.fn(), refresh: vi.fn(), toast: vi.fn() }))
vi.mock('@ionic/vue', async () => {
  const { defineComponent, h } = await import('vue')
  return Object.fromEntries(['IonButton', 'IonButtons', 'IonContent', 'IonHeader', 'IonIcon',
    'IonPage', 'IonTitle', 'IonToolbar'].map((name) => [name, defineComponent({
    name, setup: (_, { slots }) => () => h('div', slots.default?.()),
  })]))
})
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { albumId: 'album' } }),
  useRouter: () => ({ push: vi.fn(), back: vi.fn() }),
}))
vi.mock('@/services/ChapterSourceService', () => ({
  ChapterSourceService: { getAlbumSources: mocks.sources },
}))
vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: { getAlbum: async () => ({ photoMetas: [] }), refreshLocalFileAvailability: mocks.refresh },
  getImageUrl: () => '', showToast: mocks.toast,
  sanitizeError: (_error: unknown, fallback: string) => fallback,
}))

import ChapterSelectPage from '@/views/ChapterSelectPage.vue'

test('校验失败仍显示普通下载和保留章节，并标记待校验及提示错误', async () => {
  const ordinary = {
    taskId: 'ordinary', albumId: 'album', chapterId: 'ordinary', albumTitle: 'Album',
    chapterTitle: 'Ordinary', totalPages: 1, downloadedPages: 1, status: 'completed', createdAt: 1,
  }
  const retained = { ...ordinary, taskId: 'retained', chapterId: 'retained', retainedFileStatus: 'available',
    retainedExportCandidates: [{ exportId: 'export', format: 'pdf', fileId: 1,
      fileRef: 'file:path:/book.pdf', startPage: 1, pageCount: 1, availability: 'available' }] }
  mocks.sources.mockResolvedValue(new Map([
    ['ordinary', { download: ordinary }], ['retained', { download: retained }],
  ]))
  mocks.refresh.mockRejectedValue(new Error('bridge failed'))
  const wrapper = mount(ChapterSelectPage)
  await flushPromises()
  expect(wrapper.findAll('.chapter-card')).toHaveLength(2)
  expect(wrapper.text()).toContain('待校验')
  expect(wrapper.text()).toContain('图片')
  expect(mocks.toast).toHaveBeenCalledWith('保留文件校验失败，请重试', 'danger')
  wrapper.unmount()
})
