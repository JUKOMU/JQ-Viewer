<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonBackButton default-href="/download"/>
        </IonButtons>
        <IonTitle class="toolbar-title">导入PDF</IonTitle>
        <IonButtons slot="end">
          <button
            class="confirm-btn"
            :disabled="loading || !hasAnyResolved"
            @click="onConfirm"
          >
            确认导入
          </button>
        </IonButtons>
      </IonToolbar>
    </IonHeader>

    <IonContent>
    <div class="page-container">
      <!-- 空状态 -->
      <div v-if="files.length === 0 && !loading" class="empty-state">
        <IonIcon :icon="documentTextOutline" class="empty-icon"/>
        <p>所选文件夹中未找到 PDF 文件</p>
      </div>

      <!-- 统计栏 -->
      <div v-if="files.length > 0" class="stats-card">
        <span class="stat resolved">已解析 {{ resolvedCount }}</span>
        <span class="stat ambiguous">多ID {{ ambiguousCount }}</span>
        <span class="stat missing">无ID {{ missingCount }}</span>
        <span v-if="duplicateCount > 0" class="stat duplicate">
          ID重复 {{ duplicateCount }}
        </span>
      </div>

      <!-- 文件卡片列表 -->
      <TransitionGroup v-if="files.length > 0" name="card-list" tag="div" class="file-list">
        <div
          v-for="(file, idx) in files"
          :key="file.filePath"
          class="file-card"
          :class="[cardClass(file), { searchable: canSearchFile(file), selected: searchTargetIdx === idx }]"
          @click="openSearchDrawer(idx)"
        >
          <!-- 封面区 -->
          <div class="cover-wrap">
            <img
              v-if="file.albumDetail?.image"
              :src="file.albumDetail.image"
              class="cover-img"
            />
            <div v-else class="cover-placeholder">
              <IonIcon :icon="canSearchFile(file) ? searchOutline : documentTextOutline"/>
            </div>
          </div>

          <!-- 信息区 -->
          <div class="info" @click="editingIdx !== idx ? undefined : undefined">
            <h3 class="item-title">
              {{ file.albumDetail?.title || '未识别本子' }}
            </h3>
            <div class="item-meta file-name-line">{{ file.fileName }}</div>
            <div v-if="file.albumDetail?.authors?.length" class="item-meta">
              作者：{{ file.albumDetail.authors.join(' / ') }}
            </div>
            <div v-if="file.albumDetail?.tags?.length" class="item-tags">
              <span
                v-for="t in file.albumDetail.tags.slice(0, 10)"
                :key="t"
                class="tag-chip"
              >{{ t }}</span>
              <span v-if="file.albumDetail.tags.length > 10" class="tag-chip tag-more">...</span>
            </div>
            <div class="status-row">
              <span class="status-tag" :class="statusTagClass(file)">{{ statusTagText(file) }}</span>
              <span v-if="file.chapterSortOrder != null" class="status-tag chapter-tag">
                第{{ file.chapterSortOrder }}话
              </span>
            </div>
          </div>

          <!-- 编辑按钮 -->
          <button class="edit-btn" @click.stop="toggleEdit(idx)">
            <IonIcon :icon="editingIdx === idx ? checkmarkOutline : createOutline"/>
          </button>

          <!-- 编辑模式遮罩 -->
          <div v-show="editingIdx === idx" class="edit-overlay">
            <textarea
              v-model="editText"
              class="edit-textarea"
              rows="2"
              @keydown.enter.prevent="applyEdit(idx)"
            />
            <div class="edit-actions">
              <span class="edit-hint">末尾空格+数字=章节序号，如 <code>123456 3</code></span>
              <button class="apply-btn" @click="applyEdit(idx)">应用</button>
            </div>
          </div>
        </div>
      </TransitionGroup>
    </div>
    </IonContent>

    <!-- 收藏夹选择器 -->
    <FavoriteFolderPicker
      v-model="showFolderPicker"
      :online-folders="[]"
      :offline-folders="offlineFolders"
      :online-folder-counts="{}"
      :hide-online="true"
      @select="onFavFolderSelect"
      @add-folder="onAddFolder"
    />

    <section
      v-if="files.length > 0"
      class="search-drawer"
      :class="{ active: drawerState !== 'closed' }"
      :style="drawerStyle"
    >
      <div class="drawer-grip-zone" @pointerdown="startDrawerDrag">
        <Transition name="drawer-confirm">
          <button
            v-if="selectedCandidate"
            type="button"
            class="drawer-confirm-btn"
            :disabled="!canConfirmCandidate"
            @click.stop="confirmCandidate"
          >
            {{ confirmButtonLabel }}
          </button>
        </Transition>
        <div class="drawer-grip"/>
      </div>

      <div class="drawer-body">
        <div class="drawer-target">
          <span class="drawer-title">搜索匹配</span>
          <span class="drawer-subtitle">{{ drawerTargetText }}</span>
        </div>
        <SearchHeaderBar
          ref="drawerSearchRef"
          :query="drawerQuery"
          :loading="drawerSearchLoading"
          @search="submitDrawerSearch"
        />
        <Transition name="chapter-panel">
          <section v-if="selectedCandidate && candidateChapters.length > 1" class="chapter-select-panel">
            <div class="chapter-select-head">
              <span class="chapter-select-title">选择章节</span>
              <span v-if="candidateDetailLoading" class="chapter-select-loading">加载中...</span>
            </div>
            <div class="chapter-list">
              <button
                v-for="chapter in candidateChapters"
                :key="chapter.id"
                type="button"
                class="chapter-option"
                :class="{ active: selectedChapterSortOrder === chapter.sortOrder }"
                @click="selectedChapterSortOrder = chapter.sortOrder"
              >
                <span class="chapter-order">order {{ chapter.sortOrder }}</span>
                <span class="chapter-name">{{ chapter.title }}</span>
              </button>
            </div>
          </section>
        </Transition>
        <SearchResultContainer
          :result="drawerResult"
          :items="drawerDisplayItems"
          :loading="drawerSearchLoading"
          :loading-next="false"
          :error-message="drawerError"
          :mode="drawerMode"
          idle-text="点击未解析卡片后自动搜索"
          empty-text="没有匹配结果"
          @mode-change="drawerMode = $event"
          @item-click="selectCandidate"
          @retry="retryDrawerSearch"
        >
          <template #item-actions="{ item }">
            <button
              type="button"
              class="drawer-detail-btn"
              aria-label="查看详情"
              @click.stop="openCandidateDetail(item)"
            >
              <IonIcon :icon="informationCircleOutline"/>
            </button>
          </template>
        </SearchResultContainer>
      </div>
    </section>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({ name: 'PdfImportPage' })

