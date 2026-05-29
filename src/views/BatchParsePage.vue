<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <div class="header-row">
          <div class="toolbar-start">
            <MenuToggleButton/>
          </div>
          <button type="button" class="header-btn" @click="sourceExpanded = !sourceExpanded">
            <span class="header-btn-label">{{ sourceExpanded ? '收起原文' : '展开原文' }}</span>
            <IonIcon :icon="sourceExpanded ? chevronUpOutline : chevronDownOutline"/>
          </button>
          <button type="button" class="header-btn" :class="{ active: editing }" @click="toggleEdit">
            <IonIcon :icon="createOutline"/>
          </button>
        </div>

        <div v-show="sourceExpanded" class="source-area">
          <template v-if="editing">
            <div class="edit-toolbar">
              <input v-model="findText" class="edit-input" placeholder="查找" enterkeyhint="done"/>
              <input
                v-model="replaceText"
                class="edit-input"
                placeholder="替换为"
                enterkeyhint="done"
              />
              <button type="button" class="edit-btn" @click="replaceAll">全部替换</button>
              <button type="button" class="edit-btn" @click="removeSpaces">去除空格</button>
              <button type="button" class="edit-btn apply" @click="applyEdit">应用修改</button>
            </div>
            <textarea v-model="editText" class="source-textarea" rows="5" spellcheck="false"/>
          </template>

          <div v-else ref="sourceScrollRef" class="source-scroll">
            <pre
              v-for="(line, li) in sourceLines"
              :key="li"
              class="source-line"
              :class="{
                'current-line': li === currentHighlightLine,
                'no-results': line.hasOnlyFailed,
              }"
              @click="onSourceLineClick(li)"
            ><span
              v-for="(seg, si) in line.segments"
              :key="si"
              :class="segClass(seg.type)"
            >{{ seg.text }}</span></pre>
          </div>
        </div>

        <div v-show="sourceExpanded && !editing" class="source-actions-row">
          <label class="fav-toggle-label">
            <span class="fav-toggle-text">显示已收藏</span>
            <button
              type="button"
              class="fav-toggle"
              :class="{ on: showFavStatus }"
              :disabled="loadingFavStatus"
              role="switch"
              :aria-checked="showFavStatus"
              @click="toggleFavStatus"
            >
              <IonSpinner v-if="loadingFavStatus" name="dots" class="fav-toggle-spinner"/>
              <span v-else class="fav-toggle-knob"/>
            </button>
          </label>
          <button
            type="button"
            class="save-list-btn"
            :disabled="loading || albumResults.every(a => a === null)"
            @click="openBatchSave"
          >
            <IonIcon :icon="bookmarkOutline" class="save-list-icon"/>
            <span>保存当前列表到收藏夹</span>
          </button>
        </div>
      </IonToolbar>
    </IonHeader>

    <IonContent ref="contentRef" :scroll-events="true" @ion-scroll="onContentScroll">
      <div class="page-shell">
        <SearchResultContainer
          ref="resultContainerRef"
          :result="resultMeta"
          :items="displayItems"
          :loading="loading"
          :loading-previous="false"
          :loading-next="false"
          :can-load-previous="false"
          :error-message="errorMessage"
          :mode="displayMode"
          :fav-border-class-map="favBorderClassMap"
          idle-text=""
          empty-text="未找到对应本子"
          @item-click="handleItemClick"
          @mode-change="displayMode = $event"
        >
          <template v-if="displayMode === 'list'" #item-actions="{ item }">
            <button
              type="button"
              class="card-more-btn"
              :class="{ active: cardMenu?.item.id === item.id }"
              aria-label="更多操作"
              @click.stop="openCardMenu(item, $event)"
            >
              <IonIcon :icon="ellipsisVertical"/>
            </button>
          </template>
        </SearchResultContainer>
      </div>
    </IonContent>

    <!-- 卡片操作上下文菜单 -->
    <div
      v-if="cardMenu"
      ref="cardMenuRef"
      class="card-context-menu"
      :style="cardMenuStyle"
      @mousedown.prevent.stop
      @touchstart.stop
    >
      <button type="button" class="card-menu-item" @click.stop="handleCardDetail(cardMenu.item)">
        <IonIcon :icon="informationCircleOutline" class="card-menu-icon"/>
        <span>详情</span>
      </button>
      <button type="button" class="card-menu-item" @click.stop="handleCardRead(cardMenu.item)">
        <IonIcon :icon="bookOutline" class="card-menu-icon"/>
        <span>阅读</span>
      </button>
      <button type="button" class="card-menu-item" @click.stop="handleCardDownload(cardMenu.item)">
        <IonIcon :icon="downloadOutline" class="card-menu-icon"/>
        <span>下载</span>
      </button>
      <button type="button" class="card-menu-item" @click.stop="handleCardFavorite(cardMenu.item)">
        <IonIcon :icon="heartOutline" class="card-menu-icon"/>
        <span>收藏</span>
      </button>
      <button
        type="button"
        class="card-menu-item card-menu-item--danger"
        @click.stop="handleCardRemove(cardMenu.item)"
      >
        <IonIcon :icon="trashOutline" class="card-menu-icon"/>
        <span>从列表移除</span>
      </button>
    </div>

    <!-- 收藏夹选择弹窗 -->
    <FavoriteFolderPicker
      v-model="showFolderPicker"
      :online-folders="pickerOnlineFolders"
      :offline-folders="pickerOfflineFolders"
      :online-folder-counts="pickerOnlineFolderCounts"
      @select="onPickerSelect"
      @add-folder="onPickerAddFolder"
    />

    <!-- 操作进度遮罩 -->
    <div v-if="showProgressModal" class="progress-overlay">
      <div class="progress-dialog">
        <div class="progress-title">{{ progressTitle }}</div>
        <div class="progress-bar">
          <div class="progress-fill" :style="{ width: progressPercent + '%' }"/>
        </div>
        <div class="progress-percent">{{ progressPercent }}%</div>
        <div class="progress-count">已完成：{{ progressCurrent }} / {{ progressTotal }} 个本子</div>
        <div v-if="progressCurrentItem" class="progress-file">{{ progressCurrentItem }}</div>
        <div class="progress-warning">请勿关闭应用</div>
      </div>
    </div>
  </IonPage>
