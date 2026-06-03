<template>
  <Teleport to="body">
    <div v-if="modelValue" class="sheet-backdrop" @click.self="close">
      <div class="sheet-panel">
        <div class="sheet-header">
          <span class="sheet-title">{{ albumTitle || '删除章节' }}</span>
          <button class="sheet-close" @click="close">&times;</button>
        </div>

        <div class="sheet-body">
          <div class="section">
            <div class="section-header">
              <span class="section-label">选择章节（{{ selectedKeys.size }}/{{ chapters.length }}）</span>
              <div class="quick-actions">
                <button class="toggle-btn" @click="selectAll">全选</button>
                <button class="toggle-btn" @click="invertSelection">反选</button>
                <button class="toggle-btn" @click="clearSelection">清空</button>
              </div>
            </div>
            <div class="chapter-list">
              <label
                v-for="ch in chapters"
                :key="chapterKey(ch)"
                class="chapter-row"
              >
                <input
                  type="checkbox"
                  class="chapter-check"
                  :checked="selectedKeys.has(chapterKey(ch))"
                  @change="toggleChapter(chapterKey(ch))"
                />
                <span class="chapter-info">
                  <span class="chapter-name">{{ chapterTitle(ch) }}</span>
                  <span class="chapter-meta">{{ chapterMeta(ch) }}</span>
                </span>
              </label>
            </div>
          </div>

          <p class="delete-hint">
            图片章节会删除下载文件和记录；PDF 章节只删除导入记录，不删除原 PDF 文件。
          </p>
        </div>

        <div class="sheet-footer">
          <button class="btn-cancel" @click="close">取消</button>
          <button
            class="btn-confirm"
            :disabled="selectedKeys.size === 0"
            @click="onConfirm"
          >
            删除{{ selectedKeys.size > 0 ? ` (${selectedKeys.size})` : '' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useBackButton } from '@ionic/vue'
import type { CompletedEntry } from '@/services/JmcomicTypes'

defineOptions({ name: 'DeleteChaptersBottomSheet' })

const props = defineProps<{
  modelValue: boolean
  albumTitle: string
  chapters: CompletedEntry[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: [chapters: CompletedEntry[]]
}>()

const selectedKeys = ref(new Set<string>())

const chapterKey = (ch: CompletedEntry) =>
  `${ch.source}|${ch.chapterId}|${ch.pdfData?.id ?? ch.downloadTask?.taskId ?? ''}`

const chapterTitle = (ch: CompletedEntry) => {
  const order = ch.chapterSortOrder
  const prefix = order && order > 0 ? `第${order}话` : ch.chapterId
  return ch.chapterTitle ? `${prefix} ${ch.chapterTitle}` : prefix
}

const chapterMeta = (ch: CompletedEntry) => {
  if (ch.source === 'pdf-import') {
    return `PDF · ${ch.pdfData?.pageCount ?? '?'}页`
  }
  return `图片 · ${ch.downloadTask?.totalPages ?? 0}页`
}

const selectAll = () => {
  selectedKeys.value = new Set(props.chapters.map(chapterKey))
}

const invertSelection = () => {
  const next = new Set<string>()
  for (const ch of props.chapters) {
    const key = chapterKey(ch)
    if (!selectedKeys.value.has(key)) next.add(key)
  }
  selectedKeys.value = next
}

const clearSelection = () => {
  selectedKeys.value = new Set()
}

const toggleChapter = (key: string) => {
  const next = new Set(selectedKeys.value)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }
  selectedKeys.value = next
}

const close = () => {
  emit('update:modelValue', false)
}

const onConfirm = () => {
  const selected = props.chapters.filter((ch) => selectedKeys.value.has(chapterKey(ch)))
  if (selected.length === 0) return
  emit('confirm', selected)
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) clearSelection()
  },
)

useBackButton(10, (processNextHandler) => {
  if (props.modelValue) {
    close()
  } else {
    processNextHandler()
  }
})
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
  max-height: min(78vh, 640px);
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

.sheet-body {
  flex: 1;
  overflow-y: auto;
  padding: 0 18px;
}

.section {
  margin-bottom: 12px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.section-label {
  font-size: 12px;
  font-weight: 600;
  color: #b89a84;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.quick-actions {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}

.toggle-btn {
  font-size: 12px;
  border: 0;
  background: transparent;
  color: #f0a060;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 4px;
}

.toggle-btn:active {
  background: #fff0e7;
}

.chapter-list {
  max-height: 260px;
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
  accent-color: #d9534f;
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

.delete-hint {
  margin: 0 0 12px;
  color: #8a6048;
  font-size: 12px;
  line-height: 1.5;
}

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
  background: linear-gradient(135deg, #d9534f, #c53f3b);
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