import { ref, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { IonBackButton, IonButtons, IonPage, IonHeader, IonTitle, IonToolbar, IonContent, IonIcon } from '@ionic/vue'
import { alertController } from '@ionic/vue'
import { useRouter } from 'vue-router'
import { documentTextOutline, createOutline, checkmarkOutline, informationCircleOutline, searchOutline } from 'ionicons/icons'
import { PdfImportService } from '@/services/PdfImportService'
import { JmcomicService, sanitizeError, showToast } from '@/services/JmcomicService'
import { OfflineFavoriteService } from '@/services/OfflineFavoriteService'
import FavoriteFolderPicker from '@/components/favorite/FavoriteFolderPicker.vue'
import SearchHeaderBar from '@/components/search/SearchHeaderBar.vue'
import SearchResultContainer from '@/components/search/SearchResultContainer.vue'
import type { PdfFileParseItem } from '@/utils/importPdfParse'
import type { AlbumDetail, FolderEntry, PhotoMeta, SearchQuery, SearchResult, SearchResultItem } from '@/services/JmcomicTypes'
import type { SearchResultDisplayItem } from '@/components/search/SearchResultContainer.vue'

const router = useRouter()

// ---- 状态 ----
const files = ref<PdfFileParseItem[]>([])
const loading = ref(true)
const editingIdx = ref<number | null>(null)
const editText = ref('')

// ---- 收藏夹 ----
const showFolderPicker = ref(false)
const offlineFolders = ref<FolderEntry[]>([])
const pendingResolvedFiles = ref<PdfFileParseItem[]>([])
const selectedFolderId = ref<string | undefined>(undefined)

// ---- 抽屉搜索 ----
type DrawerState = 'closed' | 'half' | 'full'

const DRAWER_MAX_RATIO = 0.86
const DRAWER_HANDLE_HEIGHT = 54

const drawerState = ref<DrawerState>('closed')
const drawerDragOffset = ref<number | null>(null)
const drawerSearchRef = ref<{ focusInput: () => Promise<void> } | null>(null)
const searchTargetIdx = ref<number | null>(null)
const drawerQuery = ref<SearchQuery>({
  keyword: '',
  orderBy: 'mr',
  time: 'a',
  searchMainTag: 0,
  page: 1,
})
const drawerResult = ref<SearchResult | null>(null)
const drawerSearchLoading = ref(false)
const drawerError = ref('')
const drawerMode = ref<'list' | 'grid'>('list')
const selectedCandidate = ref<SearchResultItem | null>(null)
const selectedCandidateDetail = ref<AlbumDetail | null>(null)
const candidateDetailLoading = ref(false)
const selectedChapterSortOrder = ref<number | null>(null)
const resolvingCandidate = ref(false)

let drawerDragStartY = 0
let drawerDragStartOffset = 0
let candidateDetailRequestSeq = 0

const drawerDisplayItems = computed<SearchResultDisplayItem[]>(() =>
  (drawerResult.value?.content ?? []).map((item, indexInPage) => ({
    item,
    page: drawerResult.value?.currentPage ?? 1,
    indexInPage,
  })),
)

const drawerTargetText = computed(() => {
  if (searchTargetIdx.value === null) return '先点击需要补 ID 的文件卡片'
  return files.value[searchTargetIdx.value]?.fileName ?? '当前文件'
})

const candidateChapters = computed<PhotoMeta[]>(() =>
  [...(selectedCandidateDetail.value?.photoMetas ?? [])].sort((a, b) => a.sortOrder - b.sortOrder),
)

const resolveChapter = (detail?: AlbumDetail | null, sortOrder?: number): PhotoMeta | null => {
  const chapters = detail?.photoMetas ?? []
  if (sortOrder !== undefined) {
    return chapters.find((chapter) => chapter.sortOrder === sortOrder) ?? null
  }
  return chapters.length === 1 ? chapters[0] : null
}

const selectedCandidateChapter = computed(() =>
  resolveChapter(selectedCandidateDetail.value, selectedChapterSortOrder.value ?? undefined),
)

const confirmButtonLabel = computed(() => {
  if (!selectedCandidate.value) return ''
  if (candidateChapters.value.length > 1) {
    return selectedChapterSortOrder.value
      ? `确定 #${selectedCandidate.value.id} order ${selectedChapterSortOrder.value}`
      : '选择章节后确认'
  }
  return `确定 #${selectedCandidate.value.id}`
})

const canConfirmCandidate = computed(() =>
  !!selectedCandidate.value &&
  !drawerSearchLoading.value &&
  !candidateDetailLoading.value &&
  !resolvingCandidate.value &&
  (candidateChapters.value.length <= 1 || selectedChapterSortOrder.value !== null),
)

const drawerMaxHeight = () => Math.round(window.innerHeight * DRAWER_MAX_RATIO)

const drawerClosedOffset = () => Math.max(0, drawerMaxHeight() - DRAWER_HANDLE_HEIGHT)

const drawerHalfOffset = () => Math.round(drawerMaxHeight() * 0.42)

const drawerBaseOffset = () => {
  if (drawerState.value === 'full') return 0
  if (drawerState.value === 'half') return drawerHalfOffset()
  return drawerClosedOffset()
}

const drawerStyle = computed(() => ({
  transform: `translateY(${drawerDragOffset.value ?? drawerBaseOffset()}px)`,
}))

const canSearchFile = (file: PdfFileParseItem) =>
  file.status !== 'resolved' || file.duplicateIds.length > 0 || (file.editedIds?.length ?? file.extractedIds.length) !== 1

const stripPdfExtension = (fileName: string) => fileName.replace(/\.pdf$/i, '').trim()

// ---- 统计 ----
const resolvedCount = computed(() =>
  files.value.filter((f) =>
    f.status === 'resolved' && (f.editedIds?.length === 1 || f.extractedIds.length === 1),
  ).length,
)
const ambiguousCount = computed(() =>
  files.value.filter((f) => f.status === 'ambiguous').length,
)
const missingCount = computed(() =>
  files.value.filter((f) => f.status === 'missing').length,
)
const duplicateCount = computed(() =>
  files.value.filter((f) => f.duplicateIds.length > 0).length,
)
const hasAnyResolved = computed(() =>
  files.value.some((f) =>
    f.editedIds?.length === 1 ||
    (f.status === 'resolved' && f.extractedIds.length === 1),
  ),
)

// ---- 初始化 ----
onMounted(async () => {
  const cached = PdfImportService.getCachedParseResult()
  if (!cached || cached.files.length === 0) {
    loading.value = false
    await showToast('未接收到扫描数据，请返回重新选择文件夹', 'danger')
    return
  }
  files.value = cached.files
  loading.value = true

  // 加载离线收藏夹列表（备用）
  try {
    await OfflineFavoriteService.ensureInit()
    offlineFolders.value = OfflineFavoriteService.getFolders()
  } catch {
    // 离线收藏夹加载失败不影响导入
  }

  // 并发获取相册详情（网络失败不阻塞）
  await PdfImportService.fetchAlbumDetails(files.value)
  loading.value = false
})

// ---- 编辑 ----
function toggleEdit(idx: number) {
  if (editingIdx.value === idx) {
    editingIdx.value = null
    return
  }
  const file = files.value[idx]
  // 用原始文件名 + ID 信息初始化编辑框
  if (file.editedLine) {
    editText.value = file.editedLine
  } else if (file.extractedIds.length > 0) {
    editText.value = file.extractedIds.join(' ')
    if (file.chapterSortOrder != null) {
      editText.value += ` ${file.chapterSortOrder}`
    }
  } else {
    editText.value = ''
  }
  editingIdx.value = idx
}

async function applyEdit(idx: number) {
  const { parseEditedLine } = await import('@/utils/importPdfParse')
  const file = files.value[idx]
  const { ids, chapterSortOrder } = parseEditedLine(editText.value.trim())

  const updated = { ...file, editedLine: editText.value.trim(), editedIds: ids, chapterSortOrder }

  // 编辑后重新判断状态
  if (ids.length === 0) {
    updated.status = 'missing'
  } else if (ids.length === 1) {
    updated.status = 'resolved'
  } else {
    updated.status = 'ambiguous'
  }

  // 如果用户编辑后只剩一个 ID，且详情缺失或与当前 ID 不一致，则重新获取。
  if (ids.length === 1 && updated.albumDetail?.id !== ids[0]) {
    try {
      const detail = await JmcomicService.getAlbum(ids[0])
      updated.albumDetail = detail && detail.id ? detail : null
    } catch {
      updated.albumDetail = null
    }
  }

  const chapter = resolveChapter(updated.albumDetail, chapterSortOrder)
  updated.chapterId = chapter?.id
  updated.chapterTitle = chapter?.title

  files.value[idx] = updated as PdfFileParseItem
  editingIdx.value = null
}

async function applyResolvedId(
  idx: number,
  id: string,
  chapter?: PhotoMeta | null,
  albumDetail?: AlbumDetail | null,
) {
  const file = files.value[idx]
  const chapterSortOrder = chapter?.sortOrder
  const updated = {
    ...file,
    editedLine: chapterSortOrder ? `${id} ${chapterSortOrder}` : id,
    editedIds: [id],
    chapterId: chapter?.id,
    chapterTitle: chapter?.title,
    chapterSortOrder,
    status: 'resolved' as const,
  }

  if (albumDetail !== undefined) {
    updated.albumDetail = albumDetail
  } else {
    try {
      const detail = await JmcomicService.getAlbum(id)
      updated.albumDetail = detail && detail.id ? detail : null
    } catch {
      updated.albumDetail = null
    }
  }

  files.value[idx] = updated as PdfFileParseItem
}

// ---- 抽屉搜索行为 ----
async function openSearchDrawer(idx: number) {
  const file = files.value[idx]
  if (!file || !canSearchFile(file) || editingIdx.value === idx) return

  searchTargetIdx.value = idx
  selectedCandidate.value = null
  selectedCandidateDetail.value = null
  selectedChapterSortOrder.value = null
  drawerQuery.value = {
    keyword: stripPdfExtension(file.fileName),
    orderBy: 'mr',
    time: 'a',
    searchMainTag: 0,
    page: 1,
  }
  drawerState.value = 'half'
  await nextTick()
  void drawerSearchRef.value?.focusInput?.()
  await performDrawerSearch(drawerQuery.value)
}

async function submitDrawerSearch(query: SearchQuery) {
  const nextQuery = {...query, page: 1}
  drawerQuery.value = nextQuery
  await performDrawerSearch(nextQuery)
}

async function retryDrawerSearch() {
  await performDrawerSearch(drawerQuery.value)
}

async function performDrawerSearch(query: SearchQuery) {
  const keyword = (query.keyword ?? '').trim()
  selectedCandidate.value = null
  selectedCandidateDetail.value = null
  selectedChapterSortOrder.value = null
  drawerError.value = ''
  drawerResult.value = null

  if (!keyword) return

  drawerSearchLoading.value = true
  try {
    if (/^\d+$/.test(keyword)) {
      const album = await JmcomicService.getAlbum(keyword)
      drawerResult.value = {
        currentPage: 1,
        totalItems: 1,
        totalPages: 1,
        content: [{
          id: album.id,
          title: album.title,
          coverUrl: album.image,
          authors: album.authors,
          tags: album.tags,
        }],
      }
    } else {
      drawerResult.value = await JmcomicService.search({...query, keyword, page: 1})
    }

    const onlyResult = drawerResult.value.content.length === 1
      ? drawerResult.value.content[0]
      : null
    if (onlyResult) {
      await selectCandidate(onlyResult)
    }
  } catch (error) {
    drawerResult.value = null
    drawerError.value = sanitizeError(error, '搜索失败')
  } finally {
    drawerSearchLoading.value = false
  }
}

async function selectCandidate(item: SearchResultItem) {
  selectedCandidate.value = item
  selectedCandidateDetail.value = null
  selectedChapterSortOrder.value = null
  candidateDetailLoading.value = true
  const requestSeq = ++candidateDetailRequestSeq
  try {
    const detail = await JmcomicService.getAlbum(item.id)
    if (requestSeq !== candidateDetailRequestSeq) return
    selectedCandidateDetail.value = detail && detail.id ? detail : null
  } catch {
    if (requestSeq !== candidateDetailRequestSeq) return
    selectedCandidateDetail.value = null
  } finally {
    if (requestSeq === candidateDetailRequestSeq) {
      candidateDetailLoading.value = false
    }
  }
}

function openCandidateDetail(item: SearchResultItem) {
  void router.push({
    path: `/album/${item.id}`,
    query: {
      title: item.title,
      coverUrl: item.coverUrl,
      authors: item.authors.join(','),
    },
  })
}

async function confirmCandidate() {
  if (searchTargetIdx.value === null || !selectedCandidate.value || !canConfirmCandidate.value) return

  resolvingCandidate.value = true
  try {
    await applyResolvedId(
      searchTargetIdx.value,
      selectedCandidate.value.id,
      selectedCandidateChapter.value,
      selectedCandidateDetail.value,
    )
    selectedCandidate.value = null
    selectedCandidateDetail.value = null
    selectedChapterSortOrder.value = null
    await showToast('已回填 ID', 'success')
  } catch (error) {
    await showToast(sanitizeError(error, '回填失败'), 'danger')
  } finally {
    resolvingCandidate.value = false
  }
}

function startDrawerDrag(event: PointerEvent) {
  drawerDragStartY = event.clientY
  drawerDragStartOffset = drawerDragOffset.value ?? drawerBaseOffset()
  drawerDragOffset.value = drawerDragStartOffset
  window.addEventListener('pointermove', handleDrawerDrag)
  window.addEventListener('pointerup', endDrawerDrag)
  window.addEventListener('pointercancel', endDrawerDrag)
}

function handleDrawerDrag(event: PointerEvent) {
  const nextOffset = drawerDragStartOffset + event.clientY - drawerDragStartY
  drawerDragOffset.value = Math.min(drawerClosedOffset(), Math.max(0, nextOffset))
}

function endDrawerDrag() {
  const offset = drawerDragOffset.value ?? drawerBaseOffset()
  const half = drawerHalfOffset()
  const closed = drawerClosedOffset()

  if (offset < half * 0.55) {
    drawerState.value = 'full'
  } else if (offset > (half + closed) / 2) {
    drawerState.value = 'closed'
  } else {
    drawerState.value = 'half'
  }

  drawerDragOffset.value = null
  window.removeEventListener('pointermove', handleDrawerDrag)
  window.removeEventListener('pointerup', endDrawerDrag)
  window.removeEventListener('pointercancel', endDrawerDrag)
}

// ---- 卡片样式 ----
function cardClass(file: PdfFileParseItem) {
  const effectiveLen = file.editedIds?.length ?? file.extractedIds.length
  return {
    'card-resolved': file.status === 'resolved' && effectiveLen === 1 && file.duplicateIds.length === 0,
    'card-ambiguous': file.status === 'ambiguous',
    'card-missing': file.status === 'missing',
    'card-duplicate': file.duplicateIds.length > 0,
  }
}

function statusTagText(file: PdfFileParseItem): string {
  const id = file.editedIds?.[0] || file.extractedIds[0]
  if (file.status === 'missing') return '未识别ID'
  if (file.status === 'ambiguous') {
    return `候选: ${file.extractedIds.slice(0, 3).join(', ')}${file.extractedIds.length > 3 ? '...' : ''}`
  }
  if (file.duplicateIds.length > 0) return `ID重复 #${id}`
  return `已解析 #${id}`
}

function statusTagClass(file: PdfFileParseItem): string {
  if (file.status === 'missing') return 'tag-missing'
  if (file.status === 'ambiguous') return 'tag-ambiguous'
  if (file.duplicateIds.length > 0) return 'tag-duplicate'
  return 'tag-resolved'
}

// ---- 确认流程 ----
async function onConfirm() {
  const resolved = files.value.filter((f) => f.editedIds?.length === 1 || (f.status === 'resolved' && f.extractedIds.length === 1))

  // 确保 resolved 文件有 editedIds
  for (const f of resolved) {
    if (!f.editedIds || f.editedIds.length !== 1) {
      f.editedIds = [...f.extractedIds]
    }
  }

  const unresolved = files.value.filter((f) =>
    !(f.editedIds?.length === 1) && !(f.status === 'resolved' && f.extractedIds.length === 1),
  )

  if (resolved.length === 0) {
    await showToast('没有可导入的文件，请补充 ID 后重试', 'danger')
    return
  }

  if (unresolved.length > 0) {
    const alert = await alertController.create({
      header: '未解决的文件',
      message: `${unresolved.length} 个文件缺少有效 ID，将被忽略。仅导入 ${resolved.length} 个已解析的文件。`,
      buttons: [
        { text: '取消', role: 'cancel' },
        {
          text: '继续导入',
          handler: () => proceedToImport(resolved),
        },
      ],
    })
    await alert.present()
  } else {
    await proceedToImport(resolved)
  }
}

async function proceedToImport(resolvedFiles: PdfFileParseItem[]) {
  pendingResolvedFiles.value = resolvedFiles

  // 询问是否添加到离线收藏夹
  try {
    await OfflineFavoriteService.ensureInit()
    offlineFolders.value = OfflineFavoriteService.getFolders()
  } catch {
    // 忽略
  }

  const favAlert = await alertController.create({
    header: '添加到离线收藏夹？',
    message: `是否将这 ${resolvedFiles.length} 个 PDF 添加到离线收藏夹？`,
    buttons: [
      {
        text: '跳过',
        handler: () => doImport(resolvedFiles, undefined),
      },
      {
        text: '选择收藏夹',
        handler: () => {
          showFolderPicker.value = true
        },
      },
    ],
  })
  await favAlert.present()
}

async function onFavFolderSelect(payload: { folderId: string; source: string }) {
  showFolderPicker.value = false
  selectedFolderId.value = payload.folderId
  await doImport(pendingResolvedFiles.value, payload.folderId)
}

async function onAddFolder() {
  // 创建新离线收藏夹
  const alert = await alertController.create({
    header: '新建收藏夹',
    inputs: [{ name: 'name', placeholder: '收藏夹名称' }],
    buttons: [
      { text: '取消', role: 'cancel' },
      {
        text: '创建',
        handler: async (data) => {
          if (data.name) {
            try {
              await OfflineFavoriteService.createFolder(data.name)
              offlineFolders.value = await OfflineFavoriteService.getFolders()
            } catch {
              await showToast('创建收藏夹失败', 'danger')
            }
          }
        },
      },
    ],
  })
  await alert.present()
}

async function doImport(resolvedFiles: PdfFileParseItem[], folderId?: string) {
  loading.value = true
  try {
    const result = await PdfImportService.confirmImport(resolvedFiles, folderId)
    // 同步写入离线收藏夹
    if (folderId) {
      const favItems: SearchResultItem[] = resolvedFiles
        .filter((f) => (f.editedIds ?? f.extractedIds).length === 1)
        .map((f) => ({
          id: (f.editedIds ?? f.extractedIds)[0],
          title: f.albumDetail?.title || f.fileName,
          coverUrl: f.albumDetail?.image || '',
          authors: f.albumDetail?.authors || [],
          tags: [],
        }))
      await OfflineFavoriteService.addItems(folderId, favItems)
    }
    const parts = [`已导入 ${result.imported} 个 PDF`]
    if (result.duplicateCount > 0) parts.push(`${result.duplicateCount} 个已存在`)
    if (result.errorCount > 0) parts.push(`${result.errorCount} 个错误`)
    await showToast(parts.join('，'), 'success')
    PdfImportService.clearCachedParseResult()
    router.replace('/download')
  } catch (e: any) {
    await showToast(
      sanitizeError(e, '导入失败'),
      'danger',
    )
  } finally {
    loading.value = false
  }
}

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', handleDrawerDrag)
  window.removeEventListener('pointerup', endDrawerDrag)
  window.removeEventListener('pointercancel', endDrawerDrag)
  PdfImportService.clearCachedParseResult()
})
</script>