</template>

<script setup lang="ts">
import {computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch,} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {alertController, IonContent, IonHeader, IonIcon, IonPage, IonSpinner, IonToolbar,} from '@ionic/vue'
import {
  bookmarkOutline,
  bookOutline,
  chevronDownOutline,
  chevronUpOutline,
  createOutline,
  downloadOutline,
  ellipsisVertical,
  heartOutline,
  informationCircleOutline,
  trashOutline,
} from 'ionicons/icons'
import type {ScrollCustomEvent} from '@ionic/core'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import FavoriteFolderPicker from '@/components/favorite/FavoriteFolderPicker.vue'
import type {SearchResultDisplayItem} from '@/components/search/SearchResultContainer.vue'
import SearchResultContainer from '@/components/search/SearchResultContainer.vue'
import {JmcomicService, sanitizeError, showToast} from '@/services/JmcomicService'
import {OfflineDownloadService} from '@/services/OfflineDownloadService'
import {OfflineFavoriteService} from '@/services/OfflineFavoriteService'
import {useAuth} from '@/composables/useAuth'
import type {AlbumDetail, FavoriteResult, FolderEntry, SearchResult, SearchResultItem,} from '@/services/JmcomicTypes'

import type {InvalidIdItem, ParsedIdItem} from '@/utils/batchParse'
import {parseIdsFromText} from '@/utils/batchParse'

defineOptions({name: 'BatchParsePage'})

const STORAGE_PREFIX = 'batch-parse-text:'

interface LineSegment {
  text: string
  type: 'normal' | 'valid-id' | 'invalid-id' | 'failed-id'
}

interface SourceLine {
  segments: LineSegment[]
  hasOnlyFailed: boolean
}

const router = useRouter()
const route = useRoute()
const routeKey = computed(() => route.query.key as string)
const {isLoggedIn} = useAuth()
const contentRef = ref<InstanceType<typeof IonContent> | null>(null)
const resultContainerRef = ref<InstanceType<typeof SearchResultContainer> | null>(null)
const sourceScrollRef = ref<HTMLElement | null>(null)
const scrollElementRef = ref<HTMLElement | null>(null)
const savedScrollTop = ref(0)
const savedKey = ref<string>('')

const originalText = ref('')
const sourceExpanded = ref(true)
const currentHighlightLine = ref(-1)

const parsedItems = ref<ParsedIdItem[]>([])
const invalidIds = ref<InvalidIdItem[]>([])
const albumResults = ref<(AlbumDetail | null)[]>([])
const loading = ref(true)
const errorMessage = ref('')

const failedIds = ref<Record<string, boolean>>({})
const displayMode = ref<'list' | 'grid'>('list')

// ---- 编辑模式 ----

const editing = ref(false)
const editText = ref('')
const findText = ref('')
const replaceText = ref('')

function toggleEdit() {
  editing.value = !editing.value
  if (editing.value) {
    editText.value = originalText.value
    findText.value = ''
    replaceText.value = ''
  }
}

function replaceAll() {
  if (!findText.value) return
  const escaped = findText.value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  editText.value = editText.value.replace(new RegExp(escaped, 'g'), replaceText.value)
}

function removeSpaces() {
  editText.value = editText.value
    .split('\n')
    .map((line) => line.replace(/[ \t]+/g, ''))
    .join('\n')
}

