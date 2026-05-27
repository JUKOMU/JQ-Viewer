<template>
  <div class="quick-fab">
    <TransitionGroup name="fab-action" tag="div" class="action-stack">
      <button
        v-if="expanded"
        key="search"
        type="button"
        class="action-btn"
        aria-label="搜索"
        @click="trigger('search')"
      >
        <IonIcon :icon="searchOutline" />
      </button>
      <button
        v-if="expanded"
        key="jump"
        type="button"
        class="action-btn"
        aria-label="跳页"
        @click="trigger('jump')"
      >
        <IonIcon :icon="albumsOutline" />
      </button>
      <button
        v-if="expanded"
        key="top"
        type="button"
        class="action-btn"
        aria-label="回顶"
        @click="trigger('top')"
      >
        <IonIcon :icon="arrowUpOutline" />
      </button>
      <button
        v-if="expanded"
        key="back"
        type="button"
        class="action-btn"
        aria-label="返回"
        @click="trigger('back')"
      >
        <IonIcon :icon="returnDownBackOutline" />
      </button>
    </TransitionGroup>

    <button
      type="button"
      class="main-btn"
      :class="{ expanded }"
      aria-label="快捷操作"
      @click="expanded = !expanded"
    >
      <IonIcon :icon="ellipsisHorizontalOutline" />
    </button>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'QuickActionFab' })

const emit = defineEmits<{
  search: []
  jump: []
  top: []
  back: []
}>()
import { ref } from 'vue'
import { IonIcon } from '@ionic/vue'
import {
  albumsOutline,
  arrowUpOutline,
  ellipsisHorizontalOutline,
  returnDownBackOutline,
  searchOutline,
} from 'ionicons/icons'

const expanded = ref(false)

const trigger = (action: 'search' | 'jump' | 'top' | 'back') => {
  expanded.value = false
  if (action === 'search') {
    emit('search')
  } else if (action === 'jump') {
    emit('jump')
  } else if (action === 'top') {
    emit('top')
  } else {
    emit('back')
  }
}
</script>

<style scoped>
.quick-fab {
  position: absolute;
  right: 16px;
  bottom: calc(18px + var(--ion-safe-area-bottom));
  z-index: 20;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.action-stack {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.main-btn,
.action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(145deg, rgb(255 243 234 / 0.92), rgb(255 228 210 / 0.92));
  color: #c76735;
  box-shadow: 0 10px 24px rgb(152 88 47 / 0.18);
  backdrop-filter: blur(10px);
}

.main-btn {
  width: 52px;
  height: 52px;
  font-size: 22px;
  opacity: 0.58;
  transition:
    transform 0.2s ease,
    background-color 0.2s ease,
    opacity 0.2s ease;
}

.main-btn.expanded {
  transform: rotate(90deg);
  opacity: 1;
}

.action-btn {
  width: 38px;
  height: 38px;
  font-size: 18px;
  opacity: 0.96;
}

.main-btn:active,
.action-btn:active {
  transform: scale(0.94);
}

.main-btn:hover {
  opacity: 0.82;
}

.main-btn.expanded:active {
  transform: rotate(90deg) scale(0.94);
}

.fab-action-enter-active,
.fab-action-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}

.fab-action-enter-from,
.fab-action-leave-to {
  opacity: 0;
  transform: translateY(16px) scale(0.84);
}
</style>
