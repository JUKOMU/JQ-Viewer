<template>
  <div class="search-content">
    <div v-if="props.modeSelect" class="toolbar-row">
      <div class="upload-slot">
        <Transition name="upload-slide">
          <button
            v-if="mode === 'batch-mode' && canUseOcr"
            type="button"
            class="upload-btn"
            @click="handleUpload"
          >
            <IonIcon :icon="addOutline"/>
          </button>
        </Transition>
      </div>

      <button class="help-btn" @click="showHelp">
        <IonIcon :icon="helpCircleOutline"/>
      </button>
      <div class="mode-switch">
        <button
          type="button"
          class="mode-btn"
          :class="{ active: mode === 'single-mode' }"
          @click="mode = mode === 'single-mode' ? '' : 'single-mode'"
        >
          单个解析
        </button>
        <button
          type="button"
          class="mode-btn"
          :class="{ active: mode === 'batch-mode' }"
          @click="mode = mode === 'batch-mode' ? '' : 'batch-mode'"
        >
          批量解析
        </button>
      </div>
    </div>
    <div class="searchbar-wrap" style="position: relative">
      <div class="searchbar-box">
        <IonSearchbar
          v-if="mode !== 'batch-mode'"
          ref="searchbarRef"
          v-model="query.keyword"
          :animated="true"
          show-clear-button="always"
          class="custom"
          placeholder="请输入关键词"
          enterkeyhint="search"
        />
        <textarea
          v-else
          ref="batchTextareaRef"
          v-model="query.keyword"
          class="batch-textarea"
          placeholder="输入或粘贴多行文本提取 ID"
          rows="3"
          spellcheck="false"
        />
      </div>
      <button type="button" class="search-trigger-btn" @click="emitSearch">
        <IonIcon :icon="searchOutline"/>
      </button>
      <SearchHistoryDropdown
        v-if="mode !== 'batch-mode'"
        :visible="showHistory"
        :items="HistoryService.getSearchHistory('keyword-search')"
        :filter-text="query.keyword"
        @select="onHistorySelect"
        @clear="onHistoryClear"
      />
    </div>

    <button type="button" class="more-toggle-btn" @click="expanded = !expanded">
      <span>更多选项</span>
      <IonIcon :icon="chevronDownOutline" class="expand-icon" :class="{ expanded }"/>
    </button>

    <div v-if="expanded" class="option-panel">
      <section class="option-section">
        <div class="option-title">排序</div>
        <div class="option-group">
          <button
            v-for="option in ORDER_BY_OPTIONS"
            :key="option.value"
            type="button"
            class="option-chip"
            :class="{ active: query.orderBy === option.value }"
            @click="query.orderBy = option.value"
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
            :class="{ active: query.time === option.value }"
            @click="query.time = option.value"
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
            :class="{ active: query.searchMainTag === option.value }"
            @click="query.searchMainTag = option.value"
          >
            {{ option.label }}
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({name: 'KeywordSearchBar'})

const props = withDefaults(
  defineProps<{
    modeSelect?: boolean
  }>(),
  {
    modeSelect: true,
  },
)
const emit = defineEmits<{
  search: [query: SearchQuery]
}>()
import {computed, onBeforeUnmount, onMounted, reactive, ref} from 'vue'
import {useRouter} from 'vue-router'
import {alertController, IonIcon, IonSearchbar} from '@ionic/vue'
import {addOutline, chevronDownOutline, helpCircleOutline, searchOutline} from 'ionicons/icons'
import {ORDER_BY_OPTIONS, SEARCH_MAIN_TAG_OPTIONS, TIME_OPTIONS} from '@/constants/searchOptions'
import type {SearchQuery} from '@/services/JmcomicTypes'
import {JmcomicService, sanitizeError, showToast} from '@/services/JmcomicService'
import type {SearchHistoryItem} from '@/services/HistoryService'
import {HistoryService} from '@/services/HistoryService'
import {SettingsStore} from '@/services/SettingsService'
import SearchHistoryDropdown from '@/components/history/SearchHistoryDropdown.vue'
import {platformCapabilities} from '@/platform/activeCapabilities'

