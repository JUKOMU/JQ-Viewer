<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <div class="toolbar-start">
          <MenuToggleButton />
        </div>
        <IonTitle>历史</IonTitle>
      </IonToolbar>
    </IonHeader>
    <IonContent ref="contentRef" :scroll-events="true" @ionScroll="onScroll">
      <!-- Tab 栏 -->
      <div class="tab-bar">
        <button type="button" class="tab-btn" :class="{ active: activeTab === 'browse' }"
                @click="switchTab('browse')">浏览历史</button>
        <button type="button" class="tab-btn" :class="{ active: activeTab === 'parse' }"
                @click="switchTab('parse')">解析历史</button>
      </div>

      <!-- 浏览历史 -->
      <div v-if="activeTab === 'browse'" class="tab-content">
        <div v-if="browseItems.length === 0" class="empty-state">
          <IonIcon :icon="timeOutline" class="empty-icon" />
          <p>暂无浏览记录</p>
        </div>
        <template v-else>
          <div class="section-header">
            <span>共 {{ browseItems.length }} 条记录</span>
            <button class="clear-btn" @click="confirmClearBrowse">清空</button>
          </div>
          <div v-for="item in browseItems" :key="item.timestamp" class="browse-card"
               @click="openAlbum(item)">
            <img :src="item.coverUrl" class="card-cover" alt="" />
            <div class="card-info">
              <div class="card-title">{{ item.albumTitle }}</div>
              <div class="card-authors">{{ item.authors }}</div>
              <div v-if="item.chapterTitle" class="card-chapter">
                <IonIcon :icon="bookOutline" class="chapter-icon" /><span>{{ item.chapterTitle }}</span>
              </div>
              <div class="card-time">{{ formatRelativeTime(item.timestamp) }}</div>
            </div>
          </div>
        </template>
      </div>

      <!-- 解析历史 -->
      <div v-else class="tab-content">
        <div class="placeholder-banner">
          <IonIcon :icon="informationCircleOutline" class="banner-icon" />
          <span>解析功能尚未完成，此处为占位信息</span>
        </div>
        <div v-if="parseItems.length === 0" class="empty-state">
          <IonIcon :icon="documentTextOutline" class="empty-icon" />
          <p>暂无解析记录</p>
        </div>
        <template v-else>
          <div class="section-header">
            <span>共 {{ parseItems.length }} 条记录</span>
            <button class="clear-btn" @click="confirmClearParse">清空</button>
          </div>
          <div v-for="item in parseItems" :key="item.timestamp" class="parse-row">
            <IonIcon :icon="documentTextOutline" class="parse-icon" />
            <span class="parse-text">{{ item.text }}</span>
            <span class="parse-time">{{ formatRelativeTime(item.timestamp) }}</span>
          </div>
        </template>
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { alertController, IonContent, IonHeader, IonIcon, IonPage, IonTitle, IonToolbar } from '@ionic/vue'
import { bookOutline, documentTextOutline, informationCircleOutline, timeOutline } from 'ionicons/icons'
import { HistoryService } from '@/services/HistoryService'
import type { BrowseHistoryItem, ParseHistoryItem } from '@/services/JmcomicTypes'
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

/** 初次/切换 tab 加载浏览历史 */
async function loadBrowse() {
  browseItems.value = await HistoryService.getBrowseHistory(PAGE_SIZE, 0)
  browseHasMore.value = browseItems.value.length === PAGE_SIZE
}

/** 加载更多浏览历史 */
async function loadMoreBrowse() {
  if (isLoadingMore.value || !browseHasMore.value) return
  isLoadingMore.value = true
  const batch = await HistoryService.getBrowseHistory(PAGE_SIZE, browseItems.value.length)
  if (batch.length > 0) browseItems.value.push(...batch)
  browseHasMore.value = batch.length === PAGE_SIZE
  isLoadingMore.value = false
}

/** 初次/切换 tab 加载解析历史 */
async function loadParse() {
  parseItems.value = await HistoryService.getParseHistory(PAGE_SIZE, 0)
  parseHasMore.value = parseItems.value.length === PAGE_SIZE
}

