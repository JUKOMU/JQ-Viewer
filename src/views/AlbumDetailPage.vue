<template>
  <IonPage>
    <IonContent ref="contentRef" :scroll-events="true" @ion-scroll="handleScroll">
      <!-- 区域 A：封面头部 -->
      <AlbumHeader
        ref="headerRef"
        :cover-url="coverUrl"
        :title="albumTitle"
        :authors="albumAuthors"
        :page-count="selectedChapterPageCount"
        :loading="loading"
        :chapter-loading="chapterLoading"
        :source-menu-open="sourceMenuOpen"
        :network-available="networkAvailable"
        :image-available="selectedChapterHasDownload"
        :pdf-available="Boolean(selectedChapterPdf)"
        @back="goBack"
        @start-reading="startReading"
        @toggle-source-menu="toggleSourceMenu"
        @select-source="openReaderBySource"
      />

      <div ref="tabGestureRef" class="tab-gesture-area">
        <!-- 区域 B：Tab 栏 -->
        <div
          ref="tabBarRef"
          class="tab-bar"
          :class="{ sticky: tabBarSticky, swiping: tabSwipeActive, settling: tabSwipeSettling }"
          :style="tabBarStyle"
        >
          <div class="tab-active-indicator"/>
          <button
            v-for="(tab, index) in tabs"
            :key="tab.key"
            type="button"
            class="tab-btn"
            :class="{ active: activeTab === tab.key }"
            :style="getTabButtonStyle(index)"
            @click="switchTab(tab.key)"
          >
            {{ tab.label }}
          </button>
        </div>

        <!-- 区域 C：Tab 内容 -->
        <div
          ref="tabContentRef"
          class="tab-content"
          :class="{ swiping: tabSwipeActive, settling: tabSwipeSettling }"
          :style="tabContentStyle"
        >
          <div
            v-for="(tab, index) in tabs"
            :key="tab.key"
            :ref="(el) => setTabPanelRef(tab.key, el)"
            class="tab-panel"
            :class="{ current: activeTab === tab.key }"
            :style="getTabPanelStyle(index)"
          >
            <AlbumInfoTab
              v-if="tab.key === 'info'"
              :album="albumDetail"
              :action-busy="actionBusy"
              :download-status="selectedChapterDownloadStatus"
              :image-available="selectedChapterHasDownload"
              :pdf-available="Boolean(selectedChapterPdf)"
              @toggle-like="handleToggleLike"
              @toggle-favorite="handleToggleFavorite"
              @download="handleDownload"
              @navigate-album="onNavigateAlbum"
            />
            <AlbumChaptersTab
              v-else-if="tab.key === 'chapters'"
              :photo-metas="albumDetail?.photoMetas ?? []"
              :selected-chapter-id="selectedChapterId"
              :loading="loading"
              :show-actions="showChapterActions"
              :chapter-download-statuses="chapterDownloadStatuses"
              :chapter-pdf-statuses="chapterPdfStatuses"
              @select-chapter="selectChapter"
              @download-chapter="onDownloadChapter"
              @dismiss-actions="showChapterActions = false"
              @batch-download="onBatchDownload"
            />
            <AlbumPreviewTab
              v-else-if="tab.key === 'preview'"
              :images="previewImages"
              :total-count="previewImageTotal"
              :loading="previewLoading"
              empty-text="请先选择章节"
              @load-more="navigateToFullPreview"
              @open-reader="onOpenReader"
            />
            <AlbumCommentsTab
              v-else-if="tab.key === 'comments'"
              :comments="comments"
              :loading="commentsLoading"
              :has-more="hasMoreComments"
              :total="totalComments"
            />
          </div>
        </div>
      </div>

      <div class="bottom-spacer"/>
    </IonContent>

    <FavoriteFolderPicker
      v-model="showFolderPicker"
      :online-folders="pickerOnlineFolders"
      :offline-folders="pickerOfflineFolders"
      :online-folder-counts="onlineFolderCounts"
      @select="onPickerSelect"
      @add-folder="onPickerAddFolder"
    />
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({name: 'AlbumDetailPage'})

import {computed, nextTick, onMounted, onUnmounted, reactive, ref, watch, type ComponentPublicInstance} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {createGesture, IonContent, IonPage, menuController, type Gesture} from '@ionic/vue'
import type {PluginListenerHandle} from '@capacitor/core'
import {getImageUrl, JmcomicService, sanitizeError, showToast} from '@/services/JmcomicService'
import {buildPdfDocumentParams, fetchPdfArrayBuffer} from '@/services/PdfReaderService'
import * as pdfjsLib from 'pdfjs-dist'
import type {
  AlbumDetail,
  AlbumMeta,
  CommentItem,
  FavoriteResult,
  FolderEntry,
  ImportedPdf,
  PhotoDetail,
  PreloadResult,
} from '@/services/JmcomicTypes'
import {makeTaskId} from '@/services/JmcomicTypes'
import {OfflineDownloadService} from '@/services/OfflineDownloadService'
import {OfflineFavoriteService} from '@/services/OfflineFavoriteService'
import {HistoryService} from '@/services/HistoryService'
import {useAuth} from '@/composables/useAuth'
import AlbumHeader from '@/components/album/AlbumHeader.vue'
import AlbumInfoTab from '@/components/album/AlbumInfoTab.vue'
import AlbumChaptersTab from '@/components/album/AlbumChaptersTab.vue'
import AlbumPreviewTab from '@/components/album/AlbumPreviewTab.vue'
import AlbumCommentsTab from '@/components/album/AlbumCommentsTab.vue'
import FavoriteFolderPicker from '@/components/favorite/FavoriteFolderPicker.vue'

