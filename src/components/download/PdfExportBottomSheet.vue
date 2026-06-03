<template>
  <Teleport to="body">
    <div v-if="modelValue" class="sheet-backdrop" @click.self="close">
      <div class="sheet-panel">
        <!-- 头部 -->
        <div class="sheet-header">
          <span class="sheet-title">导出为PDF</span>
          <button class="sheet-close" @click="close">&times;</button>
        </div>

        <div class="sheet-body">
          <!-- 导出设置（可展开） -->
          <div class="section">
            <button class="expand-header" @click="showSettings = !showSettings">
              <span class="expand-arrow" :class="{ open: showSettings }">&#9654;</span>
              <span class="section-label">导出设置</span>
            </button>
            <div v-if="showSettings" class="settings-panel">
              <div class="setting-item">
                <span class="setting-label">导出位置</span>
                <div class="setting-row">
                  <input
                    class="text-input-sm"
                    type="text"
                    :value="exportPath"
                    @change="onExportPathChange"
                  />
                  <button class="mini-btn" @click="onBrowseFolder">浏览</button>
                </div>
              </div>
              <div class="setting-item">
                <span class="setting-label">目录模板</span>
                <input
                  class="text-input-sm full"
                  type="text"
                  :value="dirTemplate"
                  @change="onDirTemplateChange"
                />
              </div>
              <div class="setting-item">
                <span class="setting-label">名称模板</span>
                <input
                  class="text-input-sm full"
                  type="text"
                  :value="nameTemplate"
                  @change="onNameTemplateChange"
                />
              </div>
            </div>
          </div>

          <!-- 章节选择（多章节时显示） -->
          <div v-if="chapters.length > 1" class="section">
            <div class="section-header">
              <span class="section-label">选择章节（{{ selectedIds.size }}/{{ chapters.length }}）</span>
              <button class="toggle-all-btn" @click="toggleAll">
                {{ selectedIds.size === chapters.length ? '取消全选' : '全选' }}
              </button>
            </div>
            <div class="chapter-list">
              <label
                v-for="ch in chapters"
                :key="ch.taskId"
                class="chapter-row"
              >
                <input
                  type="checkbox"
                  class="chapter-check"
                  :checked="selectedIds.has(ch.taskId)"
                  @change="toggleChapter(ch.taskId)"
                />
                <span class="chapter-info">
                  <span class="chapter-name">{{ ch.chapterId }}</span>
                  <span class="chapter-meta">{{ chapterOrderLabel(ch) }} · {{ ch.totalPages }}页</span>
                </span>
              </label>
            </div>
          </div>

          <!-- 保存路径（可编辑） -->
          <div class="section">
            <span class="section-label">保存路径</span>
            <textarea
              class="path-input"
              :value="editedPath"
              rows="4"
              @input="onPathInput"
            />
            <div class="template-tags">
              <span class="tag-hint">点击复制变量值：</span>
              <button
                v-for="v in templateVars"
                :key="v"
                class="tag-btn"
                @click="copyTag(v)"
              >{{ v }}</button>
            </div>
            <div class="template-tags" style="margin-top: 2px">
              <span class="tag-hint">条件语法（点击复制）：</span>
              <button
                v-for="v in tagConditionVars"
                :key="v"
                class="tag-btn tag-cond"
                @click="copyTagSyntax(v)"
              >{{ v }}</button>
            </div>
          </div>

          <!-- 分卷导出 -->
          <div class="section">
            <div class="section-header">
              <span class="section-label">分卷导出</span>
              <IonToggle
                :checked="splitEnabled"
                color="warning"
                @ion-change="splitEnabled = $event.detail.checked"
              />
            </div>
            <div v-if="splitEnabled" class="split-row">
              <span class="split-hint">每卷最多</span>
              <input
                class="num-input-sm"
                type="number"
                :value="splitPages"
                min="10"
                max="999"
                step="10"
                @change="onSplitPagesChange"
              />
              <span class="split-hint">页</span>
            </div>
          </div>

          <!-- 图片质量 -->
          <div class="section">
            <span class="section-label">图片质量</span>
            <div class="quality-row">
              <label class="radio-label" :class="{ active: useOriginal }">
                <input
                  type="radio"
                  name="quality"
                  :checked="useOriginal"
                  @change="useOriginal = true"
                />
                <span>原图</span>
              </label>
              <label class="radio-label" :class="{ active: !useOriginal }">
                <input
                  type="radio"
                  name="quality"
                  :checked="!useOriginal"
                  @change="useOriginal = false"
                />
                <span>压缩</span>
              </label>
            </div>
          </div>

          <!-- 压缩比（选中压缩时显示） -->
          <div v-if="!useOriginal" class="section">
            <span class="section-label">压缩比: {{ compressionRatio.toFixed(2) }}</span>
            <IonRange
              class="ratio-slider"
              :min="0.1"
              :max="1.0"
              :step="0.05"
              :value="compressionRatio"
              color="warning"
              @ion-change="onRatioChange"
            />
            <div class="ratio-hint">图片长宽缩放至原始的 {{ (compressionRatio * 100).toFixed(0) }}%</div>
          </div>
        </div>

        <!-- 底部按钮 -->
        <div class="sheet-footer">
          <button class="btn-cancel" @click="close">取消</button>
          <button
            class="btn-confirm"
            :disabled="selectedIds.size === 0"
            @click="onConfirm"
          >
            导出{{ selectedIds.size > 0 ? ` (${selectedIds.size})` : '' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { IonRange, IonToggle, useBackButton } from '@ionic/vue'
import type { AlbumDetail, DownloadTask } from '@/services/JmcomicTypes'
import { PdfExportService } from '@/services/PdfExportService'
import { JmcomicService, showToast } from '@/services/JmcomicService'

defineOptions({ name: 'PdfExportBottomSheet' })

const props = defineProps<{
  modelValue: boolean
  chapters: DownloadTask[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: [
    payload: {
      selectedChapters: DownloadTask[]
      useOriginal: boolean
      compressionRatio: number
      editedPath: string
      splitPages: number
    },
  ]
}>()

// ---- 模板变量 ----
const templateVars = ['{id}', '{title}', '{chapterName}', '{chapterTitle}', '{chapterId}', '{pageCount}', '{author}', '{authors}', '{tags}']
const tagConditionVars = ['{tag=标签名}', '{tag=标签A|标签B}', '{tag=标签A&标签B}']

// 当前选中章节的实际值（用于复制）
const firstSelectedChapter = computed(() =>
  props.chapters.find((c) => selectedIds.value.has(c.taskId)) ?? props.chapters[0],
)

const albumDetail = ref<AlbumDetail | null>(null)

function resolveChapterName(ch: DownloadTask, album: AlbumDetail | null): string {
  if (album?.seriesId === '0') return album.title || ch.albumTitle
  const order = ch.chapterSortOrder
  if (order && order > 0) return `第${order}话`
  return ch.chapterTitle || ''
}

function chapterOrderLabel(ch: DownloadTask): string {
  const order = ch.chapterSortOrder
  if (order && order > 0) return `第${order}话`
  return ch.chapterTitle || ''
}

const templateValueMap = computed(() => {
  const ch = firstSelectedChapter.value
  if (!ch) return {} as Record<string, string>
  return {
    '{id}': ch.albumId,
    '{title}': ch.albumTitle,
    '{chapterId}': ch.chapterId,
    '{chapterName}': resolveChapterName(ch, albumDetail.value),
    '{chapterTitle}': ch.chapterTitle || '',
    '{pageCount}': String(ch.totalPages),
    '{author}': albumDetail.value?.authors?.[0] ?? '',
    '{authors}': albumDetail.value?.authors?.join('、') ?? '',
    '{tags}': albumDetail.value?.tags?.join('、') ?? '',
  } as Record<string, string>
})

// ---- 设置（双向绑定到 PdfExportService） ----
const showSettings = ref(false)
const exportPath = ref(PdfExportService.getExportPath())
const dirTemplate = ref(PdfExportService.getDirTemplate())
const nameTemplate = ref(PdfExportService.getNameTemplate())

// ---- 导出选项 ----
const selectedIds = ref(new Set<string>())
const useOriginal = ref(true)
const compressionRatio = ref(0.5)
const editedPath = ref('')
const splitEnabled = ref(false)
const splitPages = ref(100)

// ---- 根据模板 + 当前设置生成路径 ----
const templatePath = computed(() => {
  // 显式引用设置 refs 确保响应式联动
  const base = exportPath.value
  const dirTpl = dirTemplate.value
  const nameTpl = nameTemplate.value

  const ch = firstSelectedChapter.value
  if (!ch) return PdfExportService.previewPath()

  const data = {
    id: ch.albumId,
    title: ch.albumTitle,
    chapterId: ch.chapterId,
    chapterName: resolveChapterName(ch, albumDetail.value),
    chapterTitle: ch.chapterTitle || '',
    pageCount: ch.totalPages,
    author: albumDetail.value?.authors?.[0] ?? '',
    authors: albumDetail.value?.authors?.join('、') ?? '',
    tags: albumDetail.value?.tags ?? [],
  }
  const dirRendered = PdfExportService.renderTemplate(dirTpl, data)
  const nameRendered = PdfExportService.renderTemplate(nameTpl, data)
  const baseTrimmed = base.replace(/\/+$/, '')
  const dirSegments = dirRendered.split('/').map(s => PdfExportService.sanitizeSegment(s)).filter(s => s.length > 0)
  const dirClean = dirSegments.join('/')
  const nameClean = PdfExportService.sanitizeSegment(nameRendered)
  if (dirClean) {
    return `${baseTrimmed}/${dirClean}/${nameClean}.pdf`
  }
  return `${baseTrimmed}/${nameClean}.pdf`
})

// ---- 同步路径预览 ----
watch(
  [templatePath, () => props.modelValue],
  ([tp, open]) => {
    if (open) {
      editedPath.value = tp
    }
  },
)

// ---- 系统返回键关闭面板（优先级 10 > 默认 0，拦截路由返回） ----
useBackButton(10, (processNextHandler) => {
  if (props.modelValue) {
    emit('update:modelValue', false)
  } else {
    processNextHandler()
  }
})

// 面板打开时初始化状态
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      selectedIds.value = new Set(props.chapters.map((c) => c.taskId))
      useOriginal.value = true
      compressionRatio.value = 0.5
      splitEnabled.value = false
      splitPages.value = 100
      showSettings.value = false
      exportPath.value = PdfExportService.getExportPath()
      dirTemplate.value = PdfExportService.getDirTemplate()
      nameTemplate.value = PdfExportService.getNameTemplate()
      // 获取本子详情以支持 author/authors/tag 模板变量
      const albumId = props.chapters[0]?.albumId
      if (albumId) {
        albumDetail.value = null
        JmcomicService.getAlbum(albumId).then(album => {
          albumDetail.value = album
        }).catch(() => {
          albumDetail.value = null
        })
      } else {
        albumDetail.value = null
      }
    }
  },
)

