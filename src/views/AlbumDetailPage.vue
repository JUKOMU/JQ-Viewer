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
        @back="goBack"
        @start-reading="startReading"
      />

      <!-- 区域 B：Tab 栏 -->
      <div ref="tabBarRef" class="tab-bar" :class="{ sticky: tabBarSticky }">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          class="tab-btn"
          :class="{ active: activeTab === tab.key }"
          @click="switchTab(tab.key)"
        >
          {{ tab.label }}
        </button>
      </div>

      <!-- 区域 C：Tab 内容 -->
      <div class="tab-content">
        <AlbumInfoTab
          v-if="activeTab === 'info'"
          :album="albumDetail"
          :action-busy="actionBusy"
          :download-status="selectedChapterDownloadStatus"
          @toggle-like="handleToggleLike"
          @toggle-favorite="handleToggleFavorite"
          @download="handleDownload"
          @navigate-album="onNavigateAlbum"
        />
        <AlbumChaptersTab
          v-else-if="activeTab === 'chapters'"
          :photo-metas="albumDetail?.photoMetas ?? []"
          :selected-chapter-id="selectedChapterId"
          :loading="loading"
          :show-actions="showChapterActions"
          :chapter-download-statuses="chapterDownloadStatuses"
          @select-chapter="selectChapter"
          @download-chapter="onDownloadChapter"
          @dismiss-actions="showChapterActions = false"
        />
        <AlbumPreviewTab
          v-else-if="activeTab === 'preview'"
          :images="previewImages"
          :total-count="previewImageTotal"
          :loading="previewLoading"
          empty-text="请先选择章节"
          @load-more="navigateToFullPreview"
          @open-reader="onOpenReader"
        />
        <AlbumCommentsTab
          v-else-if="activeTab === 'comments'"
          :comments="comments"
          :loading="commentsLoading"
          :has-more="hasMoreComments"
          :total="totalComments"
        />
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