pdfjsLib.GlobalWorkerOptions.workerSrc = new URL(
  'pdfjs-dist/build/pdf.worker.min.mjs',
  import.meta.url,
).href

const route = useRoute()
const router = useRouter()

// ---- 路由数据 ----
const albumId = computed(() => route.params.id as string)
const coverUrl = computed(() => albumDetail.value?.image || (route.query.coverUrl as string) || '')
const albumTitle = computed(() => albumDetail.value?.title || (route.query.title as string) || '')
const albumAuthors = computed(() => {
  if (albumDetail.value?.authors?.length) return albumDetail.value.authors.join(' / ')
  const raw = route.query.authors as string
  return raw ? raw.split(',').join(' / ') : ''
})

// ---- 核心数据 ----
const albumDetail = ref<AlbumDetail | null>(null)
const photoDetail = ref<PhotoDetail | null>(null)
const loading = ref(true)

// ---- Tab ----
type TabKey = 'info' | 'chapters' | 'preview' | 'comments'
const tabs = computed(() => {
  const chapterCount = albumDetail.value?.photoMetas?.length
  const commentCount = albumDetail.value?.commentCount
  return [
    {key: 'info' as const, label: '本子信息'},
    {key: 'chapters' as const, label: chapterCount ? `章节 (${chapterCount})` : '章节'},
    {key: 'preview' as const, label: '预览'},
    {key: 'comments' as const, label: commentCount ? `评论 (${commentCount})` : '评论'},
  ]
})
const activeTab = ref<TabKey>('info')

// ---- 章节 ----
const selectedChapterId = ref('')
const selectedChapterDownloadStatus = computed(() =>
  chapterDownloadStatuses.value.get(selectedChapterId.value),
)
const selectedChapterHasDownload = computed(() =>
  selectedChapterDownloadStatus.value === 'completed',
)
const chapterLoading = ref(false)

// ---- 章节操作栏 ----
const showChapterActions = ref(false)
const chapterDownloadStatuses = ref<Map<string, string>>(new Map())
const chapterPdfMap = ref<Map<string, ImportedPdf>>(new Map())
const chapterPdfStatuses = computed(() => {
  const map = new Map<string, boolean>()
  for (const key of chapterPdfMap.value.keys()) {
    map.set(key, true)
  }
  return map
})
const selectedChapterPdf = computed(() =>
  chapterPdfMap.value.get(selectedChapterId.value) ?? null,
)
let downloadProgressHandle: PluginListenerHandle | null = null

type ReaderSource = 'network' | 'download' | 'pdf'
type PreviewSource = 'network' | 'download' | 'pdf'

const sourceMenuOpen = ref(false)
const networkAvailable = ref(typeof navigator === 'undefined' ? true : navigator.onLine)
const previewLoadedKey = ref('')
let previewPdfDoc: pdfjsLib.PDFDocumentProxy | null = null
const previewObjectUrls = new Set<string>()

const updateNetworkAvailable = () => {
  networkAvailable.value = typeof navigator === 'undefined' ? true : navigator.onLine
}

const refreshDownloadStatuses = async () => {
  try {
    const result = await JmcomicService.getDownloadTasks()
    if (result?.tasks) {
      const map = new Map<string, string>()
      for (const task of result.tasks) {
        if (task.albumId === albumId.value) {
          map.set(task.chapterId, task.status)
        }
      }
      chapterDownloadStatuses.value = map
    }
  } catch {
    // ignore
  }
}

const chooseRecentPdf = (current: ImportedPdf | undefined, next: ImportedPdf) => {
  if (!current) return next
  return next.createdAt >= current.createdAt ? next : current
}

const resolvePdfChapterKey = (pdf: ImportedPdf): string => {
  const metas = albumDetail.value?.photoMetas ?? []
  const exact = metas.find((meta) => meta.id === pdf.chapterId)
  if (exact) return exact.id

  const byOrder = metas.find((meta) => meta.sortOrder === pdf.chapterSortOrder)
  if (byOrder) return byOrder.id

  return pdf.chapterId || pdf.albumId
}

const refreshImportedPdfStatuses = async () => {
  try {
    const result = await JmcomicService.getImportedPdfs()
    const map = new Map<string, ImportedPdf>()
    for (const pdf of result.pdfs ?? []) {
      if (pdf.albumId !== albumId.value) continue
      const key = resolvePdfChapterKey(pdf)
      map.set(key, chooseRecentPdf(map.get(key), pdf))
    }
    chapterPdfMap.value = map
  } catch {
    chapterPdfMap.value = new Map()
  }
}

// ---- 操作 ----
const actionBusy = reactive({like: false, favorite: false})

// ---- 收藏夹选择弹窗 ----
const {isLoggedIn} = useAuth()
const showFolderPicker = ref(false)

const pickerOnlineFolders = ref<FolderEntry[]>([])
const pickerOfflineFolders = ref<FolderEntry[]>([])
const onlineFolderCounts = ref<Record<string, number>>({})

