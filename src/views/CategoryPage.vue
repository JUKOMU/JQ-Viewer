<template>
  <IonPage>
    <IonContent ref="contentRef">
      <Transition name="category-overlay">
        <div v-if="categoryOverlayVisible" class="category-overlay" @click.self="closeSearchOverlay">
          <div class="category-overlay-panel">
            <CategorySearchToolbar
                @search="submitOverlayCategory"
            />
          </div>
        </div>
      </Transition>

      <div class="category-page-top">
        <div class="category-page-toolbar">
          <MenuToggleButton/>
          <div class="toolbar-category">
            <CategorySearchToolbar
                @search="handleSearch"
            />
          </div>
        </div>
      </div>
      <SearchResultContainer
          :result="result"
          :loading="loading"
          :error-message="errorMessage"
          :mode="displayMode"
          idle-text="分类搜索结果将在这里显示"
          @mode-change="displayMode = $event"
          @retry="retrySearch"
      />
      <QuickActionFab
          v-if="result"
          slot="fixed"
          @search="openSearch"
          @jump="jumpToPage"
          @top="scrollToTop"
          @back="clearResult"
      />
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import {ref} from 'vue'
import {alertController, IonContent, IonPage} from "@ionic/vue";
import CategorySearchToolbar from "@/components/search/CategorySearchToolbar.vue";
import MenuToggleButton from "@/components/common/MenuToggleButton.vue";
import QuickActionFab from "@/components/common/QuickActionFab.vue";
import SearchResultContainer from "@/components/search/SearchResultContainer.vue";
import {JmcomicService} from "@/services/JmcomicService";
import type {SearchQuery, SearchResult} from "@/services/JmcomicTypes";

const result = ref<SearchResult | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const displayMode = ref<'list' | 'grid'>('grid')
const lastQuery = ref<SearchQuery | null>(null)
const contentRef = ref<InstanceType<typeof IonContent> | null>(null)
const categoryOverlayVisible = ref(false)

const handleSearch = async (query: SearchQuery) => {
  lastQuery.value = {...query, page: query.page ?? 1}
  loading.value = true
  errorMessage.value = ''
  try {
    result.value = await JmcomicService.categories(lastQuery.value)
  } catch (error) {
    result.value = null
    errorMessage.value = error instanceof Error ? error.message : '搜索失败'
  } finally {
    loading.value = false
  }
}

const retrySearch = () => {
  if (lastQuery.value) {
    void handleSearch(lastQuery.value)
  }
}

const openSearch = () => {
  categoryOverlayVisible.value = true
}

const closeSearchOverlay = () => {
  categoryOverlayVisible.value = false
}

const submitOverlayCategory = async (query: SearchQuery) => {
  closeSearchOverlay()
  void handleSearch(query)
}

const scrollToTop = () => {
  void contentRef.value?.$el?.scrollToTop?.(300)
}

const clearResult = () => {
  result.value = null
  errorMessage.value = ''
}

const jumpToPage = async () => {
  if (!result.value || !lastQuery.value) {
    return
  }

  const alert = await alertController.create({
    header: '跳转页码',
    message: `请输入 1 - ${result.value.totalPages} 的页码`,
    inputs: [{
      name: 'page',
      type: 'number',
      min: 1,
      max: result.value.totalPages,
      value: String(result.value.currentPage),
      placeholder: '页码',
    }],
    buttons: [
      {text: '取消', role: 'cancel'},
      {
        text: '跳转',
        handler: (data: { page?: string }) => {
          const page = Number(data.page)
          if (!Number.isInteger(page) || page < 1 || page > result.value!.totalPages) {
            return false
          }
          void handleSearch({...lastQuery.value!, page})
          return true
        },
      },
    ],
  })

  await alert.present()
}
</script>

<style scoped>
.toolbar-start {
  padding: 0 0 8px 14px;
}

:deep(ion-header),
:deep(ion-toolbar) {
  overflow: visible;
}

:deep(ion-toolbar) {
  --min-height: auto;
}

.category-page-top {
  padding: calc(var(--ion-safe-area-top) + 2px) 14px 0;
}

.category-overlay {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: calc(10px + var(--ion-safe-area-top)) 14px 14px;
  background: rgb(16 12 10 / 0.16);
}

.category-overlay-panel {
  width: min(100%, 920px);
}

.category-page-toolbar {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  align-items: start;
  gap: 5px;
}

.toolbar-category {
  min-width: 0;
}

.category-overlay-enter-active,
.category-overlay-leave-active {
  transition: opacity 0.18s ease;
}

.category-overlay-enter-active .category-overlay-panel,
.category-overlay-leave-active .category-overlay-panel {
  transition: transform 0.22s ease, opacity 0.22s ease;
}

.category-overlay-enter-from,
.category-overlay-leave-to {
  opacity: 0;
}

.category-overlay-enter-from .category-overlay-panel,
.category-overlay-leave-to .category-overlay-panel {
  opacity: 0;
  transform: translateY(-14px);
}
</style>