async function applyEdit() {
  originalText.value = editText.value
  editing.value = false
  const key = routeKey.value
  if (key) {
    sessionStorage.setItem(STORAGE_PREFIX + key, editText.value)
  }
  await doParse()
}

// ---- 源文本渲染 ----

const sourceLines = computed<SourceLine[]>(() => {
  const lines = originalText.value.split('\n')
  const result: SourceLine[] = lines.map((text) => ({
    segments: [{text, type: 'normal' as const}],
    hasOnlyFailed: false,
  }))

  for (const inv of invalidIds.value) {
    const line = result[inv.lineIndex]
    if (!line) continue
    line.segments = splitSegments(line.segments, inv.startIndex, inv.endIndex, 'invalid-id')
  }

  for (let i = 0; i < parsedItems.value.length; i++) {
    const item = parsedItems.value[i]
    if (!failedIds.value[item.id]) continue
    const line = result[item.lineIndex]
    if (!line) continue
    line.segments = splitSegments(line.segments, item.startIndex, item.endIndex, 'failed-id')
  }

  for (let i = 0; i < parsedItems.value.length; i++) {
    const item = parsedItems.value[i]
    if (failedIds.value[item.id]) continue
    const line = result[item.lineIndex]
    if (!line) continue
    line.segments = splitSegments(line.segments, item.startIndex, item.endIndex, 'valid-id')
  }

  for (const line of result) {
    const hasValid = line.segments.some((s) => s.type === 'valid-id')
    const hasFailed = line.segments.some((s) => s.type === 'failed-id' || s.type === 'invalid-id')
    line.hasOnlyFailed = hasFailed && !hasValid
  }

  return result
})

function splitSegments(
  segments: LineSegment[],
  targetStart: number,
  targetEnd: number,
  type: LineSegment['type'],
): LineSegment[] {
  const out: LineSegment[] = []
  let cursor = 0

  for (const seg of segments) {
    const segEnd = cursor + seg.text.length
    if (segEnd <= targetStart || cursor >= targetEnd) {
      out.push(seg)
    } else {
      if (cursor < targetStart) {
        out.push({text: seg.text.slice(0, targetStart - cursor), type: seg.type})
      }
      const overlapStart = Math.max(cursor, targetStart)
      const overlapEnd = Math.min(segEnd, targetEnd)
      if (overlapEnd > overlapStart) {
        out.push({text: seg.text.slice(overlapStart - cursor, overlapEnd - cursor), type})
      }
      if (segEnd > targetEnd) {
        out.push({text: seg.text.slice(targetEnd - cursor), type: seg.type})
      }
    }
    cursor = segEnd
  }

  return out
}

function segClass(type: LineSegment['type']): string {
  switch (type) {
    case 'valid-id':
      return 'hl-valid'
    case 'invalid-id':
      return 'hl-invalid'
    case 'failed-id':
      return 'hl-failed'
    default:
      return ''
  }
}

// ---- 结果数据 ----

const resultMeta = computed<SearchResult | null>(() => {
  if (!originalText.value) return null
  return {
    currentPage: 1,
    totalItems: parsedItems.value.length,
    totalPages: 1,
    content: [],
  }
})

function albumToSearchItem(a: AlbumDetail): SearchResultItem {
  return {
    id: a.id,
    title: a.title,
    coverUrl: a.image,
    authors: a.authors,
    tags: a.tags,
  }
}

const displayItems = computed<SearchResultDisplayItem[]>(() => {
  return albumResults.value
    .map((a, i) => (a ? {item: albumToSearchItem(a), page: 1, indexInPage: i} : null))
    .filter((x): x is SearchResultDisplayItem => x !== null)
})

// ---- 解析逻辑 ----

async function doParse() {
  const text = originalText.value
  if (!text || !text.trim()) {
    errorMessage.value = '未接收到解析文本'
    loading.value = false
    return
  }

  const parseResult = parseIdsFromText(text)
  parsedItems.value = parseResult.items
  invalidIds.value = parseResult.invalidIds

  if (parseResult.items.length === 0) {
    errorMessage.value = '未发现有效 ID（需 3 位及以上数字）'
    loading.value = false
    return
  }

  loading.value = true
  errorMessage.value = ''
  const total = parseResult.items.length
  const results = new Array<AlbumDetail | null>(total).fill(null)
  const failed: Record<string, boolean> = {}
  albumResults.value = [...results]

  const concurrency = 5
  let cursor = 0

  async function worker() {
    while (cursor < total) {
      const i = cursor++
      try {
        const r = await JmcomicService.getAlbum(parseResult.items[i].id)
        if (r && r.id && r.title) {
          results[i] = r
        } else {
          failed[parseResult.items[i].id] = true
        }
      } catch {
        failed[parseResult.items[i].id] = true
      }
      albumResults.value = [...results]
      failedIds.value = {...failed}
    }
  }

  const workers = Array.from({length: Math.min(concurrency, total)}, () => worker())
  await Promise.all(workers)

  loading.value = false
  // 清除已收藏状态（解析结果变了）
  clearFavStatus()

  await nextTick()
  updateHighlightFromScroll()
}