async function openFolderPicker() {
  await OfflineFavoriteService.ensureInit()
  pickerOfflineFolders.value = OfflineFavoriteService.getFolders()

  if (isLoggedIn.value) {
    try {
      const result: FavoriteResult = await JmcomicService.favorites({folderId: '0', page: 1})
      if (result.folderList) {
        const entries: FolderEntry[] = []
        const counts: Record<string, number> = {}
        const countPromises: Promise<void>[] = []
        for (const [id, name] of Object.entries(result.folderList)) {
          entries.push({id, name, count: 0})
          countPromises.push(
            JmcomicService.favorites({folderId: id, page: 1})
              .then((r) => {
                counts[id] = r.totalItems
              })
              .catch(() => {
                counts[id] = 0
              }),
          )
        }
        pickerOnlineFolders.value = entries
        await Promise.all(countPromises)
        onlineFolderCounts.value = counts
      }
    } catch {
      pickerOnlineFolders.value = []
    }
  } else {
    pickerOnlineFolders.value = []
  }

  showFolderPicker.value = true
}

async function onPickerSelect(payload: { folderId: string; source: 'online' | 'offline' }) {
  showFolderPicker.value = false
  if (!albumDetail.value) return

  actionBusy.favorite = true
  try {
    if (payload.source === 'online') {
      await JmcomicService.toggleAlbumFavorite(albumId.value, payload.folderId)
    } else {
      OfflineFavoriteService.addItem(payload.folderId, {
        id: albumDetail.value.id,
        title: albumDetail.value.title,
        coverUrl: albumDetail.value.image,
        authors: albumDetail.value.authors,
        tags: albumDetail.value.tags,
      })
    }
    albumDetail.value.isFavorite = true
    await showToast('已收藏', 'success')
  } catch (e: any) {
    await showToast(sanitizeError(e, '收藏失败'), 'danger')
  } finally {
    actionBusy.favorite = false
  }
}

async function onPickerAddFolder() {
  const {alertController} = await import('@ionic/vue')
  const alert = await alertController.create({
    header: '新建收藏夹',
    inputs: [{name: 'name', type: 'text', placeholder: '收藏夹名称'}],
    buttons: [
      {text: '取消', role: 'cancel'},
      {
        text: '确定',
        handler: async (data) => {
          const name = data?.name?.trim()
          if (!name) return

          if (isLoggedIn.value) {
            try {
              const r = await JmcomicService.manageFavoriteFolder('add', '0', name, '')
              if (r.status === 'ok') {
                await showToast('收藏夹已创建', 'success')
                // 刷新在线列表 + 数量
                const result: FavoriteResult = await JmcomicService.favorites({
                  folderId: '0',
                  page: 1,
                })
                if (result.folderList) {
                  const entries: FolderEntry[] = []
                  const counts: Record<string, number> = {}
                  const countPromises: Promise<void>[] = []
                  for (const [id, fName] of Object.entries(result.folderList)) {
                    entries.push({id, name: fName, count: 0})
                    countPromises.push(
                      JmcomicService.favorites({folderId: id, page: 1})
                        .then((r) => {
                          counts[id] = r.totalItems
                        })
                        .catch(() => {
                          counts[id] = 0
                        }),
                    )
                  }
                  pickerOnlineFolders.value = entries
                  await Promise.all(countPromises)
                  onlineFolderCounts.value = counts
                }
              }
            } catch {
              /* ignore */
            }
          } else {
            OfflineFavoriteService.createFolder(name)
            pickerOfflineFolders.value = OfflineFavoriteService.getFolders()
            await showToast('收藏夹已创建', 'success')
          }
        },
      },
    ],
  })
  await alert.present()
}

// ---- 预览 ----
const PREVIEW_BATCH = 20

interface PreviewImage {
  sortOrder: number
  dataUrl: string
}

const previewImages = ref<PreviewImage[]>([])
const previewImageTotal = ref(0)
const previewLoading = ref(false)
let imageReadyListenerHandle: PluginListenerHandle | null = null

const clearPreviewPdf = () => {
  for (const url of previewObjectUrls) {
    URL.revokeObjectURL(url)
  }
  previewObjectUrls.clear()
  previewPdfDoc?.destroy()
  previewPdfDoc = null
}

// ---- 评论 ----
const comments = ref<CommentItem[]>([])
const commentsLoading = ref(false)
const commentPage = ref(1)
const totalComments = ref(0)
const hasMoreComments = computed(() => comments.value.length < totalComments.value)

// ---- Tab 栏粘性 ----
const tabBarSticky = ref(false)
const headerRef = ref<InstanceType<typeof AlbumHeader> | null>(null)
const tabBarRef = ref<HTMLElement | null>(null)
const tabGestureRef = ref<HTMLElement | null>(null)
const tabContentRef = ref<HTMLElement | null>(null)
const contentRef = ref<InstanceType<typeof IonContent> | null>(null)
const tabPanelRefs = new Map<TabKey, HTMLElement>()
let tabSwipeGesture: Gesture | undefined
let tabResizeObserver: ResizeObserver | undefined
const tabSwipeActive = ref(false)
const tabSwipeSettling = ref(false)
const tabSwipeBaseIndex = ref(0)
const tabSwipeOffset = ref(0)
const tabSwipeHeight = ref(0)
const TAB_SWIPE_SETTLE_MS = 280
const TAB_SWIPE_GAP = 18
const activeTabIndex = computed(() => tabs.value.findIndex((tab) => tab.key === activeTab.value))
const isFirstTab = computed(() => activeTabIndex.value <= 0)
const tabContentStyle = computed(() =>
  tabSwipeActive.value ? {minHeight: `${Math.max(tabSwipeHeight.value + 24, 144)}px`} : {},
)
const visualTabProgress = computed(() => {
  if (!tabSwipeActive.value) return Math.max(activeTabIndex.value, 0)
  const progress = tabSwipeBaseIndex.value - tabSwipeOffset.value / getTabSwipeStride()
  return Math.max(0, Math.min(tabs.value.length - 1, progress))
})
const tabBarStyle = computed(() => ({
  '--tab-count': String(tabs.value.length),
  '--tab-progress': String(visualTabProgress.value),
}))

