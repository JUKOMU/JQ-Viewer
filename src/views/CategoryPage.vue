<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <div class="toolbar-start">
          <MenuToggleButton/>
        </div>
      </IonToolbar>
    </IonHeader>
    <IonContent ref="contentRef">
      <CategorySearchToolbar @search="handleSearch"/>
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
          @search="retrySearch"
          @jump="jumpToPage"
          @top="scrollToTop"
          @back="clearResult"
      />
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import {ref} from 'vue'
import {alertController, IonContent, IonHeader, IonPage, IonToolbar} from "@ionic/vue";
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
</style>
