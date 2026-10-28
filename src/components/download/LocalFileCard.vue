<template>
  <article
    class="card"
    :class="{
      'multi-unselected': selectionMode && !selected,
      'multi-selected': selected,
      'long-pressing': longPressActive,
    }"
    @pointerdown="startLongPress"
    @pointermove="handlePointerMove"
    @pointerup="cancelLongPress"
    @pointercancel="cancelLongPress"
  >
    <div class="cover-wrap">
      <img
        v-if="file.coverUrl"
        :src="file.coverUrl"
        class="cover"
        referrerpolicy="no-referrer"
        alt=""
      />
      <div v-else class="cover-placeholder">
        <IonIcon :icon="documentOutline" />
      </div>
    </div>
    <button
      class="info open-btn"
      type="button"
      :aria-label="readable ? `阅读 ${formatLabel}` : `${formatLabel} 文件信息`"
      :disabled="!selectionMode && !readable"
      @click="
        suppressClick ? (suppressClick = false) : selectionMode ? emit('select') : emit('open')
      "
    >
      <div class="title">{{ file.albumTitle || file.fileName }}</div>
      <div class="subtitle">{{ file.chapterTitle || file.fileName }}</div>
      <div class="meta-row">
        <span class="resource-icons" :aria-label="resourceLabel">
          <IonIcon v-if="hasImageResource" :icon="imagesOutline" class="image-resource-icon" />
          <IonIcon :icon="documentOutline" class="file-resource-icon" />
        </span>
        <span class="tag format-tag">{{ formatLabel }}</span>
        <span class="tag source-tag">{{ sourceLabel }}</span>
        <span class="tag link-tag">{{ chapterLinkLabel }}</span>
        <span v-if="file.pageCount > 0" class="tag completed">共 {{ file.pageCount }} 页</span>
        <span v-if="verifying" class="status verifying" role="status">
          <IonSpinner name="crescent" />
          校验中
        </span>
        <span v-else class="status" :class="file.availability">{{ availabilityLabel }}</span>
        <span v-if="sizeText" class="size-text">{{ sizeText }}</span>
      </div>
    </button>
    <input
      v-if="selectionMode"
      class="multi-select-checkbox"
      type="checkbox"
      :checked="selected"
      :aria-label="`${selected ? '取消选择' : '选择'}${file.fileName}`"
      @pointerdown.stop
      @click.stop
      @change.stop="emit('select')"
    />
    <button
      v-else
      class="more-btn"
      :class="{ active: menuOpen }"
      type="button"
      aria-label="更多操作"
      :aria-expanded="menuOpen"
      @pointerdown.stop
      @click.stop="$emit('more', $event)"
    >
      <IonIcon :icon="ellipsisVertical" />
    </button>
  </article>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { IonIcon, IonSpinner } from '@ionic/vue'
import { documentOutline, ellipsisVertical, imagesOutline } from 'ionicons/icons'
import type { LocalFileRecord } from '@/services/JmcomicTypes'

const props = defineProps<{
  file: LocalFileRecord
  hasImageResource: boolean
  verifying?: boolean
  menuOpen?: boolean
  readable?: boolean
  selectionMode?: boolean
  selected?: boolean
}>()

const emit = defineEmits<{
  open: []
  more: [event: Event]
  select: []
  'long-press': []
}>()
const longPressActive = ref(false)
let longPressTimer: ReturnType<typeof setTimeout> | null = null
let suppressClick = false
let longPressStartPoint: { x: number; y: number } | null = null
const startLongPress = (event: PointerEvent) => {
  if (event.button !== 0 || !event.isPrimary) return
  cancelLongPress()
  longPressStartPoint = { x: event.clientX, y: event.clientY }
  longPressTimer = setTimeout(() => {
    suppressClick = true
    longPressActive.value = true
    emit('long-press')
    longPressTimer = null
  }, 500)
}
const cancelLongPress = () => {
  if (longPressTimer !== null) clearTimeout(longPressTimer)
  longPressTimer = null
  longPressStartPoint = null
  longPressActive.value = false
}
const handlePointerMove = (event: PointerEvent) => {
  if (
    longPressStartPoint &&
    Math.hypot(event.clientX - longPressStartPoint.x, event.clientY - longPressStartPoint.y) > 10
  )
    cancelLongPress()
}
onBeforeUnmount(cancelLongPress)

