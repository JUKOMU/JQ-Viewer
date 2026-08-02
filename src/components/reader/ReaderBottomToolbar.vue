<template>
  <div
    ref="toolbarRef"
    class="bottom-toolbar"
    :class="{ 'with-chapters': chapters.length > 0 }"
    @keydown.esc="closeChapterPicker(true)"
  >
    <Transition name="chapter-picker">
      <ul
        v-if="chapterPickerOpen"
        id="reader-chapter-picker"
        ref="chapterPickerRef"
        class="chapter-picker"
        aria-label="章节列表"
      >
        <li v-for="chapter in chapters" :key="chapter.id">
          <button
            type="button"
            class="chapter-option"
            :class="{ current: chapter.id === currentChapterId }"
            :aria-current="chapter.id === currentChapterId ? 'true' : undefined"
            @click="selectChapter(chapter.id)"
          >
            <span class="chapter-order">第{{ chapter.sortOrder }}话</span>
            <span v-if="chapter.title.trim()" class="chapter-title">{{ chapter.title }}</span>
          </button>
        </li>
      </ul>
    </Transition>

    <div v-if="chapters.length > 0" class="chapter-navigation-row">
      <button
        type="button"
        class="chapter-step-btn"
        aria-label="上一章"
        :disabled="!previousChapter"
        @click="selectAdjacentChapter(previousChapter)"
      >
        <span class="chapter-step-icons" aria-hidden="true">
          <ion-icon :icon="chevronBack" />
          <ion-icon :icon="chevronBack" />
        </span>
        <span>上一章</span>
      </button>

      <button
        ref="chapterTriggerRef"
        type="button"
        class="current-chapter-btn"
        :class="{ 'is-open': chapterPickerOpen }"
        aria-controls="reader-chapter-picker"
        :aria-expanded="chapterPickerOpen"
        :disabled="chapters.length === 0"
        @click="toggleChapterPicker"
      >
        <span class="current-chapter-text">
          <span class="chapter-picker-indicator" aria-hidden="true">
            <ion-icon class="chapter-picker-icon chapter-picker-icon-up" :icon="chevronUp" />
            <ion-icon class="chapter-picker-icon chapter-picker-icon-down" :icon="chevronDown" />
          </span>
          <span class="chapter-order">{{ currentChapterOrder }}</span>
          <span v-if="currentChapterTitle" class="chapter-title">{{ currentChapterTitle }}</span>
        </span>
      </button>

      <button
        type="button"
        class="chapter-step-btn chapter-step-btn-next"
        aria-label="下一章"
        :disabled="!nextChapter"
        @click="selectAdjacentChapter(nextChapter)"
      >
        <span>下一章</span>
        <span class="chapter-step-icons" aria-hidden="true">
          <ion-icon :icon="chevronForward" />
          <ion-icon :icon="chevronForward" />
        </span>
      </button>
    </div>

    <div class="reader-progress-row">
      <button
        type="button"
        class="settings-btn"
        aria-label="阅读设置"
        title="阅读设置"
        @click="openSettings"
      >
        <ion-icon :icon="settingsOutline" aria-hidden="true" />
      </button>
      <span class="page-number current-page">{{ current }}</span>
      <ion-range
        class="progress-bar"
        aria-label="阅读进度"
        :min="1"
        :max="total"
        :value="current"
        :disabled="total <= 1"
        @ion-change="onRangeChange"
        @ion-input="onRangeInput"
        @ion-knob-move-start="$emit('progress-drag-start')"
        @ion-knob-move-end="$emit('progress-drag-end')"
      />
      <span class="page-number total-pages">{{ total }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { RangeCustomEvent } from '@ionic/vue'
import { IonIcon, IonRange } from '@ionic/vue'
import {
  chevronBack,
  chevronDown,
  chevronForward,
  chevronUp,
  settingsOutline,
} from 'ionicons/icons'
import type { PhotoMeta } from '@/services/JmcomicTypes'

defineOptions({ name: 'ReaderBottomToolbar' })

const props = withDefaults(
  defineProps<{
    current: number
    total: number
    chapters?: PhotoMeta[]
    currentChapterId?: string
  }>(),
  {
    chapters: () => [],
    currentChapterId: '',
  },
)

const emit = defineEmits<{
  'open-settings': []
  'select-chapter': [chapterId: string]
  'update:current': [page: number]
  'update:current-input': [page: number]
  'progress-drag-start': []
  'progress-drag-end': []
}>()

const toolbarRef = ref<HTMLElement | null>(null)
const chapterTriggerRef = ref<HTMLButtonElement | null>(null)
const chapterPickerRef = ref<HTMLElement | null>(null)
const chapterPickerOpen = ref(false)

const currentChapterIndex = computed(() =>
  props.chapters.findIndex((chapter) => chapter.id === props.currentChapterId),
)
const currentChapter = computed(() => props.chapters[currentChapterIndex.value])
const previousChapter = computed(() => {
  if (currentChapterIndex.value <= 0) return null
  return props.chapters[currentChapterIndex.value - 1]
})
const nextChapter = computed(() => {
  if (currentChapterIndex.value < 0 || currentChapterIndex.value >= props.chapters.length - 1) {
    return null
  }
  return props.chapters[currentChapterIndex.value + 1]
})
const currentChapterOrder = computed(() =>
  currentChapter.value ? `第${currentChapter.value.sortOrder}话` : '当前章节',
)
const currentChapterTitle = computed(() => currentChapter.value?.title.trim() ?? '')

const centerCurrentChapter = () => {
  nextTick(() => {
    const picker = chapterPickerRef.value
    const current = picker?.querySelector<HTMLElement>('[aria-current="true"]')
    if (!picker || !current) return
    picker.scrollTop = Math.max(
      0,
      current.offsetTop - (picker.clientHeight - current.offsetHeight) / 2,
    )
  })
}

const toggleChapterPicker = () => {
  chapterPickerOpen.value = !chapterPickerOpen.value
  if (chapterPickerOpen.value) centerCurrentChapter()
}

const closeChapterPicker = (restoreFocus = false) => {
  if (!chapterPickerOpen.value) return
  chapterPickerOpen.value = false
  if (restoreFocus) nextTick(() => chapterTriggerRef.value?.focus())
}

const selectChapter = (chapterId: string) => {
  closeChapterPicker()
  if (chapterId !== props.currentChapterId) emit('select-chapter', chapterId)
}

const selectAdjacentChapter = (chapter: PhotoMeta | null) => {
  closeChapterPicker()
  if (chapter) emit('select-chapter', chapter.id)
}

const openSettings = () => {
  closeChapterPicker()
  emit('open-settings')
}

const onDocumentPointerDown = (event: PointerEvent) => {
  if (!chapterPickerOpen.value) return
  const target = event.target
  if (target instanceof Node && !toolbarRef.value?.contains(target)) closeChapterPicker()
}

onMounted(() => document.addEventListener('pointerdown', onDocumentPointerDown))
onBeforeUnmount(() => document.removeEventListener('pointerdown', onDocumentPointerDown))

const onRangeChange = (ev: RangeCustomEvent) => {
  const page = Number(ev.detail.value)
  emit('update:current', page)
}

let lastInputTime = 0

const onRangeInput = (ev: RangeCustomEvent) => {
  const now = performance.now()
  if (now - lastInputTime < 100) return
  lastInputTime = now
  const page = Number(ev.detail.value)
  emit('update:current-input', page)
}
</script>

<style scoped>
.bottom-toolbar {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 20;
  display: flex;
  flex-direction: column;
  gap: 6px;
  box-sizing: border-box;
  min-height: calc(56px + var(--jq-reader-safe-area-bottom, var(--ion-safe-area-bottom, 0px)));
  padding: 8px 12px calc(8px + var(--jq-reader-safe-area-bottom, var(--ion-safe-area-bottom, 0px)));
  background: rgba(0, 0, 0, 0.68);
  backdrop-filter: blur(8px);
}

.bottom-toolbar.with-chapters {
  min-height: calc(112px + var(--jq-reader-safe-area-bottom, var(--ion-safe-area-bottom, 0px)));
}

.chapter-navigation-row,
.reader-progress-row {
  display: flex;
  align-items: center;
  width: 100%;
  max-width: 720px;
  margin: 0 auto;
}

.chapter-navigation-row {
  gap: 8px;
  min-height: 52px;
}

.reader-progress-row {
  gap: 8px;
  min-height: 38px;
}

.chapter-step-btn,
.current-chapter-btn,
.settings-btn,
.chapter-option {
  border: 0;
  color: #fff;
}

.chapter-step-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  flex: 0 0 84px;
  height: 38px;
  border-radius: 6px;
  background: rgb(255 255 255 / 0.12);
  font-size: 12px;
  white-space: nowrap;
}