// ---- 设置变更 ----
function onExportPathChange(e: Event) {
  const val = (e.target as HTMLInputElement).value.trim()
  exportPath.value = val
  PdfExportService.setExportPath(val)
}

function onDirTemplateChange(e: Event) {
  const val = (e.target as HTMLInputElement).value.trim()
  dirTemplate.value = val
  PdfExportService.setDirTemplate(val)
}

function onNameTemplateChange(e: Event) {
  const val = (e.target as HTMLInputElement).value.trim()
  nameTemplate.value = val
  PdfExportService.setNameTemplate(val)
}

async function onBrowseFolder() {
  try {
    const result = await JmcomicService.pickFolder()
    if (!result.cancelled && result.path) {
      const path = result.path.endsWith('/') ? result.path : result.path + '/'
      exportPath.value = path
      PdfExportService.setExportPath(path)
    }
  } catch {
    /* ignore */
  }
}

// ---- 路径编辑 ----
function onPathInput(e: Event) {
  editedPath.value = (e.target as HTMLTextAreaElement).value
}

async function copyTag(tag: string) {
  const value = templateValueMap.value[tag] ?? tag
  try {
    await navigator.clipboard.writeText(value)
    await showToast(`已复制 ${tag} → ${value}`, 'success', 1500)
  } catch {
    /* fallback */
  }
}

