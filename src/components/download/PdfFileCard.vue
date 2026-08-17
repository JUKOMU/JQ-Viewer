<template>
  <article class="card">
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
    <button class="info open-btn" type="button" aria-label="打开 PDF" @click="$emit('open')">
      <div class="title">{{ file.albumTitle || file.fileName }}</div>
      <div class="subtitle">{{ file.chapterTitle || file.fileName }}</div>
      <div class="meta-row">
        <span class="resource-icons" :aria-label="resourceLabel">
          <IonIcon v-if="hasImageResource" :icon="imagesOutline" class="image-resource-icon" />
          <IonIcon :icon="documentOutline" class="pdf-resource-icon" />
        </span>
        <span v-if="file.pageCount > 0" class="tag completed">共 {{ file.pageCount }} 页</span>
        <span v-if="verifying" class="status verifying" role="status">
          <IonSpinner name="crescent" />
          校验中
        </span>
        <span v-else class="status" :class="file.availability">{{ availabilityLabel }}</span>
        <span v-if="sizeText" class="size-text">{{ sizeText }}</span>
      </div>
    </button>
    <button
      class="more-btn"
      :class="{ active: menuOpen }"
      type="button"
      aria-label="更多操作"
      :aria-expanded="menuOpen"
      @click.stop="$emit('more', $event)"
    >
      <IonIcon :icon="ellipsisVertical" />
    </button>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { IonIcon, IonSpinner } from '@ionic/vue'
import { documentOutline, ellipsisVertical, imagesOutline } from 'ionicons/icons'
import type { ImportedPdf } from '@/services/JmcomicTypes'

const props = defineProps<{
  file: ImportedPdf
  hasImageResource: boolean
  verifying?: boolean
  menuOpen?: boolean
}>()

defineEmits<{
  open: []
  more: [event: Event]
}>()

const resourceLabel = computed(() => (props.hasImageResource ? '图片和 PDF' : 'PDF'))
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
  gap: 12px;
  min-height: 85px;
  padding: 0 36px 0 0;
  background: #fffaf6;
  border: 1px solid rgb(245 210 188 / 0.5);
  border-radius: 12px;
  position: relative;
  cursor: pointer;
}

.cover-wrap {
  width: 64px;
  height: 85px;
  flex: 0 0 64px;
  overflow: hidden;
  border-radius: 6px;
  background: #ece1d8;
  box-shadow: 2px 0 2px rgba(0, 0, 0, 0.2);
}

.cover,
.cover-placeholder {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-placeholder {
  display: grid;
  place-items: center;
  color: #9f785f;
  font-size: 28px;
}

.info {
  flex: 1 1 0;
  width: 0;
  min-width: 0;
  overflow: hidden;
  padding: 0;
}

.open-btn {
  display: flex;
  flex-direction: column;
  justify-content: center;
  border: 0;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.title,
.subtitle {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.title {
  display: block;
  width: 100%;
  max-width: 100%;
  color: #4c2a18;
  font-size: 14px;
  font-weight: 600;
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
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  color: #8a6048;
  font-size: 11px;
}

.resource-icons {
  display: inline-flex;
  gap: 3px;
  font-size: 14px;
}

.pdf-resource-icon {
  color: #c03939;
}

.image-resource-icon {
  color: #2d7ab5;
}

.tag,
.status {
  padding: 2px 6px;
  border-radius: 4px;
  background: #f5e2d6;
}

.tag.completed {
  background: #eaf7ea;
  color: #52a86b;
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