const router = useRouter()

const mode = ref('')
const showHistory = ref(false)
let blurTimer: ReturnType<typeof setTimeout> | null = null

const expanded = ref(false)
const searchbarRef = ref<InstanceType<typeof IonSearchbar> | null>(null)
let nativeInput: HTMLInputElement | null = null

const query = reactive<SearchQuery>({
  keyword: '',
  orderBy: 'mr',
  time: 'a',
  searchMainTag: 0,
})

const emitSearch = () => {
  const originalKeyword = (query.keyword ?? '').trim()

  // 解析模式 → 记录解析历史（异步 fire-and-forget）
  if (mode.value === 'single-mode' || mode.value === 'batch-mode') {
    if (originalKeyword) HistoryService.addParseHistory(originalKeyword, mode.value)
  }

  // 记录搜索历史（仅普通搜索模式，解析模式不走搜索历史）
  if (originalKeyword && mode.value !== 'single-mode' && mode.value !== 'batch-mode') {
    HistoryService.addSearchHistory('keyword-search', originalKeyword)
  }

  if (mode.value === 'batch-mode') {
    if (originalKeyword) {
      const bpKey = `bp-${Date.now()}`
      sessionStorage.setItem(`batch-parse-text:${bpKey}`, originalKeyword)
      router.push({path: '/batch-parse', query: {key: bpKey}})
    }
    return
  }

  const parsed = {...query}
  if (mode.value === 'single-mode') {
    parsed.keyword = (query.keyword ?? '').replace(/\D/g, '')
  }
  emit('search', parsed)
}

const ocrEnabled = ref(SettingsStore.getOcrEnabled())
const canUseOcr = computed(() => ocrEnabled.value && platformCapabilities.support.ocr)

async function handleUpload() {
  try {
    const result = await JmcomicService.pickImageAndOcr()
    await showToast('正在识别图片...', 'medium')
    if (result.text) {
      const current = (query.keyword ?? '').trim()
      query.keyword = current ? current + '\n' + result.text : result.text
      await showToast('识别完成', 'success')
      emitSearch()
    } else if (result.error) {
      await showToast(sanitizeError(result.error, '识别失败'), 'danger')
    }
    // 用户取消选择图片（text 为空且无 error）→ 不提示
  } catch (e: any) {
    await showToast(sanitizeError(e, '识别失败'), 'danger')
  }
}

async function showHelp() {
  const alert = await alertController.create({
    header: '使用帮助',
    message: [
      '【单个解析】',
      '输入数字 ID 或包含数字的文本，自动提取纯数字部分搜索对应漫画。',
      '',
      '【批量解析】',
      '粘贴多行文本，自动从每行提取 ID，跳转到批量处理页面。',
      platformCapabilities.support.ocr
        ? '点击左侧 + 按钮可选择图片进行 OCR 识别。'
        : '桌面版暂不支持图片 OCR，请直接输入或粘贴文本。',
      '',
      '【搜索选项】',
      '排序：最新 / 最多观看 / 图片最多 / 最多喜欢',
      '时间范围：今日 / 本周 / 本月 / 全部时间',
      '搜索方式：站内搜索 / 作品 / 作者 / 标签 / 登场人物',
    ].join('\n'),
    buttons: [
      { text: '知道了', role: 'cancel' },
    ],
  })
  await alert.present()
}

const handleNativeKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Enter') {
    emitSearch()
  }
}

// --- 搜索历史下拉 ---

const handleNativeFocus = () => {
  showHistory.value = true
}

const handleNativeBlur = () => {
  if (blurTimer) clearTimeout(blurTimer)
  blurTimer = setTimeout(() => {
    showHistory.value = false
  }, 200)
}

const handleNativeInput = () => {
  showHistory.value = true
}