import {computed, onMounted, onUnmounted, reactive, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {IonContent, IonPage} from '@ionic/vue'
import type {PluginListenerHandle} from '@capacitor/core'
import {getImageUrl, JmcomicService, sanitizeError, showToast} from '@/services/JmcomicService'
import type {
  AlbumDetail,
  AlbumMeta,
  CommentItem,
  FavoriteResult,
  FolderEntry,
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
const chapterLoading = ref(false)

// ---- 章节操作栏 ----
const showChapterActions = ref(false)
const chapterDownloadStatuses = ref<Map<string, string>>(new Map())
let downloadProgressHandle: PluginListenerHandle | null = null

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

// ---- 操作 ----
const actionBusy = reactive({like: false, favorite: false})

// ---- 收藏夹选择弹窗 ----
const {isLoggedIn} = useAuth()
const showFolderPicker = ref(false)

const pickerOnlineFolders = ref<FolderEntry[]>([])
const pickerOfflineFolders = ref<FolderEntry[]>([])
const onlineFolderCounts = ref<Record<string, number>>({})

async function openFolderPicker() {
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
const previewLoadedChapterId = ref('')
let imageReadyListenerHandle: PluginListenerHandle | null = null

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
const contentRef = ref<InstanceType<typeof IonContent> | null>(null)

// ---- 计算属性 ----
const selectedChapterPageCount = computed(() => {
  if (photoDetail.value && photoDetail.value?.id === selectedChapterId.value) {
    return photoDetail.value?.images.length
  }
  return albumDetail.value?.pageCount ?? 0
})

// ---- 数据加载 ----
onMounted(async () => {
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

    // 若用户已在加载期间切到预览 tab，补加载
    if (activeTab.value === 'preview') await loadPreview()
  } catch {
    // 保留 query 数据展示
  } finally {
    loading.value = false
  }

  // 获取当前本子的章节下载状态
  await refreshDownloadStatuses()
  // 监听下载进度，实时更新状态
  downloadProgressHandle = await JmcomicService.addDownloadProgressListener((data) => {
    if (data.albumId !== albumId.value) return
    const map = new Map(chapterDownloadStatuses.value)
    map.set(data.chapterId, data.status)
    chapterDownloadStatuses.value = map
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

// ---- 章节选择 ----
const selectChapter = async (chapterId: string) => {
  if (chapterId === selectedChapterId.value) {
    // 再次点击同一章节：切换操作栏
    showChapterActions.value = !showChapterActions.value
    return
  }
  // 不同章节：隐藏操作栏，加载新章节
  showChapterActions.value = false
  selectedChapterId.value = chapterId
  chapterLoading.value = true

  try {
    const photo = await JmcomicService.getPhoto(chapterId)
    photoDetail.value = photo
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

// ---- 预览 ----
const loadPreview = async () => {
  const chapterId = selectedChapterId.value
  if (previewLoadedChapterId.value === chapterId && previewImages.value.length) return

  imageReadyListenerHandle?.remove()
  imageReadyListenerHandle = null

  previewLoading.value = true
  previewImages.value = []

  if (!chapterId) return

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

  try {
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

    previewLoadedChapterId.value = chapterId
  } catch (e: any) {
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

// 同组件导航（相关本子跳转）时记录浏览历史
watch(albumId, (newId, oldId) => {
  if (newId && newId !== oldId) recordBrowseHistory()
})

onUnmounted(() => {
  imageReadyListenerHandle?.remove()
  downloadProgressHandle?.remove()
})

// 跳转全量预览页
const navigateToFullPreview = () => {
  void router.push({
    path: `/album/${albumId.value}/preview/${selectedChapterId.value}`,
    query: {title: albumTitle.value, total: String(previewImageTotal.value)},
  })
}

const onOpenReader = (page: number) => {
  const isDownloaded = chapterDownloadStatuses.value.get(selectedChapterId.value) === 'completed'
  void router.push({
    path: `/album/${albumId.value}/read/${selectedChapterId.value}`,
    query: {
      page: String(page),
      title: albumTitle.value,
      total: String(selectedChapterPageCount.value),
      ...(isDownloaded ? {source: 'download'} : {}),
    },
  })
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

const handleDownload = async () => {
  const chapterId = selectedChapterId.value
  if (!chapterId || !albumDetail.value) return

  const taskId = makeTaskId(albumId.value, chapterId)
  // 避免重复提交
  const existing = OfflineDownloadService.getAll().find(
    (t) => t.taskId === taskId && t.status !== 'failed',
  )
  if (existing) {
    await showToast('该章节已在下载队列中', 'medium')
    return
  }

  // 组装参数
  const albumTitle2 = albumDetail.value.title
  const coverUrl2 = albumDetail.value.image
  const chapterTitle2 =
    albumDetail.value.photoMetas.find((m) => m.id === chapterId)?.title || chapterId

  try {
    await JmcomicService.downloadChapter(
      albumId.value,
      chapterId,
      albumTitle2,
      chapterTitle2,
      coverUrl2,
    )
    // 乐观写入 localStorage
    OfflineDownloadService.addTask({
      taskId,
      albumId: albumId.value,
      chapterId,
      albumTitle: albumTitle2,
      chapterTitle: chapterTitle2,
      coverUrl: coverUrl2,
      totalPages: 0,
      downloadedPages: 0,
      status: 'queued',
      createdAt: Date.now(),
    })
    await showToast('已加入下载队列', 'success')
    await refreshDownloadStatuses()
  } catch (e: any) {
    await showToast(sanitizeError(e, '下载提交失败'), 'danger')
  }
}

const startReading = () => {
  const isDownloaded = chapterDownloadStatuses.value.get(selectedChapterId.value) === 'completed'
  void router.push({
    path: `/album/${albumId.value}/read/${selectedChapterId.value}`,
    query: {
      title: albumTitle.value,
      total: String(selectedChapterPageCount.value),
      ...(isDownloaded ? {source: 'download'} : {}),
    },
  })
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
  z-index: 10;
}

.tab-bar.sticky {
  position: sticky;
  top: 0;
  box-shadow: 0 2px 10px rgb(76 42 24 / 0.08);
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
  transition: background-color 0.18s ease,
  color 0.18s ease;
}

.tab-btn.active {
  background: linear-gradient(145deg, #fa9c69, #f28752);
  color: #fff;
}

/* Tab 内容 */
.tab-content {
  padding: 12px 14px;
}

.bottom-spacer {
  height: 80px;
}

@media (min-width: 680px) {
  .tab-content {
    max-width: 720px;
    margin: 0 auto;
  }
}
</style>
