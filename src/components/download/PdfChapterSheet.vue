<template>
  <Teleport to="body">
    <div v-if="modelValue" class="sheet-backdrop" @click.self="close">
      <div class="sheet-panel">
        <div class="sheet-header">
          <span class="sheet-title">章节列表</span>
          <button class="sheet-close" @click="close">&times;</button>
        </div>
        <div class="sheet-body">
          <div v-if="chapters.length === 0" class="empty">暂无章节</div>
          <div
            v-for="(ch, i) in chapters"
            :key="ch.chapterId + '|' + ch.chapterTitle"
            class="chapter-row"
            @click="onSelect(ch)"
          >
            <div class="chapter-main">
              <span class="chapter-index">#{{ i + 1 }}</span>
              <span class="chapter-name">{{ ch.chapterTitle }}</span>
            </div>
            <span class="chapter-pages">{{ ch.pdfData?.pageCount ?? '?' }} 页</span>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import type { CompletedEntry } from '@/services/JmcomicTypes'
import { useBackButton } from '@ionic/vue'

const props = defineProps<{
  modelValue: boolean
  albumTitle: string
  chapters: CompletedEntry[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'select': [chapter: CompletedEntry]
}>()

const close = () => {
  emit('update:modelValue', false)
}

const onSelect = (chapter: CompletedEntry) => {
  emit('select', chapter)
}

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
  z-index: 1000;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: flex-end;
  animation: fadeIn 0.22s ease;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.sheet-panel {
  width: 100%;
  max-height: 60vh;
  background: linear-gradient(180deg, #fffaf6 0%, #fff3eb 100%);
  border-radius: 20px 20px 0 0;
  display: flex;
  flex-direction: column;
  animation: slideUp 0.28s ease;
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
  font-size: 16px;
  font-weight: 700;
  color: #4c2a18;
}

.sheet-close {
  width: 30px;
  height: 30px;
  border: none;
  background: none;
  font-size: 22px;
  color: #b89a84;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
}

.sheet-close:active {
  background: rgba(0, 0, 0, 0.06);
}

.sheet-body {
  overflow-y: auto;
  padding: 0 16px 24px;
  flex: 1;
}

.empty {
  text-align: center;
  color: #b89a84;
  padding: 32px 0;
  font-size: 14px;
}

.chapter-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 12px;
  margin-top: 8px;
  background: #fefcf9;
  border: 1px solid #f0d8c8;
  border-radius: 10px;
  cursor: pointer;
  transition: background-color 0.15s;
}

.chapter-row:active {
  background: #fdf5ef;
}

.chapter-main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
}

.chapter-index {
  font-size: 11px;
  font-weight: 700;
  color: #e07030;
  flex-shrink: 0;
  background: #fff3eb;
  padding: 2px 8px;
  border-radius: 6px;
}

.chapter-name {
  font-size: 13px;
  color: #5a3d2e;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chapter-pages {
  font-size: 11px;
  color: #b89a84;
  flex-shrink: 0;
  margin-left: 8px;
}
</style>