// ---- 计算属性 ----
const selectedChapterPageCount = computed(() => {
  if (photoDetail.value && photoDetail.value?.id === selectedChapterId.value) {
    return photoDetail.value?.images.length
  }
  return albumDetail.value?.pageCount ?? 0
})

// ---- 数据加载 ----
const resetAlbumState = () => {
  albumDetail.value = null
  photoDetail.value = null
  selectedChapterId.value = ''
  previewImages.value = []
  previewImageTotal.value = 0
  previewLoadedKey.value = ''
  comments.value = []
  commentPage.value = 1
  totalComments.value = 0
  activeTab.value = 'info'
  showChapterActions.value = false
  sourceMenuOpen.value = false
  chapterDownloadStatuses.value = new Map()
  chapterPdfMap.value = new Map()
  loading.value = true
  imageReadyListenerHandle?.remove()
  imageReadyListenerHandle = null
  clearPreviewPdf()
}

const loadAlbumData = async () => {
  resetAlbumState()

  try {
    const [album, photo] = await Promise.all([
      JmcomicService.getAlbum(albumId.value),
      JmcomicService.getPhoto(albumId.value),
    ])
    albumDetail.value = album
    photoDetail.value = photo

    const matched = album.photoMetas.find((m) => m.id === albumId.value)
    selectedChapterId.value = matched?.id ?? album.photoMetas[0]?.id ?? ''

    recordBrowseHistory()
  } catch {
    // 保留 query 数据展示
  } finally {
    loading.value = false
  }

  await refreshDownloadStatuses()
  await refreshImportedPdfStatuses()
  downloadProgressHandle?.remove()
  downloadProgressHandle = await JmcomicService.addDownloadProgressListener((data) => {
    if (data.albumId !== albumId.value) return
    const map = new Map(chapterDownloadStatuses.value)
    if (data.status === 'cancelled') {
      map.delete(data.chapterId)
    } else {
      map.set(data.chapterId, data.status)
    }
    chapterDownloadStatuses.value = map
  })

  if (activeTab.value === 'preview') {
    await loadPreview()
  }
}

onMounted(() => {
  updateNetworkAvailable()
  void menuController.swipeGesture(false)
  document.querySelector('ion-menu')?.addEventListener('ionDidClose', handleDetailMenuDidClose)
  window.addEventListener('online', updateNetworkAvailable)
  window.addEventListener('offline', updateNetworkAvailable)
  loadAlbumData()
  nextTick(() => {
    setupTabSwipeGesture()
    observeActiveTabPanel()
  })
})

// ---- Tab 切换 ----
const switchTab = async (key: TabKey) => {
  showChapterActions.value = false
  activeTab.value = key
  if (key === 'preview') {
    await loadPreview()
  } else if (key === 'comments') {
    await loadComments()
  }
}

const setTabPanelRef = (key: TabKey, el: Element | ComponentPublicInstance | null) => {
  if (el instanceof HTMLElement) {
    tabPanelRefs.set(key, el)
  } else {
    tabPanelRefs.delete(key)
  }
}

const measureActiveTabPanel = () => {
  const panel = tabPanelRefs.get(activeTab.value)
  if (!panel) return
  tabSwipeHeight.value = panel.offsetHeight
}

const observeActiveTabPanel = () => {
  tabResizeObserver?.disconnect()
  const panel = tabPanelRefs.get(activeTab.value)
  if (!panel) return
  measureActiveTabPanel()
  tabResizeObserver = new ResizeObserver(measureActiveTabPanel)
  tabResizeObserver.observe(panel)
}

const getTabPanelStyle = (index: number) => {
  if (!tabSwipeActive.value) return {}
  const offset = (index - tabSwipeBaseIndex.value) * getTabSwipeStride() + tabSwipeOffset.value
  return {transform: `translate3d(${offset}px, 0, 0)`}
}

const getTabPanelWidth = () => {
  const panel = tabPanelRefs.get(activeTab.value)
  if (panel?.offsetWidth) return panel.offsetWidth

  const contentEl = tabContentRef.value
  const contentWidth = contentEl?.clientWidth || window.innerWidth || 1
  if (!contentEl) return Math.max(1, contentWidth - 28)

  const style = getComputedStyle(contentEl)
  const paddingX = parseFloat(style.paddingLeft || '0') + parseFloat(style.paddingRight || '0')
  return Math.max(1, contentWidth - paddingX)
}

const getTabSwipeStride = () => getTabPanelWidth() + TAB_SWIPE_GAP

const getTabButtonStyle = (index: number) => {
  const distance = Math.abs(index - visualTabProgress.value)
  return {color: distance < 0.5 ? '#fff' : '#8a6048'}
}

const getClampedTabSwipeOffset = (deltaX: number) => {
  const baseIndex = tabSwipeBaseIndex.value
  const atFirst = baseIndex <= 0
  const atLast = baseIndex >= tabs.value.length - 1
  if ((atFirst && deltaX > 0) || (atLast && deltaX < 0)) {
    return 0
  }
  const stride = getTabSwipeStride()
  return Math.max(-stride, Math.min(stride, deltaX))
}

