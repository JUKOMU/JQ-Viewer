<template>
  <IonPage>
    <IonContent :scroll-events="true" @ionScroll="handleScroll">
      <!-- 区域 A：封面头部 -->
      <AlbumHeader
        ref="headerRef"
        :cover-url="coverUrl"
        :title="albumTitle"
        :authors="albumAuthors"
        :page-count="selectedChapterPageCount"
        :loading="loading"
        @back="goBack"
        @start-reading="startReading"
      />

      <!-- 区域 B：Tab 栏 -->
      <div class="tab-bar" ref="tabBarRef" :class="{ sticky: tabBarSticky }">
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
          @toggle-like="handleToggleLike"
          @toggle-favorite="handleToggleFavorite"
          @download="handleDownload"
          @navigate-album="onNavigateAlbum"
        />
        <AlbumChaptersTab
          v-else-if="activeTab === 'chapters'"
          :photo-metas="albumDetail?.photoMetas ?? []"
          :selected-chapter-id="selectedChapterId"
          @select-chapter="selectChapter"
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
        />
      </div>

      <div class="bottom-spacer" />
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import {computed, onMounted, onUnmounted, reactive, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {IonContent, IonPage, toastController} from '@ionic/vue'
import type {PluginListenerHandle} from '@capacitor/core'
import {JmcomicService} from '@/services/JmcomicService'
import type {AlbumDetail, AlbumMeta, CommentItem, PhotoDetail} from '@/services/JmcomicTypes'
import AlbumHeader from '@/components/album/AlbumHeader.vue'
import AlbumInfoTab from '@/components/album/AlbumInfoTab.vue'
import AlbumChaptersTab from '@/components/album/AlbumChaptersTab.vue'
import AlbumPreviewTab from '@/components/album/AlbumPreviewTab.vue'
import AlbumCommentsTab from '@/components/album/AlbumCommentsTab.vue'

const route = useRoute()
const router = useRouter()

// ---- 路由数据 ----
const albumId = computed(() => route.params.id as string)
const coverUrl = computed(() => (route.query.coverUrl as string) || '')
const albumTitle = computed(() => (route.query.title as string) || '')
const albumAuthors = computed(() => {
  const raw = route.query.authors as string
  return raw ? raw.split(',').join(' / ') : ''
})

// ---- 核心数据 ----
const albumDetail = ref<AlbumDetail | null>(null)
const photoDetail = ref<PhotoDetail | null>(null)
const loading = ref(true)

// ---- Tab ----
type TabKey = 'info' | 'chapters' | 'preview' | 'comments'
const tabs = [
  { key: 'info' as const, label: '本子信息' },
  { key: 'chapters' as const, label: '章节' },
  { key: 'preview' as const, label: '预览' },
  { key: 'comments' as const, label: '评论' },
]
const activeTab = ref<TabKey>('info')

// ---- 章节 ----
const selectedChapterId = ref('')

// ---- 操作 ----
const actionBusy = reactive({ like: false, favorite: false })

// ---- 预览 ----
const PREVIEW_BATCH = 20
interface PreviewImage { sortOrder: number; dataUrl: string }
const previewImages = ref<PreviewImage[]>([])
const previewImageTotal = ref(0)
const previewLoading = ref(false)
const previewLoadedChapterId = ref('')
let previewListenerHandle: PluginListenerHandle | null = null

// ---- 评论 ----
const comments = ref<CommentItem[]>([])
const commentsLoading = ref(false)

// ---- Tab 栏粘性 ----
const tabBarSticky = ref(false)
const headerRef = ref<InstanceType<typeof AlbumHeader> | null>(null)
const tabBarRef = ref<HTMLElement | null>(null)

// ---- 计算属性 ----
const selectedChapterPageCount = computed(() => {
  if (photoDetail.value && photoDetail.value.id === selectedChapterId.value) {
    return photoDetail.value.images.length
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
  } catch {
    // 保留 query 数据展示
  } finally {
    loading.value = false
  }
})

// ---- Tab 切换 ----
const switchTab = async (key: TabKey) => {
  activeTab.value = key
  if (key === 'preview') {
    await loadPreview()
  } else if (key === 'comments') {
    await loadComments()
  }
}

// ---- 章节选择 ----
const selectChapter = async (chapterId: string) => {
  if (chapterId === selectedChapterId.value) return
  selectedChapterId.value = chapterId

  try {
    const photo = await JmcomicService.getPhoto(chapterId)
    photoDetail.value = photo
  } catch { /* ignore */ }

  if (activeTab.value === 'preview') {
    await loadPreview()
  }
}

// ---- 预览 ----
const loadPreview = async () => {
  const chapterId = selectedChapterId.value
  if (!chapterId) return
  if (previewLoadedChapterId.value === chapterId && previewImages.value.length) return

  // 若当前章节已在 photoDetail 中，直接取总数
  if (photoDetail.value?.id === chapterId) {
    previewImageTotal.value = photoDetail.value.images.length
  }

  // 移除旧监听
  previewListenerHandle?.remove()
  previewListenerHandle = null

  previewLoading.value = true
  previewImages.value = []

  // 注册流式监听：每完成一张图就追加到列表
  previewListenerHandle = await JmcomicService.addPreviewListener(chapterId, (img) => {
    previewImages.value.push(img)
  })

  try {
    const photo = await JmcomicService.getPhoto(chapterId)
    photoDetail.value = photo
    previewImageTotal.value = photo.images.length

    const batch = photo.images.slice(0, PREVIEW_BATCH)
    await JmcomicService.decryptThumbnailUrls(batch)
    previewLoadedChapterId.value = chapterId
  } catch { /* ignore */ } finally {
    previewLoading.value = false
    previewListenerHandle?.remove()
    previewListenerHandle = null
  }
}

onUnmounted(() => {
  previewListenerHandle?.remove()
})

// 跳转全量预览页
const navigateToFullPreview = () => {
  void router.push({
    path: `/album/${albumId.value}/preview/${selectedChapterId.value}`,
    query: { title: albumTitle.value, total: String(previewImageTotal.value) },
  })
}

const onOpenReader = (page: number) => {
  void router.push({
    path: `/album/${albumId.value}/read/${selectedChapterId.value}`,
    query: { page: String(page), title: albumTitle.value },
  })
}

// ---- 评论 ----
const loadComments = async () => {
  if (comments.value.length) return
  commentsLoading.value = true
  try {
    const result = await JmcomicService.getComments({ albumId: albumId.value, page: 1 })
    comments.value = result.list
  } catch { /* ignore */ } finally {
    commentsLoading.value = false
  }
}

// ---- 操作：点赞/收藏/下载 ----
const handleToggleLike = async () => {
  if (actionBusy.like || !albumDetail.value) return
  actionBusy.like = true
  try {
    await JmcomicService.toggleAlbumLike(albumId.value)
    albumDetail.value.isLiked = !albumDetail.value.isLiked
  } catch { /* ignore */ } finally {
    actionBusy.like = false
  }
}

const handleToggleFavorite = async () => {
  if (actionBusy.favorite || !albumDetail.value) return
  actionBusy.favorite = true
  try {
    await JmcomicService.toggleAlbumFavorite(albumId.value)
    albumDetail.value.isFavorite = !albumDetail.value.isFavorite
  } catch { /* ignore */ } finally {
    actionBusy.favorite = false
  }
}

const handleDownload = async () => {
  const toast = await toastController.create({
    message: '下载功能即将推出', duration: 1500, position: 'middle', color: 'medium',
  })
  await toast.present()
}

const startReading = () => {
  void router.push({
    path: `/album/${albumId.value}/read/${selectedChapterId.value}`,
    query: { title: albumTitle.value },
  })
}

// ---- 导航 ----
const onNavigateAlbum = (related: AlbumMeta) => {
  void router.push({
    path: `/album/${related.id}`,
    query: { title: related.title, coverUrl: related.coverUrl, authors: related.authors.join(',') },
  })
}

const goBack = () => {
  if (window.history.length > 1) {
    router.back()
  } else {
    void router.push('/home')
  }
}

// ---- 滚动：Tab 栏吸顶 ----
const handleScroll = async () => {
  const headerEl = headerRef.value?.$el as HTMLElement | undefined
  if (!headerEl || !tabBarRef.value) return
  tabBarSticky.value = headerEl.getBoundingClientRect().bottom <= 0
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
  transition: background-color 0.18s ease, color 0.18s ease;
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