async function resolveScrollElement() {
  if (scrollElementRef.value) return scrollElementRef.value
  const contentEl = contentRef.value?.$el as
    | { getScrollElement?: () => Promise<HTMLElement> }
    | undefined
  if (!contentEl?.getScrollElement) return null
  scrollElementRef.value = await contentEl.getScrollElement()
  return scrollElementRef.value
}

onDeactivated(() => {
  savedScrollTop.value = scrollElementRef.value?.scrollTop ?? 0
  scrollElementRef.value = null
})

onMounted(async () => {
  void resolveScrollElement()
  const key = routeKey.value
  const text = key ? sessionStorage.getItem(STORAGE_PREFIX + key) : null
  const fallback = text || sessionStorage.getItem('batch-parse-text')

  if (!fallback || !fallback.trim()) {
    errorMessage.value = '未接收到解析文本'
    loading.value = false
    return
  }

  savedKey.value = key || ''
  originalText.value = fallback
  await doParse()
})

onActivated(async () => {
  await nextTick()

  const currentKey = routeKey.value
  if (currentKey && currentKey !== savedKey.value) {
    const text = sessionStorage.getItem(STORAGE_PREFIX + currentKey)
    if (text && text.trim()) {
      savedKey.value = currentKey
      savedScrollTop.value = 0
      originalText.value = text
      editing.value = false
      await doParse()
      const scrollEl = scrollElementRef.value ?? (await resolveScrollElement())
      if (scrollEl) scrollEl.scrollTop = 0
      return
    }
  }

  const scrollEl = scrollElementRef.value ?? (await resolveScrollElement())
  if (scrollEl && savedScrollTop.value > 0) {
    scrollEl.scrollTop = savedScrollTop.value
  }
})

// ---- 双向联动 ----

let scrollTimer: ReturnType<typeof setTimeout> | null = null

function onContentScroll(_e: ScrollCustomEvent) {
  if (scrollTimer) clearTimeout(scrollTimer)
  scrollTimer = setTimeout(() => {
    updateHighlightFromScroll()
  }, 150)
}

function updateHighlightFromScroll() {
  if (!resultContainerRef.value) return

  const root = resultContainerRef.value.getRootElement()
  if (!root) return

  const cards = root.querySelectorAll<HTMLElement>('[data-entry-key]')
  if (cards.length === 0) {
    currentHighlightLine.value = -1
    return
  }

  let bestIdx = -1
  for (let i = cards.length - 1; i >= 0; i--) {
    const rect = cards[i].getBoundingClientRect()
    if (rect.top < window.innerHeight && rect.bottom > 0) {
      bestIdx = i
      break
    }
  }
  if (bestIdx < 0) {
    for (let i = cards.length - 1; i >= 0; i--) {
      if (cards[i].getBoundingClientRect().top < 0) {
        bestIdx = i
        break
      }
    }
  }

  if (bestIdx >= 0) {
    const entryKey = cards[bestIdx].dataset.entryKey
    if (entryKey) {
      const parts = entryKey.split('-')
      const idx = parseInt(parts[1], 10)
      if (!isNaN(idx) && idx < parsedItems.value.length) {
        currentHighlightLine.value = parsedItems.value[idx].lineIndex
      }
    }
  }
}

function onSourceLineClick(lineIndex: number) {
  const idx = parsedItems.value.findIndex((p) => p.lineIndex === lineIndex)
  if (idx < 0) return

  const entryKey = `1-${idx}-${parsedItems.value[idx].id}`
  const el = resultContainerRef.value?.getEntryElement(entryKey)
  if (el) {
    el.scrollIntoView({behavior: 'smooth', block: 'center'})
    currentHighlightLine.value = lineIndex
  }
}

watch(currentHighlightLine, async (lineIndex) => {
  if (lineIndex < 0 || !sourceScrollRef.value) return
  await nextTick()
  const el = sourceScrollRef.value.querySelector(`.source-line:nth-child(${lineIndex + 1})`)
  if (el) {
    el.scrollIntoView({block: 'nearest', behavior: 'smooth'})
  }
})

// ---- 生命周期 ----

onBeforeUnmount(() => {
  if (scrollTimer) clearTimeout(scrollTimer)
  document.removeEventListener('mousedown', handleCardMenuClickOutside)
  document.removeEventListener('touchstart', handleCardMenuClickOutside)
})

function handleItemClick(item: SearchResultItem) {
  router.push(`/album/${item.id}`)
}

// ========== 显示已收藏状态 ==========

