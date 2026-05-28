<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <div class="toolbar-start">
          <MenuToggleButton/>
        </div>
        <div class="tab-bar">
          <button
            type="button"
            class="tab-btn"
            :class="{ active: activeTab === 'browse' }"
            @click="switchTab('browse')"
          >
            浏览历史
          </button>
          <button
            type="button"
            class="tab-btn"
            :class="{ active: activeTab === 'parse' }"
            @click="switchTab('parse')"
          >
            解析历史
          </button>
        </div>
      </IonToolbar>
    </IonHeader>
    <IonContent ref="contentRef" :scroll-events="true" @ion-scroll="onScroll">
      <div class="page-shell">
        <div v-if="activeTab === 'browse'" class="tab-content">
          <div v-if="browseItems.length === 0" class="empty-state">
            <IonIcon :icon="timeOutline" class="empty-icon"/>
            <p>暂无浏览记录</p>
            <p class="empty-hint">开始浏览本子，记录将自动出现在这里</p>
          </div>
          <template v-else>
            <div class="section-header">
              <span class="section-title">共 {{ browseItems.length }} 条记录</span>
              <button class="clear-btn" @click="confirmClearBrowse">清空</button>
            </div>
            <TransitionGroup name="history-list" tag="div" class="card-list">
              <template v-for="group in browseGroups" :key="group.label">
                <div class="date-group-header">{{ group.label }}</div>
                <div
                  v-for="item in group.items"
                  :key="item.timestamp"
                  class="browse-card"
                  @click="openAlbum(item)"
                >
                  <div class="card-cover-wrap">
                    <img :src="item.coverUrl" class="card-cover" alt="" loading="lazy"/>
                  </div>
                  <div class="card-body">
                    <h3 class="card-title">{{ item.albumTitle }}</h3>
                    <div class="card-id">ID: {{ item.albumId }}</div>
                    <div class="card-meta">作者：{{ item.authors }}</div>
                    <div class="card-meta">{{ formatRelativeTime(item.timestamp) }}</div>
                    <div v-if="item.chapterTitle" class="card-chapter">
                      <IonIcon :icon="bookOutline" class="chapter-icon"/>
                      <span>{{ item.chapterTitle }}</span>
                    </div>
                  </div>
                  <button
                    type="button"
                    class="card-more-btn"
                    :class="{ active: contextMenu?.item.id === item.id }"
                    aria-label="更多操作"
                    @click.stop="openContextMenu(item, $event)"
                  >
                    <IonIcon :icon="ellipsisVertical"/>
                  </button>
                </div>
              </template>
            </TransitionGroup>
          </template>
        </div>

        <div v-else class="tab-content">
          <div v-if="parseItems.length === 0" class="empty-state">
            <IonIcon :icon="documentTextOutline" class="empty-icon"/>
            <p>暂无解析记录</p>
            <p class="empty-hint">解析搜索关键词后，记录会显示在这里</p>
          </div>
          <template v-else>
            <div class="section-header">
              <span class="section-title">共 {{ parseItems.length }} 条记录</span>
              <button class="clear-btn" @click="confirmClearParse">清空</button>
            </div>
            <TransitionGroup name="history-list" tag="div" class="card-list">
              <div v-for="item in parseItems" :key="item.timestamp" class="parse-card" @click="openParseItem(item)">
                <div class="parse-icon-wrap">
                  <IonIcon :icon="documentTextOutline"/>
                </div>
                <div class="parse-body">
                  <div class="parse-text">{{ item.text }}</div>
                  <div class="parse-meta">
                    <span class="parse-mode-badge" :class="item.mode === 'batch-mode' ? 'mode-batch' : 'mode-single'">
                      {{ item.mode === 'batch-mode' ? '批量解析' : '单个解析' }}
                    </span>
                    <span class="parse-time">{{ formatRelativeTime(item.timestamp) }}</span>
                  </div>
                </div>
                <button
                  type="button"
                  class="card-more-btn"
                  :class="{ active: contextMenu?.item.id === item.id }"
                  aria-label="更多操作"
                  @click.stop="openContextMenu(item, $event)"
                >
                  <IonIcon :icon="ellipsisVertical"/>
                </button>
              </div>
            </TransitionGroup>
          </template>
        </div>
      </div>
    </IonContent>
    <!-- 上下文菜单 -->
    <div
      v-if="contextMenu"
      ref="contextMenuRef"
      class="context-menu"
      :style="contextMenuStyle"
      @mousedown.prevent.stop
      @touchstart.stop
    >
      <template v-if="contextMenu.type === 'browse'">
        <button type="button" class="context-menu-item" @click.stop="handleMenuDetail">
          <IonIcon :icon="bookOutline" class="context-menu-icon"/>
          <span>进入详情页</span>
        </button>
        <button
          type="button"
          class="context-menu-item context-menu-item--danger"
          @click.stop="handleMenuDelete"
        >
          <IonIcon :icon="trashOutline" class="context-menu-icon"/>
          <span>删除此记录</span>
        </button>
      </template>
      <template v-else>
        <button type="button" class="context-menu-item" @click.stop="handleMenuCopy">
          <IonIcon :icon="copyOutline" class="context-menu-icon"/>
          <span>复制文本</span>
        </button>
        <button
          type="button"
          class="context-menu-item context-menu-item--danger"
          @click.stop="handleMenuDelete"
        >
          <IonIcon :icon="trashOutline" class="context-menu-icon"/>
          <span>删除此记录</span>
        </button>
      </template>
    </div>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({name: 'HistoryPage'})

