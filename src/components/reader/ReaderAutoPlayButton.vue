<template>
  <button
    v-if="visible && !leftMenuOpen"
    class="reader-auto-play-button"
    :class="{ dimmed }"
    type="button"
    :aria-label="isPlaying ? '暂停自动播放' : '开始自动播放'"
    :title="isPlaying ? '暂停自动播放' : '开始自动播放'"
    :aria-pressed="isPlaying"
    :disabled="disabled"
    @click.stop="emit('toggle')"
  >
    <IonIcon :icon="isPlaying ? pause : play" aria-hidden="true" />
  </button>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { IonIcon } from '@ionic/vue'
import { pause, play } from 'ionicons/icons'
import { leftMenuOpen } from '@/composables/useSideMenuState'
import { useTimedReveal } from '@/composables/useTimedReveal'

defineProps<{ isPlaying: boolean; disabled?: boolean }>()
const emit = defineEmits<{ toggle: [] }>()
const { visible, reveal, dispose } = useTimedReveal(2000)
const dimmed = ref(false)
let dimTimer: ReturnType<typeof setTimeout> | null = null

function revealButton() {
  if (visible.value) return
  dimmed.value = false
  reveal()
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

onMounted(() => {
  window.addEventListener('pointerdown', handleInteraction, { capture: true, passive: true })
  window.addEventListener('pointermove', handleInteraction, { capture: true, passive: true })
  window.addEventListener('touchstart', handleInteraction, { capture: true, passive: true })
  window.addEventListener('touchmove', handleInteraction, { capture: true, passive: true })
  window.addEventListener('keydown', handleKeydown, { capture: true })
})

onBeforeUnmount(() => {
  window.removeEventListener('pointerdown', handleInteraction, true)
  window.removeEventListener('pointermove', handleInteraction, true)
  window.removeEventListener('touchstart', handleInteraction, true)
  window.removeEventListener('touchmove', handleInteraction, true)
  window.removeEventListener('keydown', handleKeydown, true)
  if (dimTimer) clearTimeout(dimTimer)
  dispose()
})
</script>

<style scoped>
.reader-auto-play-button {
  position: fixed;
  z-index: 20;
  left: calc(18px + var(--ion-safe-area-left, 0px));
  bottom: calc(126px + var(--ion-safe-area-bottom, 0px));
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: #f0a060;
  color: #fff;
  opacity: 0.75;
  box-shadow: 0 2px 8px rgb(0 0 0 / 18%);
  transition: opacity 0.2s;
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
