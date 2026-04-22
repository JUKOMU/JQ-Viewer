<template>
  <div class="search-content">
    <div v-if="props.modeSelect" class="toolbar-row">
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

      <button class="help-btn">
        <IonIcon :icon="helpCircleOutline"/>
      </button>
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
    <div v-if="props.filterSelect" class="filter-panel">
      <div class="filter-toolbar">
        <div class="filter-title">
          <IonIcon :icon="filterOutline"/>
          <span>筛选</span>
          <span v-if="selectedTags.length" class="selected-count">
            已选择{{ selectedTags.length }}个
          </span>
        </div>

        <button type="button" class="filter-action-btn" @click="reverseSelectTags">
          <IonIcon :icon="swapHorizontalOutline"/>
        </button>

        <button type="button" class="filter-action-btn" @click="clearSelectedTags">
          <IonIcon :icon="closeOutline"/>
        </button>

        <button
            v-if="categoryList.length"
            type="button"
            class="filter-action-btn"
            @click="filterExpanded = !filterExpanded"
        >
          <IonIcon
              :icon="chevronDownOutline"
              class="expand-icon"
              :class="{ expanded: filterExpanded }"
          />
        </button>
      </div>

      <div class="filter-tags" :class="{ expanded: filterExpanded }">
        <button
            v-for="tag in categoryList"
            :key="tag.value"
            type="button"
            class="tag-chip"
            :class="{ active: selectedTags.includes(tag.value) }"
            @click="toggleTag(tag.value)"
        >
          {{ tag.description }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {IonIcon} from '@ionic/vue'
import {
  addOutline,
  chevronDownOutline,
  closeOutline,
  filterOutline,
  helpCircleOutline,
  swapHorizontalOutline
} from 'ionicons/icons';
import {CategoryItem} from "@/services/JmcomicTypes";
import {JmcomicService} from "@/services/JmcomicService";

const props = withDefaults(defineProps<{
  modeSelect?: boolean
  filterSelect?: boolean
}>(), {
  modeSelect: true,
  filterSelect: true,
})

const mode = ref('single-mode')
const filterExpanded = ref(false)
const selectedTags = ref<string[]>([])

const categoryList = ref<CategoryItem[]>([])

const toggleTag = (tag: string) => {
  if (selectedTags.value.includes(tag)) {
    selectedTags.value = selectedTags.value.filter(item => item !== tag)
    return
  }
  selectedTags.value = [...selectedTags.value, tag]
}

const reverseSelectTags = () => {
  selectedTags.value = categoryList.value
      .map(item => item.value)
      .filter(value => !selectedTags.value.includes(value))
}

const clearSelectedTags = () => {
  selectedTags.value = []
}

onMounted(async () => {
  const result = await JmcomicService.getCategoryList()
  categoryList.value = result.categoryList.filter(item => item.value !== '0')
})
</script>

<style scoped>
.search-content {
  max-width: 1000px;
  margin: 0 auto;
}

ion-searchbar.custom {
  --border-radius: 20px;
  --background: rgb(255 242 233);
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

.help-btn {
  width: 20px;
  height: 20px;
  background: #fff;
  border-radius: 999px;
  font-size: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.help-btn:active {
  transform: scale(0.92);
  background: #fff2ea;
  box-shadow: 0 0 0 3px rgba(250, 156, 105, 0.12);
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

.filter-panel {
  margin-top: 8px;
  padding: 0 16px;
}

.filter-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 32px;
  color: #666;
  font-size: 13px;
}

.filter-title {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-right: auto;
  color: #444;
}

.filter-action-btn,
.expand-btn,
.tag-chip {
  border: 0;
  background: transparent;
  padding: 0;
}

.filter-action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 999px;
  color: rgb(237 123 62);
}

.filter-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  overflow: hidden;
  flex-wrap: nowrap;
}

.filter-tags.expanded {
  flex-wrap: wrap;
}

.tag-chip {
  height: 28px;
  padding: 0 12px;
  border: 1px solid #d8d8d8;
  border-radius: 999px;
  background: #fff;
  color: #555;
  font-size: 12px;
  white-space: nowrap;
}

.tag-chip.active {
  border-color: rgb(250, 156, 105);
  background: #fff3ec;
  color: rgb(237 123 62);
}

.selected-count {
  color: rgb(237 123 62);
  font-size: 12px;
  margin-left: 4px;
}

.expand-icon {
  transition: transform 0.25s ease;
}

.expand-icon.expanded {
  transform: rotate(180deg);
}
</style>