const showFavStatus = ref(false)
const loadingFavStatus = ref(false)
const onlineFavIds = ref<Set<string>>(new Set())
const offlineFavIds = ref<Set<string>>(new Set())
const bothFavIds = ref<Set<string>>(new Set())

const favBorderClassMap = computed<Record<string, string>>(() => {
  if (!showFavStatus.value) return {}
  const map: Record<string, string> = {}
  for (const id of bothFavIds.value) {
    map[id] = 'fav-both'
  }
  for (const id of onlineFavIds.value) {
    map[id] = 'fav-online'
  }
  for (const id of offlineFavIds.value) {
    map[id] = 'fav-offline'
  }
  return map
})

function clearFavStatus() {
  showFavStatus.value = false
  onlineFavIds.value = new Set()
  offlineFavIds.value = new Set()
  bothFavIds.value = new Set()
}

async function fetchAllOnlineFavIds(): Promise<Set<string>> {
  const ids = new Set<string>()
  try {
    let page = 1
    let totalPages = 1
    while (page <= totalPages) {
      const result: FavoriteResult = await JmcomicService.favorites({folderId: '0', page})
      result.content.forEach((item) => ids.add(item.id))
      totalPages = result.totalPages
      page++
      if (page <= totalPages) {
        await new Promise((r) => setTimeout(r, 100))
      }
    }
  } catch {
    // 获取失败返回空集合
  }
  return ids
}

async function fetchAllOfflineFavIds(): Promise<Set<string>> {
  const ids = new Set<string>()
  try {
    const items = await OfflineFavoriteService.getAllItemsMerged()
    items.forEach((item) => ids.add(item.id))
  } catch {
    // ignore
  }
  return ids
}

async function toggleFavStatus() {
  if (loadingFavStatus.value) return

  if (showFavStatus.value) {
    clearFavStatus()
    return
  }

  loadingFavStatus.value = true
  try {
    const [onlineIds, offlineIds] = await Promise.all([
      fetchAllOnlineFavIds(),
      fetchAllOfflineFavIds(),
    ])

    const both = new Set<string>()
    for (const id of onlineIds) {
      if (offlineIds.has(id)) both.add(id)
    }
    for (const id of both) {
      onlineIds.delete(id)
      offlineIds.delete(id)
    }

    onlineFavIds.value = onlineIds
    offlineFavIds.value = offlineIds
    bothFavIds.value = both
    showFavStatus.value = true
  } catch {
    await showToast('获取收藏状态失败', 'danger')
  } finally {
    loadingFavStatus.value = false
  }
}

// ========== 卡片操作菜单 ==========

interface CardMenuState {
  item: SearchResultItem
  x: number
  y: number
}

const cardMenu = ref<CardMenuState | null>(null)
const cardMenuRef = ref<HTMLElement | null>(null)

const cardMenuStyle = computed(() => {
  const m = cardMenu.value
  if (!m) return {}
  const menuH = 224
  const top = m.y + menuH > window.innerHeight ? m.y - menuH - 8 : m.y
  return {
    position: 'fixed' as const,
    top: `${top}px`,
    left: `${Math.min(m.x, window.innerWidth - 160)}px`,
  }
})

function openCardMenu(item: SearchResultItem, event: MouseEvent) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  cardMenu.value = {item, x: rect.left, y: rect.bottom + 4}
  setTimeout(() => {
    document.addEventListener('mousedown', handleCardMenuClickOutside)
    document.addEventListener('touchstart', handleCardMenuClickOutside)
  }, 0)
}

function closeCardMenu() {
  cardMenu.value = null
  document.removeEventListener('mousedown', handleCardMenuClickOutside)
  document.removeEventListener('touchstart', handleCardMenuClickOutside)
}

function handleCardMenuClickOutside(e: Event) {
  if (cardMenuRef.value?.contains(e.target as Node)) return
  closeCardMenu()
}

function handleCardDetail(item: SearchResultItem) {
  closeCardMenu()
  router.push(`/album/${item.id}`)
}

async function handleCardRead(item: SearchResultItem) {
  closeCardMenu()
  try {
    const photo = await JmcomicService.getPhoto(item.id)
    await router.push({
      path: `/album/${item.id}/read/${photo.id}`,
      query: {title: item.title, total: String(photo.images.length)},
    })
  } catch {
    await showToast('获取章节失败', 'danger')
  }
}