import {computed, onBeforeUnmount, onMounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import {alertController, IonContent, IonIcon, IonPage} from '@ionic/vue'
import {bookOutline, copyOutline, documentTextOutline, ellipsisVertical, trashOutline, timeOutline} from 'ionicons/icons'
import {HistoryService} from '@/services/HistoryService'
import type {BrowseHistoryItem, ParseHistoryItem} from '@/services/JmcomicTypes'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'

const PAGE_SIZE = 50
const router = useRouter()
const contentRef = ref<InstanceType<typeof IonContent>>()
const scrollEl = ref<HTMLElement | null>(null)
const activeTab = ref<'browse' | 'parse'>('browse')
const browseItems = ref<BrowseHistoryItem[]>([])
const parseItems = ref<ParseHistoryItem[]>([])
const browseHasMore = ref(true)
const parseHasMore = ref(true)
const isLoadingMore = ref(false)

interface BrowseGroup {
  label: string
  items: BrowseHistoryItem[]
}

const browseGroups = computed<BrowseGroup[]>(() => {
  const now = new Date()
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const yesterdayStart = todayStart - 86_400_000
  const dayOfWeek = now.getDay() || 7
  const weekStart = todayStart - (dayOfWeek - 1) * 86_400_000

  const groups: Record<string, BrowseHistoryItem[]> = {
    今天: [],
    昨天: [],
    本周: [],
    更早: [],
  }

  for (const item of browseItems.value) {
    if (item.timestamp >= todayStart) {
      groups['今天'].push(item)
    } else if (item.timestamp >= yesterdayStart) {
      groups['昨天'].push(item)
    } else if (item.timestamp >= weekStart) {
      groups['本周'].push(item)
    } else {
      groups['更早'].push(item)
    }
  }

  return (Object.entries(groups) as [string, BrowseHistoryItem[]][])
    .filter(([, items]) => items.length > 0)
    .map(([label, items]) => ({label, items}))
})

async function loadBrowse() {
  browseItems.value = await HistoryService.getBrowseHistory(PAGE_SIZE, 0)
  browseHasMore.value = browseItems.value.length === PAGE_SIZE
}

async function loadMoreBrowse() {
  if (isLoadingMore.value || !browseHasMore.value) return
  isLoadingMore.value = true
  const batch = await HistoryService.getBrowseHistory(PAGE_SIZE, browseItems.value.length)
  if (batch.length > 0) browseItems.value.push(...batch)
  browseHasMore.value = batch.length === PAGE_SIZE
  isLoadingMore.value = false
}

async function loadParse() {
  parseItems.value = await HistoryService.getParseHistory(PAGE_SIZE, 0)
  parseHasMore.value = parseItems.value.length === PAGE_SIZE
}

async function loadMoreParse() {
  if (isLoadingMore.value || !parseHasMore.value) return
  isLoadingMore.value = true
  const batch = await HistoryService.getParseHistory(PAGE_SIZE, parseItems.value.length)
  if (batch.length > 0) parseItems.value.push(...batch)
  parseHasMore.value = batch.length === PAGE_SIZE
  isLoadingMore.value = false
}

const onScroll = () => {
  const el = scrollEl.value
  if (!el) return
  const threshold = 200
  if (el.scrollHeight - el.scrollTop - el.clientHeight < threshold) {
    if (activeTab.value === 'browse') loadMoreBrowse()
    else loadMoreParse()
  }
}

async function switchTab(tab: 'browse' | 'parse') {
  activeTab.value = tab
  if (tab === 'browse') await loadBrowse()
  else await loadParse()
}

onMounted(async () => {
  const contentEl = contentRef.value?.$el
  if (contentEl && typeof (contentEl as any).getScrollElement === 'function') {
    scrollEl.value = (await (contentEl as any).getScrollElement()) as HTMLElement
  }
  await loadBrowse()
})

function openAlbum(item: BrowseHistoryItem) {
  const authorsParam = item.authors.replace(/\s*\/\s*/g, ',')
  void router.push({
    path: `/album/${item.albumId}`,
    query: {title: item.albumTitle, coverUrl: item.coverUrl, authors: authorsParam},
  })
}

function openParseItem(item: ParseHistoryItem) {
  if (item.mode === 'batch-mode') {
    sessionStorage.setItem('batch-parse-text', item.text)
    void router.push({path: '/batch-parse'})
  } else {
    const digits = item.text.replace(/\D/g, '')
    void router.push({path: '/search', query: {keyword: digits}})
  }
}

// ---- 上下文菜单 ----

interface ContextMenuState {
  type: 'browse' | 'parse'
  item: BrowseHistoryItem | ParseHistoryItem
  x: number
  y: number
}

const contextMenu = ref<ContextMenuState | null>(null)
const contextMenuRef = ref<HTMLElement | null>(null)

const contextMenuStyle = computed(() => {
  const m = contextMenu.value
  if (!m) return {}
  const menuH = m.type === 'browse' ? 90 : 90
  const top = m.y + menuH > window.innerHeight ? m.y - menuH - 8 : m.y
  return {
    position: 'fixed' as const,
    top: `${top}px`,
    left: `${Math.min(m.x, window.innerWidth - 160)}px`,
  }
})

function openContextMenu(item: BrowseHistoryItem | ParseHistoryItem, event: MouseEvent) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  const type = 'albumId' in item ? 'browse' : 'parse'
  contextMenu.value = {type, item, x: rect.left, y: rect.bottom + 4}
  setTimeout(() => {
    document.addEventListener('mousedown', handleContextMenuClickOutside)
    document.addEventListener('touchstart', handleContextMenuClickOutside)
  }, 0)
}