/** 加载更多解析历史 */
async function loadMoreParse() {
  if (isLoadingMore.value || !parseHasMore.value) return
  isLoadingMore.value = true
  const batch = await HistoryService.getParseHistory(PAGE_SIZE, parseItems.value.length)
  if (batch.length > 0) parseItems.value.push(...batch)
  parseHasMore.value = batch.length === PAGE_SIZE
  isLoadingMore.value = false
}

/** 滚动触底时自动加载更多 */
const onScroll = () => {
  const el = scrollEl.value
  if (!el) return
  const threshold = 200
  if (el.scrollHeight - el.scrollTop - el.clientHeight < threshold) {
    if (activeTab.value === 'browse') loadMoreBrowse()
    else loadMoreParse()
  }
}

// 切换 tab 时重新加载
async function switchTab(tab: 'browse' | 'parse') {
  activeTab.value = tab
  if (tab === 'browse') await loadBrowse()
  else await loadParse()
}

onMounted(async () => {
  const contentEl = contentRef.value?.$el
  if (contentEl && typeof (contentEl as any).getScrollElement === 'function') {
    scrollEl.value = await (contentEl as any).getScrollElement() as HTMLElement
  }
  await loadBrowse()
})

function openAlbum(item: BrowseHistoryItem) {
  const authorsParam = item.authors.replace(/\s*\/\s*/g, ',')
  void router.push({
    path: `/album/${item.albumId}`,
    query: { title: item.albumTitle, coverUrl: item.coverUrl, authors: authorsParam },
  })
}

async function confirmClearBrowse() {
  const alert = await alertController.create({
    header: '确认清空',
    message: '确定要清空所有浏览记录吗？此操作不可撤销。',
    buttons: [
      { text: '取消', role: 'cancel' },
      { text: '清空', role: 'destructive', handler: async () => {
        await HistoryService.clearBrowseHistory()
        browseItems.value = []
      }},
    ],
  })
  await alert.present()
}

async function confirmClearParse() {
  const alert = await alertController.create({
    header: '确认清空',
    message: '确定要清空所有解析记录吗？此操作不可撤销。',
    buttons: [
      { text: '取消', role: 'cancel' },
      { text: '清空', role: 'destructive', handler: async () => {
        await HistoryService.clearParseHistory()
        parseItems.value = []
      }},
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
.tab-bar {
  display: flex;
  gap: 2px;
  padding: 8px 12px;
  background: #fffaf6;
  border-bottom: 1px solid rgb(245 210 188 / 0.5);
  z-index: 10;
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

.tab-content {
  padding: 12px 14px;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 60px 20px;
  color: #9e7d6a;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 12px;
  opacity: 0.4;
}

.empty-state p {
  margin: 0;
  font-size: 13px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 4px 10px;
  font-size: 12px;
  color: #8a6048;
}

.clear-btn {
  border: 0;
  background: transparent;
  color: rgb(237 123 62);
  font-size: 12px;
}

.browse-card {
  display: flex;
  gap: 12px;
  padding: 10px 12px;
  margin-bottom: 8px;
  background: #fff;
  border: 1px solid rgb(245 210 188 / 0.5);
  border-radius: 14px;
  box-shadow: 0 4px 12px rgb(115 67 38 / 0.06);
  cursor: pointer;
}

.browse-card:active {
  background: rgb(255 245 238);
}

.card-cover {
  width: 60px;
  height: 80px;
  border-radius: 8px;
  object-fit: cover;
  flex-shrink: 0;
  background: #f0e4da;
}

.card-info {
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 3px;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: #3a261d;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-authors {
  font-size: 11px;
  color: #9a725b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-chapter {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: #c96d3a;
}

.chapter-icon {
  font-size: 12px;
}

.card-time {
  font-size: 10px;
  color: #b8a090;
}

.placeholder-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  margin-bottom: 14px;
  background: #fff8e7;
  border: 1px solid rgb(240 200 120 / 0.5);
  border-radius: 10px;
  font-size: 12px;
  color: #8a6d3b;
}

.banner-icon {
  font-size: 18px;
  flex-shrink: 0;
}

.parse-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-bottom: 1px solid rgb(245 210 188 / 0.3);
}

.parse-icon {
  font-size: 16px;
  color: #c96d3a;
  flex-shrink: 0;
}

.parse-text {
  flex: 1;
  font-size: 13px;
  color: #3a261d;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.parse-time {
  font-size: 11px;
  color: #b8a090;
  flex-shrink: 0;
}
</style>
