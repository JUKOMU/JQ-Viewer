<template>
  <button
    v-if="(visible || focused) && !leftMenuOpen"
    class="reader-auto-play-button"
    :class="[{ dimmed }, intervalSeconds < 0 ? 'negative' : intervalSeconds > 0 ? 'positive' : '']"
    :style="{ '--speed-fill': `${speedFillPercent}%` }"
    type="button"
    :aria-label="isPlaying ? '暂停自动播放' : '开始自动播放'"
    :title="isPlaying ? '暂停自动播放' : '开始自动播放'"
    :aria-pressed="isPlaying"
    :disabled="disabled"
    @focus="focused = true"
    @blur="focused = false"
    @click.stop="emit('toggle')"
  >
    <span class="reader-auto-play-speed-ring" aria-hidden="true">
      <span class="reader-auto-play-speed-half upper" />
      <span class="reader-auto-play-speed-half lower" />
      <span class="reader-auto-play-speed-midline" />
    </span>
    <span class="reader-auto-play-face">
      <IonIcon :icon="buttonIcon" aria-hidden="true" />
    </span>
  </button>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { IonIcon } from '@ionic/vue'
import {
  arrowBackOutline,
  arrowDownOutline,
  arrowForwardOutline,
  arrowUpOutline,
  pause,
  play,
} from 'ionicons/icons'
import { leftMenuOpen } from '@/composables/useSideMenuState'
import { useTimedReveal } from '@/composables/useTimedReveal'

const props = defineProps<{
  isPlaying: boolean
  isVertical: boolean
  intervalSeconds: number
  speedAdjustmentVersion: number
  disabled?: boolean
}>()
const emit = defineEmits<{ toggle: [] }>()
const { visible, reveal, restart, dispose } = useTimedReveal(2000)
const dimmed = ref(false)
const focused = ref(false)
const showDirectionIcon = ref(false)
const speedFillPercent = computed(
  () => Math.floor(((30 - Math.abs(props.intervalSeconds)) / 29.9) * 10000) / 100,
)
const buttonIcon = computed(() => {
  if (!showDirectionIcon.value || !props.isPlaying) return props.isPlaying ? pause : play
  if (props.isVertical) return props.intervalSeconds < 0 ? arrowUpOutline : arrowDownOutline
  return props.intervalSeconds < 0 ? arrowBackOutline : arrowForwardOutline
})
let dimTimer: ReturnType<typeof setTimeout> | null = null
let directionIconTimer: ReturnType<typeof setTimeout> | null = null

function revealButton(resetTimer = false) {
  if (visible.value && !resetTimer) return
  if (dimTimer) clearTimeout(dimTimer)
  dimmed.value = false
  if (visible.value) restart()
  else reveal()
  dimTimer = setTimeout(() => {
    dimmed.value = true
    dimTimer = null
  }, 1000)
}

function handleInteraction(event: Event) {
  if (event.target instanceof Element && event.target.closest('.main-menu')) return
  if (leftMenuOpen.value) return
  revealButton()
}

function isEditable(target: EventTarget | null) {
  return (
    target instanceof HTMLElement &&
    (target.isContentEditable ||
      !!target.closest(
        'input, textarea, select, ion-input, ion-textarea, ion-select, [contenteditable="true"]',
      ))
  )
}

function handleKeydown(event: KeyboardEvent) {
  if (isEditable(event.target) || ['Shift', 'Control', 'Alt', 'Meta'].includes(event.key)) return
  revealButton()
}

function showPlaybackDirection() {
  showDirectionIcon.value = true
  if (directionIconTimer) clearTimeout(directionIconTimer)
  directionIconTimer = setTimeout(() => {
    showDirectionIcon.value = false
    directionIconTimer = null
  }, 1000)
}

watch(
  () => props.speedAdjustmentVersion,
  () => {
    if (!props.isPlaying) return
    revealButton(true)
    showPlaybackDirection()
  },
)

watch(
  () => props.isPlaying,
  (isPlaying) => {
    if (isPlaying) return
    showDirectionIcon.value = false
    if (directionIconTimer) clearTimeout(directionIconTimer)
    directionIconTimer = null
  },
)

onMounted(() => {
  window.addEventListener('pointerdown', handleInteraction, { capture: true, passive: true })
  window.addEventListener('touchstart', handleInteraction, { capture: true, passive: true })
  window.addEventListener('keydown', handleKeydown, { capture: true })
})

onBeforeUnmount(() => {
  window.removeEventListener('pointerdown', handleInteraction, true)
  window.removeEventListener('touchstart', handleInteraction, true)
  window.removeEventListener('keydown', handleKeydown, true)
  if (dimTimer) clearTimeout(dimTimer)
  if (directionIconTimer) clearTimeout(directionIconTimer)
  dispose()
})
</script>

<style scoped>
.reader-auto-play-button {
  position: fixed;
  z-index: 20;
  left: calc(14px + var(--ion-safe-area-left, 0px));
  bottom: calc(122px + var(--ion-safe-area-bottom, 0px));
  display: block;
  width: 50px;
  height: 50px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: #fff;
  opacity: 0.75;
  box-shadow: 0 2px 8px rgb(0 0 0 / 18%);
  transition: opacity 0.2s;
}

.reader-auto-play-speed-ring {
  position: absolute;
  inset: 0;
  overflow: hidden;
  border-radius: 50%;
  background: #d7dadd;
}

.reader-auto-play-speed-half {
  position: absolute;
  left: 0;
  width: 100%;
  height: 50%;
  background: linear-gradient(
    to top,
    #cc7435 0 var(--speed-fill),
    transparent var(--speed-fill) 100%
  );
}

.reader-auto-play-speed-half.upper {
  top: 0;
  border-radius: 100% 100% 0 0;
}

.reader-auto-play-speed-half.lower {
  bottom: 0;
  border-radius: 0 0 100% 100%;
  background: linear-gradient(
    to bottom,
    #cc7435 0 var(--speed-fill),
    transparent var(--speed-fill) 100%
  );
}

.reader-auto-play-speed-midline {
  position: absolute;
  top: 50%;
  right: 0;
  left: 0;
  height: 1px;
  transform: translateY(-50%);
  background: #8c6b5a;
  pointer-events: none;
}

.reader-auto-play-button.negative .reader-auto-play-speed-half.lower,
.reader-auto-play-button.positive .reader-auto-play-speed-half.upper {
  background: transparent;
}

.reader-auto-play-face {
  position: absolute;
  z-index: 1;
  inset: 4px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #f0a060;
  color: #fff;
}

.reader-auto-play-button:disabled {
  opacity: 0.45;
}

.reader-auto-play-button.dimmed {
  opacity: 0.4;
}

.reader-auto-play-button:focus-visible {
  outline: 2px solid #fff;
  outline-offset: 2px;
}

.reader-auto-play-button ion-icon {
  font-size: 21px;
}
</style>
