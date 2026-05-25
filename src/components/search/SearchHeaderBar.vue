<template>
  <div ref="rootRef" class="header-search">
    <div class="expanded-panel" style="position: relative;">
      <div class="search-row">
        <IonSearchbar
            ref="searchbarRef"
            v-model="draft.keyword"
            :animated="true"
            class="compact-searchbar"
            show-clear-button="always"
            placeholder="请输入关键词"
            enterkeyhint="search"
            @keydown.enter="submit"
        />
        <button type="button" class="submit-btn" :disabled="loading" @click="submit">
          <IonIcon :icon="searchOutline"/>
        </button>
      </div>
      <SearchHistoryDropdown
        :visible="showHistory"
        :items="HistoryService.getSearchHistory('search-page')"
        :filter-text="draft.keyword"
        @select="onHistorySelect"
        @clear="onHistoryClear"
      />
      <div class="options-panel">
        <section class="option-section">
          <div class="option-title">排序</div>
          <div class="option-group">
            <button
                v-for="option in ORDER_BY_OPTIONS"
                :key="option.value"
                type="button"
                class="option-chip"
                :class="{ active: draft.orderBy === option.value }"
                @click="draft.orderBy = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </section>
        <section class="option-section">
          <div class="option-title">时间范围</div>
          <div class="option-group">
            <button
                v-for="option in TIME_OPTIONS"
                :key="option.value"
                type="button"
                class="option-chip"
                :class="{ active: draft.time === option.value }"
                @click="draft.time = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </section>
        <section class="option-section">
          <div class="option-title">搜索方式</div>
          <div class="option-group">
            <button
                v-for="option in SEARCH_MAIN_TAG_OPTIONS"
                :key="option.value"
                type="button"
                class="option-chip"
                :class="{ active: draft.searchMainTag === option.value }"
                @click="draft.searchMainTag = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import {nextTick, onBeforeUnmount, onMounted, reactive, ref, watch} from 'vue'
import {IonIcon, IonSearchbar} from '@ionic/vue'
import {searchOutline} from 'ionicons/icons'
import {ORDER_BY_OPTIONS, SEARCH_MAIN_TAG_OPTIONS, TIME_OPTIONS} from '@/constants/searchOptions'
import type {SearchQuery} from '@/services/JmcomicTypes'
import { HistoryService } from '@/services/HistoryService'
import type { SearchHistoryItem } from '@/services/HistoryService'
import SearchHistoryDropdown from '@/components/history/SearchHistoryDropdown.vue'

const props = defineProps<{
  query: SearchQuery
  loading: boolean
}>()

const emit = defineEmits<{
  search: [query: SearchQuery]
}>()

const rootRef = ref<HTMLElement | null>(null)
const searchbarRef = ref<InstanceType<typeof IonSearchbar> | null>(null)
const showHistory = ref(false)
let blurTimer: ReturnType<typeof setTimeout> | null = null
let nativeInput: HTMLInputElement | null = null
const draft = reactive<SearchQuery>({
  keyword: '',
  orderBy: 'mr',
  time: 'a',
  searchMainTag: 0,
  page: 1,
})

const syncDraft = (query: SearchQuery) => {
  Object.assign(draft, {
    keyword: query.keyword ?? '',
    orderBy: query.orderBy,
    time: query.time,
    searchMainTag: query.searchMainTag,
    page: query.page ?? 1,
  })
}

const focusInput = async () => {
  await nextTick()
  await searchbarRef.value?.$el?.setFocus?.()
}

const submit = () => {
  const kw = (draft.keyword ?? '').trim()
  if (kw) HistoryService.addSearchHistory('search-page', kw)
  emit('search', {...draft, page: 1})
}

const handleDocumentClick = (event: MouseEvent) => {
  if (!rootRef.value?.contains(event.target as Node)) {
    searchbarRef.value?.$el?.getInputElement?.().then((input: HTMLInputElement) => input.blur()).catch(() => undefined)
  }
}

// --- 搜索历史下拉 ---

const handleNativeFocus = () => {
  showHistory.value = true
}

const handleNativeBlur = () => {
  if (blurTimer) clearTimeout(blurTimer)
  blurTimer = setTimeout(() => { showHistory.value = false }, 200)
}

const handleNativeInput = () => {
  showHistory.value = true
}

const onHistorySelect = (item: SearchHistoryItem) => {
  draft.keyword = item.keyword
  showHistory.value = false
  if (blurTimer) { clearTimeout(blurTimer); blurTimer = null }
}

const onHistoryClear = () => {
  HistoryService.clearSearchHistory('search-page')
  showHistory.value = false
}

watch(() => props.query, syncDraft, {immediate: true, deep: true})

onMounted(async () => {
  document.addEventListener('click', handleDocumentClick)
  nativeInput = await searchbarRef.value?.$el?.getInputElement?.() ?? null
  nativeInput?.addEventListener('focus', handleNativeFocus)
  nativeInput?.addEventListener('blur', handleNativeBlur)
  nativeInput?.addEventListener('input', handleNativeInput)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleDocumentClick)
  nativeInput?.removeEventListener('focus', handleNativeFocus)
  nativeInput?.removeEventListener('blur', handleNativeBlur)
  nativeInput?.removeEventListener('input', handleNativeInput)
  if (blurTimer) clearTimeout(blurTimer)
})

defineExpose({focusInput})
</script>

<style scoped>
.header-search {
  width: 100%;
}

.submit-btn,
.option-chip {
  border: 0;
  font: inherit;
}

.expanded-panel {
  padding: 8px;
  border-radius: 22px;
  background: #fffbf8;
  box-shadow: 0 8px 22px rgb(76 42 24 / 0.12);
}

.search-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 2px 2px 8px;
  border-radius: 18px;
  background: #fffbf8;
  border: 1px solid rgb(85 85 85 / 0.1);
}

ion-searchbar.compact-searchbar {
  --background: transparent;
  --box-shadow: none;
  --border-radius: 14px;
  min-height: 34px;
  padding: 0;
}

:deep(.compact-searchbar .searchbar-search-icon) {
  display: none !important;
}

:deep(.compact-searchbar .searchbar-input) {
  padding-inline-start: 4px !important;
  font-size: 13px !important;
}

.submit-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 36px;
  width: 36px;
  height: 36px;
  border-radius: 16px;
  background: rgb(250 156 105);
  color: #fff;
}

.submit-btn:disabled {
  opacity: 0.55;
}

.options-panel {
  padding: 12px 8px 4px;
}

.option-section + .option-section {
  margin-top: 12px;
}

.option-title {
  margin-bottom: 7px;
  color: #8a6048;
  font-size: 11px;
}

.option-group {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.option-chip {
  height: 28px;
  padding: 0 10px;
  border: 1px solid rgb(250 156 105 / 0.68);
  border-radius: 999px;
  background: #fff;
  color: #6f5141;
  font-size: 11px;
}

.option-chip.active {
  background: rgb(250 156 105);
  color: #fff;
}
</style>