async function handleCardDownload(item: SearchResultItem) {
  closeCardMenu()
  try {
    const photo = await JmcomicService.getPhoto(item.id)
    const taskId = `${item.id}_${photo.id}`
    const existing = OfflineDownloadService.getAll().find(
      (t) => t.taskId === taskId && t.status !== 'failed',
    )
    if (existing) {
      await showToast('该章节已在下载队列中', 'medium')
      return
    }
    await JmcomicService.downloadChapter(item.id, photo.id, item.title, photo.title, item.coverUrl)
    OfflineDownloadService.addTask({
      taskId,
      albumId: item.id,
      chapterId: photo.id,
      albumTitle: item.title,
      chapterTitle: photo.title,
      coverUrl: item.coverUrl,
      totalPages: 0,
      downloadedPages: 0,
      status: 'queued',
      createdAt: Date.now(),
    })
    await showToast('已加入下载队列', 'success')
  } catch {
    await showToast('获取章节失败', 'danger')
  }
}

function handleCardFavorite(item: SearchResultItem) {
  closeCardMenu()
  pickerMode.value = 'single'
  pickerTargetItem.value = item
  openFolderPickerForMode()
}

function handleCardRemove(item: SearchResultItem) {
  closeCardMenu()
  const idx = albumResults.value.findIndex((a) => a?.id === item.id)
  if (idx >= 0) {
    const updated = [...albumResults.value]
    updated[idx] = null
    albumResults.value = updated
  }
}

// ========== 收藏夹选择弹窗 ==========

type PickerMode = 'batch' | 'single'

const showFolderPicker = ref(false)
const pickerMode = ref<PickerMode>('batch')
const pickerTargetItem = ref<SearchResultItem | null>(null)
const pickerOnlineFolders = ref<FolderEntry[]>([])
const pickerOfflineFolders = ref<FolderEntry[]>([])
const pickerOnlineFolderCounts = ref<Record<string, number>>({})

async function loadOnlineFolderData() {
  if (!isLoggedIn.value) {
    pickerOnlineFolders.value = []
    return
  }
  try {
    const result: FavoriteResult = await JmcomicService.favorites({folderId: '0', page: 1})
    if (result.folderList) {
      const entries: FolderEntry[] = []
      const countPromises: Promise<void>[] = []
      const counts: Record<string, number> = {}
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
      pickerOnlineFolderCounts.value = counts
    }
  } catch {
    pickerOnlineFolders.value = []
  }
}

async function openFolderPickerForMode() {
  await OfflineFavoriteService.ensureInit()
  pickerOfflineFolders.value = OfflineFavoriteService.getFolders()
  void loadOnlineFolderData()
  showFolderPicker.value = true
}

function openBatchSave() {
  const hasResults = albumResults.value.some((a) => a !== null)
  if (!hasResults) return
  pickerMode.value = 'batch'
  pickerTargetItem.value = null
  openFolderPickerForMode()
}

async function executeSingleFavorite(
  item: SearchResultItem,
  payload: { folderId: string; source: 'online' | 'offline' },
) {
  try {
    if (payload.source === 'online') {
      const album = await JmcomicService.getAlbum(item.id)
      if (album?.isFavorite) {
        showToast('该本子已在收藏夹中', 'medium')
        return
      }
      await JmcomicService.toggleAlbumFavorite(item.id, payload.folderId)
      showToast('已收藏到在线收藏夹', 'success')
    } else {
      if (offlineFavIds.value.has(item.id) || bothFavIds.value.has(item.id)) {
        showToast('该本子已在离线收藏夹中', 'medium')
        return
      }
      await OfflineFavoriteService.addItem(payload.folderId, item)
      offlineFavIds.value = new Set([...offlineFavIds.value, item.id])
      showToast('已收藏到离线收藏夹', 'success')
    }
  } catch (e: any) {
    showToast(sanitizeError(e, '收藏失败'), 'danger')
  }
}

async function onPickerSelect(payload: { folderId: string; source: 'online' | 'offline' }) {
  showFolderPicker.value = false

  if (pickerMode.value === 'single' && pickerTargetItem.value) {
    const item = pickerTargetItem.value
    pickerTargetItem.value = null
    void executeSingleFavorite(item, payload)
    return
  }

  // 批量保存
  const validResults = albumResults.value.filter((a): a is AlbumDetail => a !== null)

  // 去重
  let alreadyFavIds: Set<string>
  if (payload.source === 'online') {
    if (showFavStatus.value) {
      alreadyFavIds = new Set([...onlineFavIds.value, ...bothFavIds.value])
    } else {
      alreadyFavIds = await fetchAllOnlineFavIds()
    }
  } else {
    if (showFavStatus.value) {
      alreadyFavIds = new Set([...offlineFavIds.value, ...bothFavIds.value])
    } else {
      alreadyFavIds = await fetchAllOfflineFavIds()
    }
  }

  const toAdd = validResults
    .map((a) => albumToSearchItem(a))
    .filter((item) => !alreadyFavIds.has(item.id))

  if (toAdd.length === 0) {
    await showToast('所有本子已收藏', 'medium')
    return
  }

  showProgressModal.value = true
  progressCurrent.value = 0
  progressCurrentItem.value = ''

  try {
    if (payload.source === 'offline') {
      progressTitle.value = '正在保存到离线收藏夹'
      progressTotal.value = 1
      await OfflineFavoriteService.addItems(payload.folderId, toAdd)
      progressCurrent.value = 1
      await showToast(`已保存 ${toAdd.length} 个本子到离线`, 'success')
    } else {
      progressTitle.value = '正在保存到在线收藏夹'
      progressTotal.value = toAdd.length
      let ok = 0
      const failed: string[] = []
      for (const item of toAdd) {
        try {
          await JmcomicService.toggleAlbumFavorite(item.id, payload.folderId)
          ok++
        } catch {
          failed.push(item.title || item.id)
        }
        progressCurrent.value = ok + failed.length
        progressCurrentItem.value = item.title || item.id
      }
      if (failed.length > 0) {
        await showToast(
          `已保存 ${ok}/${toAdd.length} 个，失败 ${failed.length} 项：${failed.slice(0, 3).join('、')}${failed.length > 3 ? ' 等' : ''}`,
          'medium',
        )
      } else {
        await showToast(`已保存 ${ok} 个本子到在线`, 'success')
      }
    }
  } catch (e: any) {
    await showToast(sanitizeError(e, '保存失败'), 'danger')
  } finally {
    showProgressModal.value = false
  }
}