const shouldLetRelatedScrollHandle = (detail: { deltaX: number; event: UIEvent }) => {
  const target = detail.event.target
  if (!(target instanceof Element)) return false
  const scrollEl = target.closest('.related-scroll') as HTMLElement | null
  if (!scrollEl) return false
  if (detail.deltaX === 0) {
    return scrollEl.scrollWidth > scrollEl.clientWidth + 1
  }

  if (detail.deltaX < 0) {
    return scrollEl.scrollLeft + scrollEl.clientWidth < scrollEl.scrollWidth - 1
  }
  if (detail.deltaX > 0) {
    return scrollEl.scrollLeft > 0
  }
  return false
}

const resetTabSwipeState = () => {
  tabSwipeActive.value = false
  tabSwipeSettling.value = false
  tabSwipeOffset.value = 0
  nextTick(() => observeActiveTabPanel())
}

const handleDetailMenuDidClose = () => {
  void menuController.swipeGesture(false)
}

const openSideMenuFromDetail = async () => {
  await menuController.swipeGesture(true)
  await menuController.open()
}

const finishTabSwipe = (targetIndex: number | null, endOffset: number) => {
  tabSwipeSettling.value = true
  tabSwipeOffset.value = endOffset
  window.setTimeout(() => {
    const target = targetIndex === null ? null : tabs.value[targetIndex]
    if (target) {
      void switchTab(target.key)
    }
    resetTabSwipeState()
    void menuController.swipeGesture(false)
  }, TAB_SWIPE_SETTLE_MS)
}

const setupTabSwipeGesture = () => {
  tabSwipeGesture?.destroy()
  tabSwipeGesture = undefined
  const el = tabGestureRef.value
  if (!el) return

  tabSwipeGesture = createGesture({
    el,
    gestureName: 'album-detail-tab-swipe',
    gesturePriority: 35,
    threshold: 12,
    direction: 'x',
    canStart: (detail) => !tabSwipeSettling.value && !shouldLetRelatedScrollHandle(detail),
    onStart: () => {
      tabSwipeBaseIndex.value = activeTabIndex.value
      tabSwipeActive.value = true
      tabSwipeSettling.value = false
      tabSwipeOffset.value = 0
      measureActiveTabPanel()
      void menuController.swipeGesture(false)
    },
    onMove: (detail) => {
      tabSwipeOffset.value = getClampedTabSwipeOffset(detail.deltaX)
    },
    onEnd: (detail) => {
      const baseIndex = tabSwipeBaseIndex.value
      const stride = getTabSwipeStride()
      const threshold = Math.min(90, getTabPanelWidth() * 0.22)
      const shouldMove = Math.abs(detail.deltaX) > threshold || Math.abs(detail.velocityX) > 0.35
      const nextIndex = detail.deltaX < 0 ? baseIndex + 1 : baseIndex - 1

      if (shouldMove && detail.deltaX > 0 && baseIndex === 0) {
        resetTabSwipeState()
        void openSideMenuFromDetail()
        return
      }

      if (shouldMove && nextIndex >= 0 && nextIndex < tabs.value.length) {
        finishTabSwipe(nextIndex, nextIndex > baseIndex ? -stride : stride)
        return
      }

      finishTabSwipe(null, 0)
    },
  })
  tabSwipeGesture.enable(true)
}

watch(activeTab, () => {
  void menuController.swipeGesture(false)
  nextTick(() => observeActiveTabPanel())
})

// ---- 章节选择 ----
const selectChapter = async (chapterId: string) => {
  if (chapterId === selectedChapterId.value) {
    // 再次点击同一章节：切换操作栏
    showChapterActions.value = !showChapterActions.value
    return
  }
  // 不同章节：隐藏操作栏，加载新章节
  showChapterActions.value = false
  sourceMenuOpen.value = false
  previewLoadedKey.value = ''
  selectedChapterId.value = chapterId
  chapterLoading.value = true

  try {
    photoDetail.value = await JmcomicService.getPhoto(chapterId)
    recordBrowseHistory()
  } catch (e: any) {
    await showToast(sanitizeError(e, '章节加载失败'), 'danger')
  } finally {
    chapterLoading.value = false
  }

  if (activeTab.value === 'preview') {
    await loadPreview()
  }
}

const onDownloadChapter = async (chapterId: string) => {
  if (chapterId !== selectedChapterId.value) {
    selectedChapterId.value = chapterId
  }
  await handleDownload()
  await refreshDownloadStatuses()
}

const getPreferredSource = (): PreviewSource => {
  if (selectedChapterHasDownload.value) return 'download'
  if (selectedChapterPdf.value) return 'pdf'
  return 'network'
}

const buildPdfReaderQuery = (pdf: ImportedPdf, page?: number) => ({
  path: pdf.filePath,
  title: pdf.fileName,
  albumId: pdf.albumId,
  albumTitle: pdf.albumTitle || albumTitle.value,
  authors: pdf.authors || albumAuthors.value,
  coverUrl: pdf.coverUrl || coverUrl.value,
  chapterId: pdf.chapterId || selectedChapterId.value || albumId.value,
  chapterTitle: pdf.chapterTitle || pdf.fileName,
  ...(page ? {page: String(page)} : {}),
})

const openReaderBySource = (source: ReaderSource, page?: number) => {
  const chapterId = selectedChapterId.value || albumId.value
  sourceMenuOpen.value = false

  if (source === 'pdf') {
    const pdf = selectedChapterPdf.value
    if (!pdf?.filePath) return
    void router.push({
      path: '/pdf-reader',
      query: buildPdfReaderQuery(pdf, page),
    })
    return
  }

  if (source === 'download' && !selectedChapterHasDownload.value) return
  if (source === 'network' && !networkAvailable.value) {
    void showToast('当前网络不可用', 'medium')
    return
  }

  void router.push({
    path: `/album/${albumId.value}/read/${chapterId}`,
    query: {
      ...(page ? {page: String(page)} : {}),
      title: albumTitle.value,
      total: String(selectedChapterPageCount.value),
      ...(source === 'download' ? {source: 'download'} : {}),
    },
  })
}