<style scoped>
IonHeader {
  --ion-background-color: var(--ion-background-color, #fff);
}

.toolbar-title {
  font-size: 16px;
  font-weight: 600;
  color: #4c2a18;
}

/* ---- 页面容器 ---- */
.page-container {
  padding: 12px 14px 132px;
}

/* ---- 确认按钮 ---- */
.confirm-btn {
  background: var(--ion-color-primary, #3880ff);
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
}

.confirm-btn:disabled {
  opacity: 0.4;
}

/* ---- 空状态 ---- */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 20px;
  color: #999;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 12px;
}

/* ---- 统计栏 ---- */
.stats-card {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px 16px;
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 2px 12px rgba(115, 67, 38, 0.06);
  margin-bottom: 14px;
}

.stat {
  font-size: 13px;
  padding: 4px 10px;
  border-radius: 12px;
  font-weight: 500;
}

.stat.resolved {
  background: #e8f5e9;
  color: #2e7d32;
}

.stat.ambiguous {
  background: #fff8e1;
  color: #f57c00;
}

.stat.missing {
  background: #ffebee;
  color: #c62828;
}

.stat.duplicate {
  background: #ede7f6;
  color: #4527a0;
}

/* ---- 文件列表 ---- */
.file-list {
}

/* TransitionGroup 动画 */
.card-list-enter-active,
.card-list-leave-active {
  transition: all 0.3s ease;
}

.card-list-enter-from {
  opacity: 0;
  transform: translateY(16px);
}

.card-list-leave-to {
  opacity: 0;
  transform: translateX(-20px);
}

.card-list-move {
  transition: transform 0.3s ease;
}

/* ---- 文件卡片 ---- */
.file-card {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(115, 67, 38, 0.08);
  margin-bottom: 10px;
  padding: 0 10px 0 0;
  overflow: hidden;
  border-left: 4px solid transparent;
  position: relative;
  min-height: 108px;
}

.file-card.searchable {
  cursor: pointer;
}

.file-card.searchable:active {
  transform: scale(0.99);
}

.file-card.selected {
  box-shadow: 0 4px 18px rgb(250 156 105 / 0.22);
}

.file-card.card-resolved {
  border-left-color: #4caf50;
}

.file-card.card-ambiguous {
  border-left-color: #ff9800;
}

.file-card.card-missing {
  border-left-color: #f44336;
}

.file-card.card-duplicate {
  border-left-color: #7c4dff;
}

/* ---- 封面 ---- */
.cover-wrap {
  width: 80px;
  aspect-ratio: 3 / 4;
  flex-shrink: 0;
  border-radius: 6px;
  overflow: hidden;
  background: linear-gradient(145deg, #f3ded0, #ffece0);
}

.cover-img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
}

.cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c4a48d;
  font-size: 28px;
}