async function onPickerAddFolder() {
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
                await loadOnlineFolderData()
              } else {
                await showToast(r.msg || '创建失败', 'danger')
              }
            } catch {
              await showToast('创建失败', 'danger')
            }
          } else {
            await OfflineFavoriteService.createFolder(name)
            pickerOfflineFolders.value = OfflineFavoriteService.getFolders()
            await showToast('收藏夹已创建', 'success')
          }
        },
      },
    ],
  })
  await alert.present()
}

// ========== 进度弹窗 ==========

const showProgressModal = ref(false)
const progressTitle = ref('')
const progressCurrent = ref(0)
const progressTotal = ref(0)
const progressCurrentItem = ref('')

const progressPercent = computed(() => {
  if (progressTotal.value <= 0) return 0
  return Math.round((progressCurrent.value / progressTotal.value) * 100)
})
</script>

<style scoped>
/* ---- Header ---- */

:deep(ion-toolbar) {
  --min-height: auto;
  --padding-top: 0;
  --padding-bottom: 0;
}

.header-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 14px 6px;
}

.toolbar-start {
  flex: 1;
  padding-bottom: 0;
}

.header-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 10px;
  border: 1px solid rgb(250 156 105 / 0.4);
  border-radius: 999px;
  background: #fff;
  color: #8a6048;
  font-size: 12px;
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.header-btn:active {
  background: #fff0e7;
}

.header-btn.active {
  background: rgb(250 156 105);
  color: #fff;
  border-color: rgb(250 156 105);
}

.header-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.header-btn-label {
  font-size: 12px;
}

/* ---- 源文本下方操作行 ---- */

.source-actions-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px 8px;
  flex-wrap: wrap;
}

.fav-toggle-label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
}

.fav-toggle-text {
  font-size: 12px;
  color: #8a6048;
  font-weight: 600;
}

.fav-toggle {
  position: relative;
  width: 42px;
  height: 24px;
  border: 0;
  border-radius: 999px;
  background: #e0cfc4;
  cursor: pointer;
  transition: background-color 0.2s ease;
  flex-shrink: 0;
}

.fav-toggle:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.fav-toggle.on {
  background: #6dbf87;
}

.fav-toggle-knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 4px rgb(0 0 0 / 0.15);
  transition: transform 0.2s ease;
}

.fav-toggle.on .fav-toggle-knob {
  transform: translateX(18px);
}

.fav-toggle-spinner {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  --color: #8a6048;
  width: 14px;
  height: 14px;
}

.save-list-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 14px;
  border: 1px solid rgb(250 156 105 / 0.5);
  border-radius: 999px;
  background: #fffaf6;
  color: #8a6048;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.save-list-btn:active {
  background: #fff0e7;
}

.save-list-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.save-list-icon {
  font-size: 16px;
  flex-shrink: 0;
}

/* ---- 源文本区域 ---- */

.source-area {
  margin: 0 14px 8px;
  border-radius: 14px;
  background: #fffaf6;
  overflow: hidden;
}

.edit-toolbar {
  display: flex;
  gap: 6px;
  padding: 8px 10px 4px;
}

.edit-input {
  flex: 1;
  min-width: 0;
  height: 28px;
  padding: 0 8px;
  border: 1px solid #e0cfc4;
  border-radius: 8px;
  font-size: 12px;
  color: #4c2a18;
  background: #fff;
  outline: none;
}

.edit-input:focus {
  border-color: #f0a060;
}

