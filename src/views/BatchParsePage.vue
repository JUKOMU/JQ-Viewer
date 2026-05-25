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
          <button
            type="button"
            class="header-btn"
            :class="{ active: editing }"
            @click="toggleEdit"
          >
            <IonIcon :icon="createOutline"/>
          </button>
        </div>

        <div v-show="sourceExpanded" class="source-area">
          <!-- 编辑模式 -->
          <template v-if="editing">
            <div class="edit-toolbar">
              <input
                v-model="findText"
                class="edit-input"
                placeholder="查找"
                enterkeyhint="done"
              />
              <input
                v-model="replaceText"
                class="edit-input"
                placeholder="替换为"
                enterkeyhint="done"
              />
              <button type="button" class="edit-btn" @click="replaceAll">全部替换</button>
              <button type="button" class="edit-btn apply" @click="applyEdit">应用修改</button>
            </div>
            <textarea
              v-model="editText"
              class="source-textarea"
              rows="5"
              spellcheck="false"
            />
          </template>

          <!-- 查看模式 -->
          <div ref="sourceScrollRef" v-else class="source-scroll">
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
      </IonToolbar>
    </IonHeader>

    <IonContent ref="contentRef" :scroll-events="true" @ionScroll="onContentScroll">
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
            idle-text=""
            empty-text="未找到对应本子"
            @item-click="handleItemClick"
            @mode-change="displayMode = $event"
        />
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { IonContent, IonHeader, IonIcon, IonPage, IonToolbar } from '@ionic/vue'
import { chevronDownOutline, chevronUpOutline, createOutline } from 'ionicons/icons'
import type { ScrollCustomEvent } from '@ionic/core'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import SearchResultContainer from '@/components/search/SearchResultContainer.vue'
import type { SearchResultDisplayItem } from '@/components/search/SearchResultContainer.vue'
import { JmcomicService } from '@/services/JmcomicService'
import type { AlbumDetail, SearchResult, SearchResultItem } from '@/services/JmcomicTypes'
import { parseIdsFromText } from '@/utils/batchParse'
import type { InvalidIdItem, ParsedIdItem } from '@/utils/batchParse'

defineOptions({ name: 'BatchParsePage' })

const SESSION_KEY = 'batch-parse-text'

interface LineSegment {
  text: string
  type: 'normal' | 'valid-id' | 'invalid-id' | 'failed-id'
}

interface SourceLine {
  segments: LineSegment[]
  hasOnlyFailed: boolean
}

const router = useRouter()
const contentRef = ref<InstanceType<typeof IonContent> | null>(null)
const resultContainerRef = ref<InstanceType<typeof SearchResultContainer> | null>(null)
const sourceScrollRef = ref<HTMLElement | null>(null)

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

async function applyEdit() {
  originalText.value = editText.value
  editing.value = false
  await doParse()
}

// ---- 源文本渲染 ----

const sourceLines = computed<SourceLine[]>(() => {
  const lines = originalText.value.split('\n')
  const result: SourceLine[] = lines.map(text => ({
    segments: [{ text, type: 'normal' as const }],
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
    const hasValid = line.segments.some(s => s.type === 'valid-id')
    const hasFailed = line.segments.some(s => s.type === 'failed-id' || s.type === 'invalid-id')
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
        out.push({ text: seg.text.slice(0, targetStart - cursor), type: seg.type })
      }
      const overlapStart = Math.max(cursor, targetStart)
      const overlapEnd = Math.min(segEnd, targetEnd)
      if (overlapEnd > overlapStart) {
        out.push({ text: seg.text.slice(overlapStart - cursor, overlapEnd - cursor), type })
      }
      if (segEnd > targetEnd) {
        out.push({ text: seg.text.slice(targetEnd - cursor), type: seg.type })
      }
    }
    cursor = segEnd
  }

  return out
}

function segClass(type: LineSegment['type']): string {
  switch (type) {
    case 'valid-id': return 'hl-valid'
    case 'invalid-id': return 'hl-invalid'
    case 'failed-id': return 'hl-failed'
    default: return ''
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
    .map((a, i) => (a ? { item: albumToSearchItem(a), page: 1, indexInPage: i } : null))
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
      // 每次完成一个就更新响应式数组，触发渐进渲染
      albumResults.value = [...results]
      failedIds.value = { ...failed }
    }
  }

  const workers = Array.from({ length: Math.min(concurrency, total) }, () => worker())
  await Promise.all(workers)

  loading.value = false

  await nextTick()
  updateHighlightFromScroll()
}

onMounted(async () => {
  const text = sessionStorage.getItem(SESSION_KEY)

  if (!text || !text.trim()) {
    errorMessage.value = '未接收到解析文本'
    loading.value = false
    return
  }

  originalText.value = text
  await doParse()
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
  // 从下往上找，优先选择靠近视口底部的可见卡片
  for (let i = cards.length - 1; i >= 0; i--) {
    const rect = cards[i].getBoundingClientRect()
    if (rect.top < window.innerHeight && rect.bottom > 0) {
      bestIdx = i
      break
    }
  }
  // 回退：所有卡片都在视口下方时选最后一个已滚过的
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
  const idx = parsedItems.value.findIndex(p => p.lineIndex === lineIndex)
  if (idx < 0) return

  const entryKey = `1-${idx}-${parsedItems.value[idx].id}`
  const el = resultContainerRef.value?.getEntryElement(entryKey)
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    currentHighlightLine.value = lineIndex
  }
}

// 源文本滚动同步：结果滚动时源文本自动滚动到对应行
watch(currentHighlightLine, async (lineIndex) => {
  if (lineIndex < 0 || !sourceScrollRef.value) return
  await nextTick()
  const el = sourceScrollRef.value.querySelector(`.source-line:nth-child(${lineIndex + 1})`)
  if (el) {
    el.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }
})

// ---- 生命周期 ----

onBeforeUnmount(() => {
  if (scrollTimer) clearTimeout(scrollTimer)
})

function handleItemClick(item: SearchResultItem) {
  router.push(`/album/${item.id}`)
}
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

.header-btn-label {
  font-size: 12px;
}

/* ---- 源文本区域 ---- */

.source-area {
  margin: 0 14px 8px;
  border-radius: 14px;
  background: #fffaf6;
  overflow: hidden;
}

/* 编辑工具栏 */
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

/* 编辑 textarea */
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

/* 查看模式滚动区 */
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
</style>