.file-card.searchable .cover-placeholder {
  color: #fa9c69;
  background: linear-gradient(145deg, #fff6ef, #ffe2d0);
}

/* ---- 信息区 ---- */
.info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 8px 2px 8px 4px;
}

.item-title {
  font-size: 11px;
  font-weight: 700;
  line-height: 1.4;
  color: #30201a;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin: 0;
}

.item-meta {
  font-size: 11px;
  line-height: 1.35;
  color: #876653;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-name-line {
  word-break: break-all;
  white-space: normal;
}

/* ---- 标签 chips ---- */
.item-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.tag-chip {
  max-width: 120px;
  padding: 3px 7px;
  border-radius: 999px;
  background: #fff0e7;
  color: #9b5a35;
  font-size: 10px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tag-chip.tag-more {
  background: transparent;
  color: #9b5a35;
  padding: 3px 4px;
}

/* ---- 状态标签行 ---- */
.status-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 2px;
}

.status-tag {
  font-size: 10px;
  padding: 3px 7px;
  border-radius: 999px;
  font-weight: 500;
  white-space: nowrap;
}

.status-tag.tag-resolved {
  background: #e8f5e9;
  color: #2e7d32;
}

.status-tag.tag-ambiguous {
  background: #fff8e1;
  color: #e65100;
}