.edit-btn {
  flex-shrink: 0;
  height: 28px;
  padding: 0 10px;
  border: 1px solid rgb(250 156 105 / 0.4);
  border-radius: 999px;
  background: #fff;
  color: #8a6048;
  font-size: 11px;
  cursor: pointer;
  white-space: nowrap;
}

.edit-btn:active {
  background: #fff0e7;
}

.edit-btn.apply {
  background: rgb(250 156 105);
  color: #fff;
  border-color: rgb(250 156 105);
}

.source-textarea {
  display: block;
  width: 100%;
  padding: 8px 12px 10px;
  border: 0;
  resize: vertical;
  font-family: 'Courier New', Courier, monospace;
  font-size: 13px;
  line-height: 1.65;
  color: #4c2a18;
  background: transparent;
  outline: none;
  box-sizing: border-box;
}

.source-scroll {
  max-height: 200px;
  overflow-y: auto;
  padding: 10px 14px;
}

.source-line {
  margin: 0;
  padding: 3px 8px;
  border-radius: 4px;
  font-family: 'Courier New', Courier, monospace;
  font-size: 13px;
  line-height: 1.65;
  color: #4c2a18;
  white-space: pre-wrap;
  word-break: break-all;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.source-line:hover {
  background: #fff7f2;
}

.source-line.current-line {
  background: #ffe4d1;
  border-left: 3px solid #fa9c69;
  padding-left: 5px;
}

.source-line.no-results {
  color: #b89a84;
}

.hl-valid {
  background: #fff0e7;
  color: #c96d3a;
  font-weight: 700;
  border-radius: 2px;
}

.hl-invalid {
  background: #fdf0f0;
  color: #c88888;
  text-decoration: line-through;
  border-radius: 2px;
}

.hl-failed {
  background: #ffe8e8;
  color: #d9534f;
  font-weight: 600;
  border-radius: 2px;
}

/* ---- 结果区域 ---- */

.page-shell {
}

@media (min-width: 680px) {
  .page-shell {
    max-width: 1000px;
    margin-right: auto;
    margin-left: auto;
  }
}

/* ---- 收藏状态边框（:deep 穿透到 SearchResultContainer 内部） ---- */

:deep(.fav-online) {
  border: 2px solid #6dbf87;
}

:deep(.fav-offline) {
  border: 2px solid #d4b870;
}

:deep(.fav-both) {
  border: 2px solid #5b9bd5;
}

/* ---- 卡片更多操作按钮 ---- */

:deep(.card-more-btn) {
  position: absolute;
  right: 2px;
  top: 50%;
  transform: translateY(-50%);
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #8a6048;
  font-size: 18px;
  cursor: pointer;
  z-index: 2;
}

:deep(.card-more-btn.active) {
  background: rgb(250 156 105 / 0.15);
  color: #c96d3a;
}

/* ---- 上下文菜单 ---- */

.card-context-menu {
  display: flex;
  flex-direction: column;
  min-width: 140px;
  padding: 6px;
  border-radius: 14px;
  background: #fffaf6;
  box-shadow: 0 12px 36px rgb(76 42 24 / 0.22);
  z-index: 50;
  overflow: hidden;
}

.card-menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 42px;
  padding: 10px 14px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: #3a261d;
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.12s ease;
}

.card-menu-item:active {
  background: #fff0e7;
}

.card-menu-item--danger {
  color: #d4533e;
}

.card-menu-item--danger:active {
  background: #ffe8e8;
}

.card-menu-icon {
  flex-shrink: 0;
  font-size: 17px;
  color: #8a6048;
}

.card-menu-item--danger .card-menu-icon {
  color: #d4533e;
}

/* ---- 进度弹窗 ---- */

.progress-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgb(16 12 10 / 0.32);
  backdrop-filter: blur(2px);
}

.progress-dialog {
  width: min(320px, 80vw);
  padding: 24px 22px 20px;
  border-radius: 20px;
  background: #fffaf6;
  box-shadow: 0 16px 40px rgb(76 42 24 / 0.24);
  text-align: center;
}

.progress-title {
  margin-bottom: 16px;
  font-size: 15px;
  font-weight: 700;
  color: #3a261d;
}

.progress-bar {
  height: 8px;
  border-radius: 999px;
  background: #f0ddd2;
  overflow: hidden;
  margin-bottom: 8px;
}

.progress-fill {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #fa9c69, #f0a060);
  transition: width 0.25s ease;
}

.progress-percent {
  margin-bottom: 8px;
  font-size: 22px;
  font-weight: 700;
  color: #fa9c69;
}

.progress-count {
  margin-bottom: 6px;
  font-size: 12px;
  color: #785947;
}

.progress-file {
  margin-bottom: 10px;
  font-size: 11px;
  color: #9e7d6a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.progress-warning {
  font-size: 11px;
  color: #c9886a;
  font-weight: 600;
}
</style>
