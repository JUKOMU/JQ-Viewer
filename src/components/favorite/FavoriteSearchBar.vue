<template>
  <div ref="rootRef" class="favorite-search">
    <div class="search-row">
      <IonSearchbar
          ref="searchbarRef"
          v-model="draft.keyword"
          :animated="true"
          class="compact-searchbar"
          show-clear-button="always"
          placeholder="搜索收藏夹..."
          enterkeyhint="search"
          @keydown.enter="submit"
      />
      <button type="button" class="submit-btn" :disabled="loading" @click="submit">
        <IonIcon :icon="searchOutline"/>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import {nextTick, onBeforeUnmount, onMounted, reactive, ref, watch} from 'vue'
import {IonIcon, IonSearchbar} from '@ionic/vue'
import {searchOutline} from 'ionicons/icons'
import type {FavoriteQuery} from '@/services/JmcomicTypes'

const props = defineProps<{
  query: FavoriteQuery
  loading: boolean
}>()

const emit = defineEmits<{
  search: [query: FavoriteQuery]
}>()

const rootRef = ref<HTMLElement | null>(null)
const searchbarRef = ref<InstanceType<typeof IonSearchbar> | null>(null)
const draft = reactive<FavoriteQuery>({
  folderId: '0',
  page: 1,
  keyword: '',
})

const syncDraft = (query: FavoriteQuery) => {
  Object.assign(draft, {
    folderId: query.folderId,
    page: query.page ?? 1,
    keyword: query.keyword ?? '',
  })
}

const submit = () => {
  emit('search', {...draft, page: 1})
}

const focusInput = async () => {
  await nextTick()
  await searchbarRef.value?.$el?.setFocus?.()
}

const handleDocumentClick = (event: MouseEvent) => {
  if (!rootRef.value?.contains(event.target as Node)) {
    searchbarRef.value?.$el?.getInputElement?.().then((input: HTMLInputElement) => input.blur()).catch(() => undefined)
  }
}

watch(() => props.query, syncDraft, {immediate: true, deep: true})

onMounted(() => {
  document.addEventListener('click', handleDocumentClick)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleDocumentClick)
})

defineExpose({focusInput})
</script>

<style scoped>
.favorite-search {
  width: 100%;
}

.submit-btn {
  border: 0;
  font: inherit;
}

.search-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 2px 0 8px;
  height: 40px;
  box-sizing: border-box;
  overflow: hidden;
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
</style>