const onHistorySelect = (item: SearchHistoryItem) => {
  query.keyword = item.keyword
  showHistory.value = false
  if (blurTimer) {
    clearTimeout(blurTimer)
    blurTimer = null
  }
}

const onHistoryClear = () => {
  HistoryService.clearSearchHistory('keyword-search')
  showHistory.value = false
}

onMounted(async () => {
  nativeInput = (await searchbarRef.value?.$el?.getInputElement?.()) ?? null
  nativeInput?.addEventListener('keydown', handleNativeKeydown)
  nativeInput?.addEventListener('focus', handleNativeFocus)
  nativeInput?.addEventListener('blur', handleNativeBlur)
  nativeInput?.addEventListener('input', handleNativeInput)
})

onBeforeUnmount(() => {
  nativeInput?.removeEventListener('keydown', handleNativeKeydown)
  nativeInput?.removeEventListener('focus', handleNativeFocus)
  nativeInput?.removeEventListener('blur', handleNativeBlur)
  nativeInput?.removeEventListener('input', handleNativeInput)
  if (blurTimer) clearTimeout(blurTimer)
})
</script>

<style scoped>
.search-content {
  max-width: 1000px;
  margin: 0 auto;
  padding: 10px 16px;
  border-radius: 30px;
}

.searchbar-wrap {
  display: flex;
  align-items: flex-start;
  gap: 0;
  padding: 4px 4px 4px 10px;
  background: #fffbf8;
  border: 1px solid rgb(250 156 105 / 0.3);
  border-radius: 22px;
  box-shadow: 0 4px 6px 0 rgb(62 39 26 / 0.2);
}

.searchbar-box {
  flex: 1;
  min-width: 0;
  padding-top: 4px;
}

.batch-textarea {
  display: block;
  width: 100%;
  min-height: 56px;
  padding: 6px 8px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: #30201a;
  font-size: 14px;
  font-family: inherit;
  line-height: 1.5;
  resize: vertical;
  outline: none;
  box-sizing: border-box;
}

.batch-textarea::placeholder {
  color: #a0a0a0;
}

ion-searchbar.custom {
  --border-radius: 18px;
  --background: transparent;
  --box-shadow: none;
  padding: 0;
}

.more-toggle-btn,
.option-chip {
  border: 0;
}

.search-trigger-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 38px;
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: 18px;
  background: rgb(250, 156, 105);
  color: #fff;
  font-size: 16px;
}

.search-trigger-btn:active {
  transform: scale(0.96);
}

:deep(.custom .searchbar-search-icon) {
  display: none !important;
}

:deep(.custom .searchbar-input) {
  padding-inline-start: 4px !important;
}

.more-toggle-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  padding: 0 16px;
  background: transparent;
  color: rgb(237 123 62);
  font-size: 12px;
}

.expand-icon {
  transition: transform 0.25s ease;
}

.expand-icon.expanded {
  transform: rotate(180deg);
}

.option-panel {
  margin-top: 12px;
  padding: 10px 16px 15px 16px;
  border-radius: 16px;
  background: rgb(255 235 223 / 0.6);
  box-shadow: 0 4px 6px 0 rgb(62 39 26 / 0.2);
}

.option-section + .option-section {
  margin-top: 14px;
}

.option-title {
  margin-bottom: 8px;
  color: #555;
  font-size: 10px;
}

.option-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.option-chip {
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  background: #fff;
  color: #555;
  font-size: 10px;
  border: 1px solid rgb(250, 156, 105);
  transition: transform 0.15s ease-out,
  background-color 0.15s ease,
  box-shadow 0.15s ease;
}

.option-chip.active {
  transform: scale(0.97);
  background: rgb(250, 156, 105);
  color: #fff;
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
  transition: transform 0.15s ease-out,
  background-color 0.15s ease,
  box-shadow 0.15s ease;
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
  transition: opacity 0.25s ease,
  transform 0.25s ease;
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
  transition: background-color 0.4s ease;
}

.mode-btn.active {
  background: rgb(250, 156, 105);
  color: #fff;
}
</style>