async function copyTagSyntax(syntax: string) {
  try {
    await navigator.clipboard.writeText(syntax)
    await showToast(`已复制语法: ${syntax}`, 'success', 1500)
  } catch {
    /* fallback */
  }
}

// ---- 章节选择 ----
function toggleChapter(taskId: string) {
  const next = new Set(selectedIds.value)
  if (next.has(taskId)) {
    next.delete(taskId)
  } else {
    next.add(taskId)
  }
  selectedIds.value = next
}

function toggleAll() {
  if (selectedIds.value.size === props.chapters.length) {
    selectedIds.value = new Set()
  } else {
    selectedIds.value = new Set(props.chapters.map((c) => c.taskId))
  }
}

// ---- 分卷 ----
function onSplitPagesChange(e: Event) {
  const val = parseInt((e.target as HTMLInputElement).value, 10)
  if (Number.isFinite(val)) {
    splitPages.value = Math.max(10, Math.min(999, val))
  }
}

// ---- 压缩比 ----
function onRatioChange(e: CustomEvent) {
  compressionRatio.value = Number(e.detail.value)
}

// ---- 操作 ----
function close() {
  emit('update:modelValue', false)
}

async function onConfirm() {
  const selected = props.chapters.filter((c) =>
    selectedIds.value.has(c.taskId),
  )
  if (selected.length === 0) return

  emit('confirm', {
    selectedChapters: selected,
    useOriginal: useOriginal.value,
    compressionRatio: compressionRatio.value,
    editedPath: editedPath.value,
    splitPages: splitEnabled.value ? splitPages.value : 0,
  })
}
</script>