const toggleSourceMenu = () => {
  sourceMenuOpen.value = !sourceMenuOpen.value
}

// ---- 预览 ----
const loadDownloadedPreview = async (chapterId: string) => {
  const photo = await JmcomicService.getDownloadedPhoto(albumId.value, chapterId)
  photoDetail.value = photo
  previewImageTotal.value = photo.images.length
  previewImages.value = photo.images.slice(0, PREVIEW_BATCH).map((img) => ({
    sortOrder: img.sortOrder,
    dataUrl: getImageUrl(photo.id, img.sortOrder, 'thumb'),
  }))
}

const renderPdfPreviewPage = async (pageNum: number): Promise<string | null> => {
  if (!previewPdfDoc) return null
  let page: pdfjsLib.PDFPageProxy | null = null
  try {
    page = await previewPdfDoc.getPage(pageNum)
    const rawViewport = page.getViewport({scale: 1})
    const columns = window.innerWidth >= 680 ? 4 : 3
    const gap = 6
    const sidePadding = 24
    const targetWidth = (window.innerWidth - sidePadding - gap * (columns - 1)) / columns
    const scale = Math.max(0.4, (targetWidth * Math.min(window.devicePixelRatio || 1, 2)) / rawViewport.width)
    const viewport = page.getViewport({scale})
    const canvas = document.createElement('canvas')
    canvas.width = viewport.width
    canvas.height = viewport.height
    const task = page.render({canvas, viewport})
    await task.promise
    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob((b) => resolve(b), 'image/jpeg', 0.82),
    )
    if (!blob) return null
    const url = URL.createObjectURL(blob)
    previewObjectUrls.add(url)
    return url
  } catch {
    return null
  } finally {
    page?.cleanup()
  }
}

const loadPdfPreview = async (pdf: ImportedPdf) => {
  clearPreviewPdf()
  const arrayBuffer = await fetchPdfArrayBuffer(pdf.filePath)
  previewPdfDoc = await pdfjsLib.getDocument(buildPdfDocumentParams(arrayBuffer)).promise
  previewImageTotal.value = previewPdfDoc.numPages

  const firstCount = Math.min(PREVIEW_BATCH, previewPdfDoc.numPages)
  const rendered = await Promise.all(
    Array.from({length: firstCount}, (_, i) => renderPdfPreviewPage(i + 1)),
  )
  previewImages.value = rendered
    .map((url, index) => url ? {sortOrder: index + 1, dataUrl: url} : null)
    .filter((item): item is PreviewImage => Boolean(item))
}

const loadNetworkPreview = async (chapterId: string) => {
  // 用 Set 去重：imageReady 事件可能在 preloadImages 返回前就触发
  const addedSortOrders = new Set<number>()
  imageReadyListenerHandle = await JmcomicService.addImageReadyListener(chapterId, (sortOrder) => {
    if (addedSortOrders.has(sortOrder)) return
    addedSortOrders.add(sortOrder)
    previewImages.value.push({
      sortOrder,
      dataUrl: getImageUrl(chapterId, sortOrder, 'thumb'),
    })
  })

  const photo = await JmcomicService.getPhoto(chapterId)
  photoDetail.value = photo
  previewImageTotal.value = photo.images.length

  const batch = photo.images.slice(0, PREVIEW_BATCH)
  const result: PreloadResult = await JmcomicService.preloadImages(chapterId, batch, 'thumb')

  // 追加快取命中项（避免直接赋值覆盖 imageReady 已推送的图片）
  for (const so of result.cached) {
    if (!addedSortOrders.has(so)) {
      addedSortOrders.add(so)
      previewImages.value.push({
        sortOrder: so,
        dataUrl: getImageUrl(chapterId, so, 'thumb'),
      })
    }
  }
}

const loadPreview = async () => {
  const chapterId = selectedChapterId.value
  const source = getPreferredSource()
  const pdf = selectedChapterPdf.value
  const cacheKey = `${source}:${chapterId}:${pdf?.id ?? ''}`
  if (previewLoadedKey.value === cacheKey && previewImages.value.length) return

  imageReadyListenerHandle?.remove()
  imageReadyListenerHandle = null

  previewLoading.value = true
  previewImages.value = []
  previewImageTotal.value = 0

  if (!chapterId) {
    previewLoading.value = false
    return
  }

  try {
    if (source !== 'pdf') clearPreviewPdf()
    if (source === 'download') {
      await loadDownloadedPreview(chapterId)
    } else if (source === 'pdf' && pdf) {
      await loadPdfPreview(pdf)
    } else {
      await loadNetworkPreview(chapterId)
    }
    previewLoadedKey.value = cacheKey
  } catch (e: any) {
    if (source === 'download' && pdf) {
      try {
        await loadPdfPreview(pdf)
        previewLoadedKey.value = `pdf:${chapterId}:${pdf.id}`
        return
      } catch {
        // 继续走统一错误提示
      }
    }
    await showToast(sanitizeError(e, '预览加载失败'), 'danger')
  } finally {
    previewLoading.value = false
  }
}

// ---- 浏览历史 ----

