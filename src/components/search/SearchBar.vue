<template>
  <div class="search-content">
    <div class="toolbar-row">
      <div class="upload-slot">
        <Transition name="upload-slide">
          <button
              v-if="mode === 'batch-mode'"
              type="button"
              class="upload-btn"
          >
            <IonIcon :icon="addOutline"/>
          </button>
        </Transition>
      </div>

      <div class="mode-switch">
        <button
            type="button"
            class="mode-btn"
            :class="{ active: mode === 'single-mode' }"
            @click="mode = 'single-mode'"
        >
          单个解析
        </button>
        <button
            type="button"
            class="mode-btn"
            :class="{ active: mode === 'batch-mode' }"
            @click="mode = 'batch-mode'"
        >
          批量解析
        </button>
      </div>
    </div>
    <IonSearchbar animated="true" show-clear-button="always" class="custom"/>
  </div>
</template>

<script setup lang="ts">
import {ref} from 'vue'
import {IonIcon} from '@ionic/vue'
import {addOutline} from 'ionicons/icons';

defineProps({
  contentId: String,
});

const mode = ref('single-mode')

</script>

<style scoped>
.search-content {
  max-width: 1000px;
  margin: 0 auto;
}

ion-searchbar.custom {
  --border-radius: 20px;
}

.toolbar-row {
  position: relative;
  display: flex;
  justify-content: flex-end;
  padding: 0 16px;
  margin-bottom: 4px;
  min-height: 32px;
}

.upload-slot {
  position: absolute;
  left: 16px;
  top: 50%;
  transform: translateY(-50%);
}

.upload-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 28px;
  width: 28px;
  border: 1px solid rgb(250, 156, 105);
  background: #fff;
  color: rgb(237 123 62);
  border-radius: 999px;
  font-size: 18px;
  cursor: pointer;
  transition: transform 0.15s ease, background-color 0.15s ease, box-shadow 0.15s ease;
  -webkit-tap-highlight-color: transparent;
}

.upload-btn:active {
  transform: scale(0.92);
  background: #fff2ea;
  box-shadow: 0 0 0 3px rgba(250, 156, 105, 0.12);
}

.upload-btn:hover {
  background: #fff7f2;
}

.upload-slide-enter-active,
.upload-slide-leave-active {
  transition: opacity 0.25s ease, transform 0.25s ease;
}

.upload-slide-enter-from,
.upload-slide-leave-to {
  opacity: 0;
  transform: translateX(-12px) rotate(-90deg);
}

.upload-slide-enter-to,
.upload-slide-leave-from {
  opacity: 1;
  transform: translateX(0) rotate(0deg);
}

.mode-switch {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px;
  background: #fff;
  border: 1px solid rgb(250, 156, 105);
  border-radius: 999px;
}

.mode-btn {
  height: 28px;
  padding: 0 14px;
  border: 0;
  background: transparent;
  border-radius: 999px;
  font-size: 12px;
  color: #222;
}

.mode-btn.active {
  background: rgb(250, 156, 105);
  color: #fff;
}
</style>