.status-tag.tag-missing {
  background: #ffebee;
  color: #c62828;
}

.status-tag.tag-duplicate {
  background: #ede7f6;
  color: #4527a0;
}

.status-tag.chapter-tag {
  background: #e3f2fd;
  color: #1565c0;
}

/* ---- 编辑按钮 ---- */
.edit-btn {
  position: absolute;
  right: 2px;
  top: 50%;
  transform: translateY(-50%);
  background: none;
  border: none;
  padding: 6px;
  cursor: pointer;
  color: #b0886a;
  font-size: 16px;
  z-index: 1;
}

/* ---- 编辑遮罩 ---- */
.edit-overlay {
  position: absolute;
  inset: 0;
  background: #fff;
  border-radius: 12px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 2;
}

.edit-textarea {
  flex: 1;
  width: 100%;
  border: 1px solid #e0c8b0;
  border-radius: 6px;
  padding: 8px;
  font-size: 13px;
  font-family: monospace;
  resize: none;
  min-height: 40px;
  background: #fff;
  color: #4c2a18;
}

.edit-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.edit-hint {
  font-size: 11px;
  color: #b09880;
}

.edit-hint code {
  background: #f0ede8;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 10px;
}

.apply-btn {
  background: var(--ion-color-primary, #3880ff);
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 5px 12px;
  font-size: 12px;
  cursor: pointer;
  flex-shrink: 0;
}

/* ---- 底部搜索抽屉 ---- */
.search-drawer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 30;
  height: 86vh;
  padding-bottom: var(--ion-safe-area-bottom);
  border-radius: 18px 18px 0 0;
  background: #fffaf6;
  box-shadow: 0 -10px 28px rgb(76 42 24 / 0.16);
  transition: transform 0.24s ease;
  will-change: transform;
}