const recordBrowseHistory = () => {
  const id = albumId.value
  const title = albumTitle.value
  if (!id || !title) return

  HistoryService.recordBrowse({
    albumId: id,
    albumTitle: title,
    coverUrl: coverUrl.value,
    authors: albumAuthors.value,
    chapterId: selectedChapterId.value,
    chapterTitle: photoDetail.value?.title || '',
  })
}

// 弹窗关闭且未选择时重置 busy 状态
watch(showFolderPicker, (open) => {
  if (!open) actionBusy.favorite = false
})

// 同组件导航（相关本子跳转）时重新加载数据
watch(albumId, (newId, oldId) => {
  if (newId && newId !== oldId) loadAlbumData()
})

onUnmounted(() => {
  tabSwipeGesture?.destroy()
  tabResizeObserver?.disconnect()
  document.querySelector('ion-menu')?.removeEventListener('ionDidClose', handleDetailMenuDidClose)
  void menuController.swipeGesture(true)
  imageReadyListenerHandle?.remove()
  downloadProgressHandle?.remove()
  window.removeEventListener('online', updateNetworkAvailable)
  window.removeEventListener('offline', updateNetworkAvailable)
  clearPreviewPdf()
})

// 跳转全量预览页
const navigateToFullPreview = () => {
  const source = getPreferredSource()
  const pdf = selectedChapterPdf.value
  const commonQuery = {
    title: albumTitle.value,
    total: String(previewImageTotal.value || selectedChapterPageCount.value),
  }

  if (source === 'pdf' && pdf?.filePath) {
    void router.push({
      path: `/album/${albumId.value}/preview/${selectedChapterId.value}`,
      query: {
        ...commonQuery,
        source: 'pdf',
        pdfPath: pdf.filePath,
        pdfTitle: pdf.fileName,
        albumTitle: pdf.albumTitle || albumTitle.value,
        authors: pdf.authors || albumAuthors.value,
        coverUrl: pdf.coverUrl || coverUrl.value,
      },
    })
    return
  }

  void router.push({
    path: `/album/${albumId.value}/preview/${selectedChapterId.value}`,
    query: {
      ...commonQuery,
      ...(source === 'download' ? {source: 'download'} : {}),
    },
  })
}

const onOpenReader = (page: number) => {
  openReaderBySource(getPreferredSource(), page)
}

// ---- 评论 ----
const loadComments = async (append = false) => {
  if (commentsLoading.value) return
  if (!append) {
    commentPage.value = 1
    comments.value = []
    totalComments.value = 0
  }
  commentsLoading.value = true
  try {
    const result = await JmcomicService.getComments({
      albumId: albumId.value,
      page: commentPage.value,
    })
    totalComments.value = result.total
    if (append) {
      comments.value.push(...result.list)
    } else {
      comments.value = result.list
    }
    commentPage.value++
  } catch (e: any) {
    await showToast(sanitizeError(e, '评论加载失败'), 'danger')
  } finally {
    commentsLoading.value = false
  }
}

const loadMoreComments = async () => {
  if (!hasMoreComments.value || commentsLoading.value) return
  await loadComments(true)
}

// ---- 操作：点赞/收藏/下载 ----

function adjustLikeCount(likes: string, delta: 1 | -1): string {
  if (/^\d+$/.test(likes)) {
    return String(parseInt(likes, 10) + delta)
  }
  return likes
}

const handleToggleLike = async () => {
  if (!isLoggedIn.value) {
    await showToast('请先登录', 'medium')
    return
  }
  if (actionBusy.like || !albumDetail.value) return
  actionBusy.like = true
  const wasLiked = albumDetail.value.isLiked
  albumDetail.value.isLiked = !wasLiked
  albumDetail.value.likes = adjustLikeCount(
    albumDetail.value.likes,
    wasLiked ? -1 : 1,
  )
  try {
    await JmcomicService.toggleAlbumLike(albumId.value)
    await showToast(wasLiked ? '已取消点赞' : '已点赞', 'success')
  } catch (e: any) {
    const msg = sanitizeError(e, '')
    if (/已经评价/.test(msg)) {
      if (wasLiked) {
        albumDetail.value.isLiked = true
        albumDetail.value.likes = adjustLikeCount(albumDetail.value.likes, 1)
        await showToast('暂不支持取消点赞', 'medium')
      } else {
        await showToast('已点赞', 'success')
      }
    } else {
      albumDetail.value.isLiked = wasLiked
      albumDetail.value.likes = adjustLikeCount(
        albumDetail.value.likes,
        wasLiked ? 1 : -1,
      )
      await showToast(sanitizeError(e, '点赞失败'), 'danger')
    }
  } finally {
    actionBusy.like = false
  }
}

const handleToggleFavorite = async () => {
  if (actionBusy.favorite || !albumDetail.value) return
  // 已收藏：直接取消
  if (albumDetail.value.isFavorite) {
    actionBusy.favorite = true
    try {
      await JmcomicService.toggleAlbumFavorite(albumId.value)
      albumDetail.value.isFavorite = false
      await showToast('已取消收藏', 'success')
    } catch (e: any) {
      await showToast(sanitizeError(e, '取消收藏失败'), 'danger')
    } finally {
      actionBusy.favorite = false
    }
    return
  }
  // 未收藏：打开收藏夹选择弹窗
  actionBusy.favorite = true
  void openFolderPicker()
}

