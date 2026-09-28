<template>
  <div ref="root" class="inline-select">
    <span v-if="label" class="select-label">{{ label }}</span>
    <button
      ref="trigger"
      type="button"
      class="select-trigger"
      :class="{ open }"
      :aria-label="labelText"
      :aria-expanded="open"
      aria-haspopup="listbox"
      @click="open = !open"
    >
      <span>{{ selectedLabel }}</span>
      <IonIcon :icon="chevronDownOutline" :class="{ rotated: open }" />
    </button>
    <Transition name="select-options">
      <div v-if="open" class="select-options" role="listbox" :aria-label="labelText">
        <button
          v-for="option in options"
          :key="option.value"
          type="button"
          role="option"
          class="select-option"
          :class="{ selected: option.value === modelValue }"
          :aria-selected="option.value === modelValue"
          @click="selectOption(option.value)"
        >
          {{ option.label }}
        </button>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { IonIcon } from '@ionic/vue'
import { chevronDownOutline } from 'ionicons/icons'

interface InlineSelectOption {
  value: string
  label: string
}

const props = defineProps<{
  modelValue: string
  options: readonly InlineSelectOption[]
  labelText: string
  label?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const root = ref<HTMLElement | null>(null)
const trigger = ref<HTMLButtonElement | null>(null)
const open = ref(false)
const selectedLabel = computed(
  () => props.options.find((option) => option.value === props.modelValue)?.label || '',
)

const selectOption = (value: string) => {
  emit('update:modelValue', value)
  open.value = false
  trigger.value?.focus()
}

const handlePointerDown = (event: PointerEvent) => {
  if (open.value && !root.value?.contains(event.target as Node)) open.value = false
}

const handleKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Escape' && open.value) {
    open.value = false
    trigger.value?.focus()
    event.stopPropagation()
  }
}

onMounted(() => {
  document.addEventListener('pointerdown', handlePointerDown, true)
  document.addEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handlePointerDown, true)
  document.removeEventListener('keydown', handleKeydown)
})
</script>

<style scoped>
.inline-select {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}

.select-label {
  padding: 0 4px;
  color: #8a6048;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.select-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  min-height: 36px;
  padding: 0 14px;
  border: 1px solid #ead1c1;
  border-radius: 16px;
  color: #4c2a18;
  background: #fffaf6;
  font: inherit;
  font-size: 13px;
  text-align: left;
}

.select-trigger.open {
  border-color: #c06f45;
}

.select-trigger:focus-visible {
  outline: 2px solid #c06f45;
  outline-offset: -3px;
}

.select-trigger ion-icon {
  flex: 0 0 auto;
  margin-left: 12px;
  font-size: 18px;
  transition: transform 0.18s ease;
}

.select-trigger ion-icon.rotated {
  transform: rotate(180deg);
}

.select-options {
  position: absolute;
  top: calc(100% + 4px);
  right: 0;
  left: 0;
  z-index: 20;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 5px;
  overflow: auto;
  border: 1px solid #ead1c1;
  border-radius: 12px;
  background: #fffaf6;
  box-shadow: 0 8px 22px rgb(76 42 24 / 0.18);
}

.select-option {
  min-height: 34px;
  padding: 0 10px;
  border: 0;
  border-radius: 8px;
  color: #704631;
  background: transparent;
  font: inherit;
  font-size: 13px;
  text-align: left;
}

.select-option.selected {
  color: #c06f45;
  background: #fff1e7;
  font-weight: 600;
}

.select-option:focus-visible {
  outline: 2px solid #c06f45;
  outline-offset: -2px;
}

.select-options-enter-active,
.select-options-leave-active {
  transition:
    opacity 0.16s ease,
    transform 0.16s ease;
  transform-origin: top center;
}

.select-options-enter-from,
.select-options-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (prefers-reduced-motion: reduce) {
  .select-trigger ion-icon,
  .select-options-enter-active,
  .select-options-leave-active {
    transition: none;
  }
}
</style>