function closeContextMenu() {
  contextMenu.value = null
  document.removeEventListener('mousedown', handleContextMenuClickOutside)
  document.removeEventListener('touchstart', handleContextMenuClickOutside)
}

function handleContextMenuClickOutside(e: Event) {
  if (contextMenuRef.value?.contains(e.target as Node)) return
  closeContextMenu()
}

function handleMenuDetail() {
  const item = contextMenu.value?.item as BrowseHistoryItem
  closeContextMenu()
  if (item) openAlbum(item)
}

async function handleMenuCopy() {
  const item = contextMenu.value?.item as ParseHistoryItem
  const text = item?.text
  closeContextMenu()
  if (text) {
    try {
      await navigator.clipboard.writeText(text)
    } catch {
      /* ignore */
    }
  }
}

async function handleMenuDelete() {
  const m = contextMenu.value
  if (!m) return
  closeContextMenu()

  const isBrowse = m.type === 'browse'
  const alert = await alertController.create({
    header: '确认删除',
    message: isBrowse ? '确定要删除这条浏览记录吗？' : '确定要删除这条解析记录吗？',
    buttons: [
      {text: '取消', role: 'cancel'},
      {
        text: '删除',
        role: 'destructive',
        handler: async () => {
          if (isBrowse) {
            await HistoryService.deleteBrowseItem(m.item.id)
            browseItems.value = browseItems.value.filter(i => i.id !== m.item.id)
          } else {
            await HistoryService.deleteParseItem(m.item.id)
            parseItems.value = parseItems.value.filter(i => i.id !== m.item.id)
          }
        },
      },
    ],
  })
  await alert.present()
}

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', handleContextMenuClickOutside)
  document.removeEventListener('touchstart', handleContextMenuClickOutside)
})

async function confirmClearBrowse() {
  const alert = await alertController.create({
    header: '确认清空',
    message: '确定要清空所有浏览记录吗？此操作不可撤销。',
    buttons: [
      {text: '取消', role: 'cancel'},
      {
        text: '清空',
        role: 'destructive',
        handler: async () => {
          await HistoryService.clearBrowseHistory()
          browseItems.value = []
        },
      },
    ],
  })
  await alert.present()
}

async function confirmClearParse() {
  const alert = await alertController.create({
    header: '确认清空',
    message: '确定要清空所有解析记录吗？此操作不可撤销。',
    buttons: [
      {text: '取消', role: 'cancel'},
      {
        text: '清空',
        role: 'destructive',
        handler: async () => {
          await HistoryService.clearParseHistory()
          parseItems.value = []
        },
      },
    ],
  })
  await alert.present()
}

