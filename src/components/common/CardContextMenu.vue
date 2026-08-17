<template>
  <Teleport to="body">
    <div
      v-if="visible"
      ref="menuRef"
      class="card-context-menu"
      role="menu"
      :style="menuStyle"
      @mousedown.stop
      @touchstart.stop
    >
      <div v-if="title" class="card-context-menu-title">{{ title }}</div>
      <button
        v-for="action in actions"
        :key="action.id"
        type="button"
        class="card-menu-item"
        :class="{ 'card-menu-item--danger': action.danger }"
        :disabled="action.disabled"
        role="menuitem"
        @click="$emit('select', action.id)"
      >
        <IonSpinner v-if="action.loading" name="crescent" class="card-menu-spinner" />
        <IonIcon v-else :icon="action.icon" class="card-menu-icon" />
        <span>{{ action.label }}</span>
      </button>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { IonIcon, IonSpinner } from '@ionic/vue'

interface CardContextMenuAction {
  id: string
  label: string
  icon: string
  danger?: boolean
  disabled?: boolean
  loading?: boolean
}

const props = defineProps<{
  visible: boolean
  anchor: HTMLElement | null
  actions: CardContextMenuAction[]
  title?: string
}>()

const emit = defineEmits<{
  close: []
  select: [actionId: string]
}>()

const menuRef = ref<HTMLElement | null>(null)
const menuStyle = ref({
  position: 'fixed' as const,
  top: '8px',
  left: '8px',
})

async function updatePosition() {
  if (!props.visible || !props.anchor) return

  await nextTick()
  const menu = menuRef.value
  if (!menu) return

  const margin = 8
  const gap = 4
  const anchorRect = props.anchor.getBoundingClientRect()
  const menuRect = menu.getBoundingClientRect()
  const maxLeft = Math.max(margin, window.innerWidth - menuRect.width - margin)
  const maxTop = Math.max(margin, window.innerHeight - menuRect.height - margin)
  const belowTop = anchorRect.bottom + gap
  const aboveTop = anchorRect.top - gap - menuRect.height

  menuStyle.value = {
    position: 'fixed',
    left: `${Math.max(margin, Math.min(anchorRect.left, maxLeft))}px`,
    top: `${Math.max(margin, Math.min(belowTop + menuRect.height > window.innerHeight - margin ? aboveTop : belowTop, maxTop))}px`,
  }
}

function isInsideMenuOrAnchor(target: EventTarget | null) {
  if (!(target instanceof Node)) return false
  return Boolean(menuRef.value?.contains(target) || props.anchor?.contains(target))
}

function handlePointerDown(event: PointerEvent) {
  if (props.visible && !isInsideMenuOrAnchor(event.target)) emit('close')
}

function handleKeyDown(event: KeyboardEvent) {
  if (props.visible && event.key === 'Escape') emit('close')
}

watch([() => props.visible, () => props.anchor], () => {
  void updatePosition()
})

onMounted(() => {
  document.addEventListener('pointerdown', handlePointerDown, true)
  document.addEventListener('keydown', handleKeyDown)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handlePointerDown, true)
  document.removeEventListener('keydown', handleKeyDown)
})
</script>

<style scoped>
.card-context-menu {
  display: flex;
  flex-direction: column;
  min-width: 140px;
  max-width: calc(100vw - 16px);
  max-height: calc(100vh - 16px);
  padding: 6px;
  overflow-y: auto;
  border-radius: 14px;
  background: #fffaf6;
  box-shadow: 0 12px 36px rgb(76 42 24 / 0.22);
  z-index: 1000;
}

.card-context-menu-title {
  max-width: 220px;
  padding: 4px 14px 6px;
  overflow: hidden;
  color: #4c2a18;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 42px;
  padding: 10px 14px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: #3a261d;
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.12s ease;
}

.card-menu-item:active:not(:disabled) {
  background: #fff0e7;
}

.card-menu-item:disabled {
  cursor: default;
  opacity: 0.6;
}

.card-menu-item--danger {
  color: #d4533e;
}

.card-menu-icon,
.card-menu-spinner {
  flex-shrink: 0;
  width: 17px;
  height: 17px;
  color: #8a6048;
}

.card-menu-item--danger .card-menu-icon,
.card-menu-item--danger .card-menu-spinner {
  color: currentColor;
}

.card-menu-item:focus-visible {
  outline: 2px solid #c06f45;
  outline-offset: 2px;
}
</style>