const formatLabel = computed(() => props.file.format.toUpperCase())
const resourceLabel = computed(() =>
  props.hasImageResource ? `图片和 ${formatLabel.value}` : formatLabel.value,
)
const sourceLabel = computed(() => (props.file.sourceType === 'imported' ? '导入' : '导出'))
const chapterLinkLabel = computed(() => {
  if (props.file.chapterLinkStatus === 'multi_chapter') return '多章节'
  if (props.file.chapterLinkStatus === 'resolved') return '已关联'
  return '未关联'
})
const sizeText = computed(() => {
  const size = props.file.fileSize
  if (!size || size <= 0) return ''
  if (size >= 1024 * 1024 * 1024) return `${(size / (1024 * 1024 * 1024)).toFixed(1)} GB`
  if (size >= 1024 * 1024) return `${(size / (1024 * 1024)).toFixed(1)} MB`
  if (size >= 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${size} B`
})
const availabilityLabel = computed(() => {
  if (props.file.availability === 'available') return '可用'
  if (props.file.availability === 'missing') return '文件缺失'
  if (props.file.availability === 'inaccessible') return '无法读取'
  if (props.file.availability === 'invalid') return '文件损坏'
  return '待校验'
})
</script>

<style scoped>
.card {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-height: 108px;
  padding: 0 10px 0 0;
  background: #fffaf6;
  border-radius: 12px;
  position: relative;
  box-shadow: 5px 12px 28px rgb(76 42 24 / 0.2);
  cursor: pointer;
  transition: transform 0.16s ease;
}
.card.long-pressing {
  transform: scale(0.98);
}
.multi-unselected {
  opacity: 0.5;
}
.multi-selected {
  opacity: 1;
}
.multi-select-checkbox {
  position: absolute;
  z-index: 2;
  top: 50%;
  right: 5px;
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  transform: translateY(-50%);
  accent-color: #bf8350;
}

.cover-wrap {
  width: 80px;
  aspect-ratio: 3 / 4;
  flex: 0 0 80px;
  align-self: flex-start;
  overflow: hidden;
  border-radius: 6px;
  background: linear-gradient(145deg, #f3ded0, #ffece0);
}

.cover,
.cover-placeholder {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.cover-placeholder {
  display: grid;
  place-items: center;
  background: linear-gradient(145deg, #f3ded0, #ffece0);
  color: #9f785f;
  font-size: 28px;
}

.info {
  flex: 1 1 0;
  width: 0;
  min-width: 0;
  overflow: hidden;
  padding: 0 2px 0 4px;
}

.open-btn {
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  gap: 4px;
  border: 0;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.subtitle {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.title {
  display: -webkit-box;
  width: 100%;
  max-width: 100%;
  margin: 0;
  overflow: hidden;
  color: #30201a;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.4;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.subtitle {
  display: inline-block;
  align-self: flex-start;
  max-width: 100%;
  margin-top: 4px;
  padding: 2px 8px;
  border-radius: 4px;
  background: #f0ede8;
  color: #8a6048;
  font-family: monospace;
  font-size: 10px;
}

.meta-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  color: #8a6048;
  font-size: 11px;
}

.resource-icons {
  display: inline-flex;
  flex: 0 0 auto;
  gap: 3px;
  font-size: 14px;
}

.file-resource-icon {
  color: #c03939;
}

.image-resource-icon {
  color: #2d7ab5;
}

.tag,
.status {
  flex: 0 0 auto;
  white-space: nowrap;
  padding: 2px 6px;
  border-radius: 4px;
  background: #f5e2d6;
}

.tag.completed {
  background: #eaf7ea;
  color: #52a86b;
}

.format-tag {
  color: #8a4e2c;
  font-weight: 600;
}

.source-tag,
.link-tag {
  background: #f0ede8;
  color: #765b4d;
}

.status.available {
  background: #eaf7ea;
  color: #52a86b;
}

.status.missing {
  background: #ffeaea;
  color: #d9534f;
}

.status.inaccessible {
  background: #fff8e1;
  color: #a65f00;
}

.status.invalid {
  background: #ffe3df;
  color: #a52d27;
}

.status.unknown {
  background: #f0ede8;
  color: #8a6048;
}

.status.verifying {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  background: #fff8e1;
  color: #a65f00;
}

.status.verifying ion-spinner {
  width: 12px;
  height: 12px;
}

.size-text {
  color: #b89a84;
  font-size: 11px;
}

.more-btn {
  position: absolute;
  top: 50%;
  right: 2px;
  width: 28px;
  height: 28px;
  transform: translateY(-50%);
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #8a6048;
  font-size: 18px;
}

.open-btn:focus-visible,
.more-btn:focus-visible {
  outline: 2px solid #c06f45;
  outline-offset: 2px;
}

.open-btn:disabled {
  cursor: default;
  opacity: 1;
}

.more-btn:active,
.more-btn.active {
  background: rgb(250 156 105 / 0.15);
  color: #c96d3a;
}

@media (prefers-reduced-motion: reduce) {
  .card {
    transition: none;
  }
}
</style>