function formatRelativeTime(timestamp: number): string {
  const diff = Date.now() - timestamp
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}小时前`
  if (diff < 604_800_000) return `${Math.floor(diff / 86_400_000)}天前`
  const d = new Date(timestamp)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
</script>

<style scoped>
.toolbar-start {
  padding: 0 0 8px 14px;
}

/* 页面容器 */
.page-shell {
  margin: 0 14px 86px;
}

/* Tab 栏 */
.tab-bar {
  display: flex;
  gap: 2px;
  margin-bottom: 10px;
  padding: 4px 14px;
  border-radius: 18px;
  background: #fffbf8;
}

.tab-btn {
  flex: 1;
  height: 34px;
  border: 0;
  border-radius: 14px;
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

/* Empty state */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding-top: 36vh;
  color: #b89a84;
}

.empty-icon {
  font-size: 56px;
  margin-bottom: 12px;
}

.empty-state p {
  margin: 0;
  font-size: 14px;
}

.empty-hint {
  margin-top: 6px !important;
  font-size: 12px !important;
  color: #c4a494;
}

/* Section header */
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 4px 10px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #8a6048;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.clear-btn {
  font-size: 12px;
  border: 0;
  background: transparent;
  color: #d9534f;
  cursor: pointer;
  padding: 2px 8px;
  border-radius: 4px;
}

.clear-btn:active {
  background: #ffeaea;
}

/* Date group header */
.date-group-header {
  padding: 16px 4px 8px;
  font-size: 13px;
  font-weight: 600;
  color: #8a6048;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

/* Card list */
.card-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

/* Browse card */
.browse-card {
  position: relative;
  display: flex;
  align-items: stretch;
  gap: 0;
  height: 108px;
  padding: 0 10px 0 0;
  background: #fffaf6;
  border-radius: 12px;
  box-shadow: 5px 12px 28px rgb(76 42 24 / 0.2);
  cursor: pointer;
  transition: transform 0.16s ease;
}

.browse-card:active {
  transform: scale(0.98);
}

.card-cover-wrap {
  flex-shrink: 0;
  aspect-ratio: 3 / 4;
  height: 100%;
  overflow: hidden;
  border-radius: 6px;
  background: linear-gradient(145deg, #f3ded0, #ffece0);
}

.card-cover {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
}

.card-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: start;
  gap: 4px;
  padding: 0 2px 0 10px;
  overflow: hidden;
}

.card-title {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: #30201a;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.4;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.card-id {
  color: #876653;
  font-size: 11px;
  line-height: 1.35;
}

.card-meta {
  color: #876653;
  font-size: 11px;
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-chapter {
  display: flex;
  align-items: center;
  gap: 3px;
  padding: 2px 6px;
  background: #fff3ea;
  border-radius: 4px;
  font-size: 10px;
  color: #c96d3a;
  align-self: flex-start;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chapter-icon {
  font-size: 11px;
  flex-shrink: 0;
}

/* Parse card */
.parse-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 12px;
  background: #fffaf6;
  border-radius: 12px;
  box-shadow: 5px 12px 28px rgb(76 42 24 / 0.2);
  cursor: pointer;
  transition: transform 0.16s ease;
}

.parse-card:active {
  transform: scale(0.98);
}

.parse-icon-wrap {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: #fff3ea;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c96d3a;
  font-size: 18px;
}

.parse-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.parse-text {
  font-size: 13px;
  color: #30201a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.parse-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

.parse-mode-badge {
  font-size: 9px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
  line-height: 1.6;
}

.mode-single {
  background: #e8f0fe;
  color: #4a7fbd;
}

.mode-batch {
  background: #fef3e0;
  color: #c9822e;
}

.parse-time {
  font-size: 10px;
  color: #b8a090;
}

/* More button */
.card-more-btn {
  position: absolute;
  right: 2px;
  top: 50%;
  transform: translateY(-50%);
  width: 28px;
  height: 28px;
  border: 0;
  background: transparent;
  color: #8a6048;
  font-size: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  cursor: pointer;
  flex-shrink: 0;
}

.card-more-btn:active,
.card-more-btn.active {
  background: #f5d2bc;
}

/* Context menu */
.context-menu {
  min-width: 148px;
  background: #fffbf8;
  border: 1px solid rgb(250 156 105 / 0.3);
  border-radius: 14px;
  box-shadow: 0 8px 22px rgb(76 42 24 / 0.14);
  padding: 6px 0;
  z-index: 999;
}

.context-menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 44px;
  padding: 0 16px;
  border: 0;
  background: transparent;
  color: #30201a;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background-color 0.12s ease;
}

.context-menu-item:active {
  background: #fff0e7;
}

.context-menu-item--danger {
  color: #d4533e;
}

.context-menu-icon {
  font-size: 16px;
  flex-shrink: 0;
  color: inherit;
}

/* TransitionGroup */
.history-list-enter-active,
.history-list-leave-active {
  transition: opacity 0.28s ease,
  transform 0.28s ease;
}

.history-list-enter-from {
  opacity: 0;
  transform: translateY(12px) scale(0.97);
}

.history-list-leave-to {
  opacity: 0;
  transform: translateX(-20px);
}

.history-list-move {
  transition: transform 0.28s ease;
}
</style>