const downloadSingleChapter = async (chapterId: string): Promise<boolean> => {
  if (!albumDetail.value) return false

  const taskId = makeTaskId(albumId.value, chapterId)
  const existing = OfflineDownloadService.getAll().find(
    (t) => t.taskId === taskId && t.status !== 'failed',
  )
  if (existing) return false

  const chapterTitle =
    albumDetail.value.photoMetas.find((m) => m.id === chapterId)?.title || chapterId

  try {
    await JmcomicService.downloadChapter(
      albumId.value,
      chapterId,
      albumDetail.value.title,
      chapterTitle,
      albumDetail.value.image,
    )
    OfflineDownloadService.addTask({
      taskId,
      albumId: albumId.value,
      chapterId,
      albumTitle: albumDetail.value.title,
      chapterTitle,
      coverUrl: albumDetail.value.image,
      isSingleEpisode: albumDetail.value.isSingleEpisode,
      totalPages: 0,
      downloadedPages: 0,
      status: 'queued',
      createdAt: Date.now(),
    })
    return true
  } catch (e: any) {
    await showToast(sanitizeError(e, '下载提交失败'), 'danger')
    return false
  }
}

const handleDownload = async () => {
  const chapterId = selectedChapterId.value
  if (!chapterId || !albumDetail.value) return

  const taskId = makeTaskId(albumId.value, chapterId)
  const existing = OfflineDownloadService.getAll().find(
    (t) => t.taskId === taskId && t.status !== 'failed',
  )
  if (existing) {
    await showToast('该章节已在下载队列中', 'medium')
    return
  }

  const success = await downloadSingleChapter(chapterId)
  if (success) {
    await showToast('已加入下载队列', 'success')
    await refreshDownloadStatuses()
  }
}

const onBatchDownload = async (chapterIds: string[]) => {
  if (!chapterIds.length) return
  let successCount = 0
  for (const id of chapterIds) {
    if (await downloadSingleChapter(id)) {
      successCount++
    }
  }
  if (successCount > 0) {
    await showToast(`已加入 ${successCount} 个下载任务`, 'success')
    await refreshDownloadStatuses()
  }
}

const startReading = () => {
  if (selectedChapterHasDownload.value) {
    openReaderBySource('download')
    return
  }
  if (selectedChapterPdf.value) {
    sourceMenuOpen.value = true
    return
  }
  openReaderBySource('network')
}

// ---- 导航 ----
const onNavigateAlbum = (related: AlbumMeta) => {
  void router.push({
    path: `/album/${related.id}`,
    query: {title: related.title, coverUrl: related.coverUrl, authors: related.authors.join(',')},
  })
}

const goBack = () => {
  if (window.history.length > 1) {
    router.back()
  } else {
    void router.push('/home')
  }
}

// ---- 滚动：Tab 栏吸顶 + 评论触底加载 ----
const handleScroll = async () => {
  const headerEl = headerRef.value?.$el as HTMLElement | undefined
  if (!headerEl || !tabBarRef.value) return
  tabBarSticky.value = headerEl.getBoundingClientRect().bottom <= 0

  if (activeTab.value !== 'comments') return
  if (!hasMoreComments.value || commentsLoading.value) return
  const ionContentEl = contentRef.value?.$el as any
  if (!ionContentEl) return
  const el = await ionContentEl.getScrollElement?.() as HTMLElement | undefined
  if (!el) return
  if (el.scrollHeight - el.scrollTop - el.clientHeight < 200) {
    loadMoreComments()
  }
}
</script>

<style scoped>
/* Tab 栏 */
.tab-bar {
  display: flex;
  gap: 2px;
  padding: 8px 12px;
  background: #fffaf6;
  border-bottom: 1px solid rgb(245 210 188 / 0.5);
  position: relative;
  z-index: 10;
}

.tab-bar.sticky {
  padding-top: calc(var(--ion-safe-area-top) + 8px);
  position: sticky;
  top: 0;
  box-shadow: 0 2px 10px rgb(76 42 24 / 0.08);
  transition: padding-top 0.1s ease;
}

.tab-btn {
  flex: 1;
  height: 34px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #8a6048;
  font-size: 12px;
  font-weight: 600;
  position: relative;
  z-index: 1;
  transition: color 0.18s ease;
}

.tab-btn.active {
  color: #fff;
}

.tab-active-indicator {
  position: absolute;
  left: 12px;
  top: 8px;
  width: calc((100% - 24px - (var(--tab-count) - 1) * 2px) / var(--tab-count));
  height: 34px;
  border-radius: 8px;
  background: linear-gradient(145deg, #fa9c69, #f28752);
  box-shadow: 0 4px 10px rgb(242 135 82 / 0.22);
  transform: translate3d(calc(var(--tab-progress) * (100% + 2px)), 0, 0);
  transition: transform 0.24s cubic-bezier(0.22, 1, 0.36, 1);
}

.tab-bar.swiping .tab-active-indicator {
  transition: none;
}

.tab-bar.settling .tab-active-indicator {
  transition: transform 0.28s cubic-bezier(0.22, 1, 0.36, 1);
}

/* Tab 内容 */
.tab-content {
  padding: 12px 14px;
  overflow: hidden;
  position: relative;
  touch-action: pan-y;
}

.tab-panel {
  display: none;
}

.tab-panel.current {
  display: block;
}

.tab-content.swiping .tab-panel {
  display: block;
  position: absolute;
  inset: 12px 14px auto;
  width: calc(100% - 28px);
  will-change: transform;
}

.tab-content.settling .tab-panel {
  transition: transform 0.28s cubic-bezier(0.22, 1, 0.36, 1);
}

.bottom-spacer {
  height: 80px;
}

@media (min-width: 680px) {
  .tab-content {
    max-width: 720px;
    margin: 0 auto;
  }

  .tab-content.swiping .tab-panel {
    width: calc(100% - 28px);
  }
}
</style>