.search-drawer.active {
  box-shadow: 0 -12px 34px rgb(76 42 24 / 0.22);
}

.drawer-grip-zone {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 54px;
  touch-action: none;
  user-select: none;
}

.drawer-grip {
  width: 48px;
  height: 5px;
  border-radius: 999px;
  background: #d2aa91;
}

.drawer-confirm-btn {
  position: absolute;
  left: 50%;
  bottom: 42px;
  transform: translateX(-50%);
  height: 34px;
  max-width: calc(100vw - 32px);
  padding: 0 16px;
  border: 0;
  border-radius: 999px;
  background: #fa9c69;
  color: #fff;
  box-shadow: 0 6px 16px rgb(250 156 105 / 0.35);
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
}

.drawer-confirm-btn:disabled {
  opacity: 0.6;
}

.drawer-confirm-enter-active,
.drawer-confirm-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.drawer-confirm-enter-from,
.drawer-confirm-leave-to {
  opacity: 0;
  transform: translate(-50%, 8px);
}

.drawer-body {
  height: calc(100% - 54px);
  overflow-y: auto;
  padding: 0 8px 18px;
}

.drawer-target {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
  padding: 0 6px;
}

.drawer-title {
  flex-shrink: 0;
  color: #4c2a18;
  font-size: 14px;
  font-weight: 700;
}