<style scoped>
.sheet-backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgb(16 12 10 / 0.28);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.sheet-panel {
  width: 100%;
  max-width: 480px;
  max-height: min(85vh, 720px);
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #fffaf6 0%, #fff3eb 100%);
  border-radius: 20px 20px 0 0;
  box-shadow: 0 -8px 36px rgb(76 42 24 / 0.16);
  padding-bottom: var(--ion-safe-area-bottom);
  animation: slideUp 0.25s ease-out;
}

@keyframes slideUp {
  from { transform: translateY(100%); }
  to { transform: translateY(0); }
}

/* 头部 */
.sheet-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 18px 10px;
  flex-shrink: 0;
}

.sheet-title {
  font-size: 17px;
  font-weight: 600;
  color: #4c2a18;
}

.sheet-close {
  width: 28px;
  height: 28px;
  border: 0;
  background: transparent;
  font-size: 22px;
  color: #b89a84;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}

.sheet-close:active {
  background: #f0d8c8;
}

/* 正文 */
.sheet-body {
  flex: 1;
  overflow-y: auto;
  padding: 0 18px;
}

.section {
  margin-bottom: 16px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.section-label {
  font-size: 12px;
  font-weight: 600;
  color: #b89a84;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

/* 可展开设置 */
.expand-header {
  display: flex;
  align-items: center;
  gap: 6px;
  border: 0;
  background: transparent;
  cursor: pointer;
  padding: 4px 0;
  width: 100%;
}

.expand-arrow {
  font-size: 10px;
  color: #b89a84;
  transition: transform 0.2s;
}

.expand-arrow.open {
  transform: rotate(90deg);
}

.settings-panel {
  margin-top: 8px;
  padding: 10px 12px;
  background: #fefcf9;
  border: 1px solid #f0d8c8;
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.setting-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.setting-label {
  font-size: 11px;
  font-weight: 600;
  color: #8a6048;
}

.setting-row {
  display: flex;
  gap: 6px;
}

.text-input-sm {
  flex: 1;
  min-width: 0;
  height: 30px;
  border: 1px solid #e0cfc4;
  border-radius: 6px;
  padding: 0 8px;
  font-size: 12px;
  font-family: monospace;
  color: #4c2a18;
  background: #fdfaf8;
  outline: none;
}

.text-input-sm:focus {
  border-color: #f0a060;
}

.text-input-sm.full {
  width: 100%;
  flex: none;
}

.mini-btn {
  height: 30px;
  padding: 0 10px;
  border: 1px solid #e0cfc4;
  border-radius: 6px;
  background: #fdfaf8;
  color: #8a6048;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
  flex-shrink: 0;
}

.mini-btn:active {
  background: #f0e4db;
}

/* 路径输入 */
.path-input {
  width: 100%;
  min-height: 52px;
  padding: 8px 10px;
  border: 1px solid #f0d8c8;
  border-radius: 10px;
  font-size: 12px;
  font-family: monospace;
  color: #4c2a18;
  background: #fdf5ef;
  outline: none;
  resize: vertical;
  line-height: 1.5;
  word-break: break-all;
}

.path-input:focus {
  border-color: #f0a060;
}

/* 模板标签 */
.template-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
}

.tag-hint {
  font-size: 11px;
  color: #b89a84;
  margin-right: 2px;
}

.tag-btn {
  padding: 2px 8px;
  border: 1px solid #f0d8c8;
  border-radius: 999px;
  background: #fff0e7;
  color: #9b5a35;
  font-size: 11px;
  font-family: monospace;
  cursor: pointer;
  transition: background 0.15s;
}

.tag-btn:active {
  background: #fde0c8;
}

.tag-btn.tag-cond {
  background: #fdf0f0;
  color: #b06060;
  font-style: italic;
}

.tag-btn.tag-cond:active {
  background: #f8d8d8;
}

/* 章节列表 */
.chapter-list {
  max-height: 180px;
  overflow-y: auto;
  border: 1px solid #f0d8c8;
  border-radius: 10px;
  background: #fefcf9;
}

.chapter-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  cursor: pointer;
  user-select: none;
}

.chapter-row:not(:last-child) {
  border-bottom: 1px solid #f5ebe4;
}

.chapter-row:active {
  background: #fdf5ef;
}

.chapter-check {
  accent-color: #f0a060;
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}

.chapter-info {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.chapter-name {
  font-size: 14px;
  color: #4c2a18;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chapter-meta {
  font-size: 11px;
  color: #b89a84;
  font-family: monospace;
}

.toggle-all-btn {
  font-size: 12px;
  border: 0;
  background: transparent;
  color: #f0a060;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 4px;
}

.toggle-all-btn:active {
  background: #fff0e7;
}

/* 分卷 */
.split-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.split-hint {
  font-size: 13px;
  color: #8a6048;
}

.num-input-sm {
  width: 56px;
  height: 28px;
  border: 1px solid #e0cfc4;
  border-radius: 6px;
  text-align: center;
  font-size: 13px;
  color: #4c2a18;
  background: #fdfaf8;
  outline: none;
}

.num-input-sm:focus {
  border-color: #f0a060;
}

/* 质量选择 */
.quality-row {
  margin-top: 5px;
  display: flex;
  gap: 10px;
}

.radio-label {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  padding: 10px 14px;
  border: 1px solid #e0cfc4;
  border-radius: 10px;
  font-size: 14px;
  color: #4c2a18;
  cursor: pointer;
  background: #fefcf9;
  transition: border-color 0.15s, background 0.15s;
}

.radio-label.active {
  border-color: #f0a060;
  background: #fff0e7;
}

.radio-label input {
  accent-color: #f0a060;
}

/* 压缩比滑块 */
.ratio-slider {
  --bar-background: #f0e4db;
  --bar-background-active: #f0a060;
  --knob-background: #f0a060;
  --knob-size: 18px;
  --height: 4px;
  padding: 0;
}

.ratio-hint {
  font-size: 11px;
  color: #b89a84;
  margin-top: 2px;
}

/* 底部按钮 */
.sheet-footer {
  display: flex;
  gap: 10px;
  padding: 12px 18px 0;
  flex-shrink: 0;
}

.btn-cancel,
.btn-confirm {
  flex: 1;
  height: 42px;
  border: 0;
  border-radius: 10px;
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.15s;
}

.btn-cancel {
  background: #f0e4db;
  color: #8a6048;
}

.btn-cancel:active {
  opacity: 0.7;
}

.btn-confirm {
  background: linear-gradient(135deg, #f0a060, #e8843c);
  color: #fff;
}

.btn-confirm:active {
  opacity: 0.85;
}

.btn-confirm:disabled {
  opacity: 0.4;
  pointer-events: none;
}
</style>