.chapter-step-icons {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
}

.chapter-step-icons ion-icon {
  flex-shrink: 0;
  margin: 0 -4px;
  font-size: 15px;
}

.chapter-step-btn:disabled {
  background: rgb(255 255 255 / 0.06);
  color: rgb(255 255 255 / 0.32);
}

.current-chapter-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 1;
  min-width: 0;
  height: 52px;
  padding: 5px 8px;
  border-radius: 6px;
  background: rgb(255 255 255 / 0.16);
  transition: background-color 0.18s ease;
}

.current-chapter-btn.is-open {
  background: rgb(0 0 0 / 0.3);
}

.current-chapter-btn:disabled {
  opacity: 0.45;
}

.current-chapter-text {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 0;
  max-width: calc(100% - 48px);
}

.chapter-order {
  display: block;
  overflow: hidden;
  max-width: 100%;
  color: #fff;
  font-size: 14px;
  font-weight: 700;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chapter-title {
  display: block;
  overflow: hidden;
  max-width: 100%;
  margin-top: 2px;
  color: rgb(255 255 255 / 0.68);
  font-size: 11px;
  font-weight: 400;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chapter-picker-indicator {
  position: absolute;
  top: 50%;
  right: calc(100% + 6px);
  width: 16px;
  height: 28px;
  transform: translateY(-50%);
}

.chapter-picker-icon {
  position: absolute;
  top: 50%;
  left: 50%;
  color: rgb(255 255 255 / 0.72);
  font-size: 14px;
  transition: transform 0.18s ease;
}

.chapter-picker-icon-up {
  transform: translate(-50%, calc(-50% - 4px));
}

.chapter-picker-icon-down {
  transform: translate(-50%, calc(-50% + 4px));
}

.current-chapter-btn.is-open .chapter-picker-icon-up {
  transform: translate(-50%, calc(-50% - 7px));
}

.current-chapter-btn.is-open .chapter-picker-icon-down {
  transform: translate(-50%, calc(-50% + 7px));
}

.settings-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 34px;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: rgb(255 255 255 / 0.12);
  font-size: 18px;
}

.page-number {
  flex: 0 0 auto;
  min-width: 20px;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  text-align: center;
  white-space: nowrap;
}

.progress-bar {
  flex: 1;
  min-width: 48px;
  --bar-background: rgba(255, 255, 255, 0.25);
  --bar-background-active: #fa9c69;
  --knob-background: #fa9c69;
  --knob-size: 18px;
  --height: 4px;
  padding: 0;
}

.chapter-picker {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 50%;
  width: min(calc(100% - 24px), 720px);
  z-index: 1;
  box-sizing: border-box;
  overflow-y: auto;
  max-height: min(364px, calc(100vh - 156px));
  margin: 0;
  padding: 4px;
  border: 1px solid rgb(255 255 255 / 0.14);
  border-radius: 6px;
  background: rgb(24 24 24 / 0.96);
  box-shadow: 0 -8px 24px rgb(0 0 0 / 0.32);
  list-style: none;
  overscroll-behavior: contain;
  touch-action: pan-y;
  transform: translateX(-50%);
}

.chapter-picker-enter-active,
.chapter-picker-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}

.chapter-picker-enter-from,
.chapter-picker-leave-to {
  opacity: 0;
  transform: translate(-50%, 6px);
}

.chapter-picker li {
  margin: 0;
  padding: 0;
}

.chapter-option {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  width: 100%;
  height: 52px;
  padding: 6px 12px;
  border-radius: 4px;
  background: transparent;
  text-align: left;
}

.chapter-option.current {
  background: rgb(250 156 105 / 0.2);
}

.chapter-option.current .chapter-order {
  color: #ffb58e;
}

.chapter-option:focus-visible,
.chapter-step-btn:focus-visible,
.current-chapter-btn:focus-visible,
.settings-btn:focus-visible {
  outline: 2px solid #ffb58e;
  outline-offset: 1px;
}

@media (max-width: 360px) {
  .bottom-toolbar {
    padding-right: 8px;
    padding-left: 8px;
  }

  .chapter-navigation-row {
    gap: 5px;
  }

  .chapter-step-btn {
    flex-basis: 72px;
    font-size: 11px;
  }

  .chapter-picker {
    width: calc(100% - 16px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .current-chapter-btn,
  .chapter-picker-icon,
  .chapter-picker-enter-active,
  .chapter-picker-leave-active {
    transition: none;
  }
}
</style>