.drawer-subtitle {
  min-width: 0;
  overflow: hidden;
  color: #9b735a;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer-body :deep(.result-shell) {
  margin: 10px 0 86px;
  padding-top: 0;
}

.drawer-body :deep(.result-toolbar) {
  position: static;
}

.drawer-body :deep(.result-list),
.drawer-body :deep(.result-grid) {
  padding-bottom: 24px;
}

.drawer-detail-btn {
  position: absolute;
  right: 8px;
  top: 8px;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 999px;
  background: rgb(255 250 246 / 0.92);
  color: #9b5a35;
  box-shadow: 0 4px 12px rgb(76 42 24 / 0.14);
  font-size: 17px;
}

.chapter-select-panel {
  margin: 10px 0 0;
  padding: 10px;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 8px 22px rgb(76 42 24 / 0.12);
}

.chapter-select-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.chapter-select-title {
  color: #4c2a18;
  font-size: 13px;
  font-weight: 700;
}

.chapter-select-loading {
  color: #9b735a;
  font-size: 11px;
}

.chapter-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 190px;
  overflow-y: auto;
}

.chapter-option {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  min-height: 38px;
  padding: 7px 9px;
  border: 1px solid rgb(245 210 188 / 0.72);
  border-radius: 10px;
  background: #fffaf6;
  color: #5b3a28;
  text-align: left;
}

.chapter-option.active {
  border-color: #fa9c69;
  background: #fff0e7;
  box-shadow: inset 0 0 0 1px rgb(250 156 105 / 0.32);
}

.chapter-order {
  flex-shrink: 0;
  padding: 3px 7px;
  border-radius: 999px;
  background: #fa9c69;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
}

.chapter-name {
  min-width: 0;
  overflow: hidden;
  font-size: 12px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chapter-panel-enter-active,
.chapter-panel-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.chapter-panel-enter-from,
.chapter-panel-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

@media (min-width: 680px) {
  .search-drawer {
    left: max(0px, calc((100vw - 720px) / 2));
    right: max(0px, calc((100vw - 720px) / 2));
  }
}
</style>
