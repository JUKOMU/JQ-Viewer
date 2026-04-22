<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonMenuButton :auto-hide="false"/>
        </IonButtons>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <CategorySearchToolbar @search="handleSearch"/>
      <div class="result-container">
        <template v-if="loading">
          正在搜索...
        </template>
        <template v-else-if="errorMessage">
          {{ errorMessage }}
        </template>
        <template v-else-if="result">
          <div class="result-summary">
            共 {{ result.totalItems }} 条，当前第 {{ result.currentPage }}/{{ result.totalPages }} 页
          </div>
          <div v-if="result.content.length" class="result-list">
            <article v-for="item in result.content" :key="item.id" class="result-item">
              <div class="result-title">{{ item.title }}</div>
              <div class="result-meta">ID: {{ item.id }}</div>
              <div v-if="item.authors.length" class="result-meta">
                作者：{{ item.authors.join(' / ') }}
              </div>
              <div v-if="item.tags.length" class="result-meta">
                标签：{{ item.tags.join(' / ') }}
              </div>
            </article>
          </div>
          <div v-else>
            没有搜索结果
          </div>
        </template>
        <template v-else>
          分类搜索结果将在这里显示
        </template>
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import {ref} from 'vue'
import {IonButtons, IonContent, IonHeader, IonMenuButton, IonPage, IonToolbar} from "@ionic/vue";
import CategorySearchToolbar from "@/components/search/CategorySearchToolbar.vue";
import {JmcomicService} from "@/services/JmcomicService";
import type {SearchQuery, SearchResult} from "@/services/JmcomicTypes";

const result = ref<SearchResult | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const handleSearch = async (query: SearchQuery) => {
  loading.value = true
  errorMessage.value = ''
  try {
    result.value = await JmcomicService.search(query)
  } catch (error) {
    result.value = null
    errorMessage.value = error instanceof Error ? error.message : '搜索失败'
  } finally {
    loading.value = false
  }
}

</script>

<style scoped>
.search-page-title {
  font-size: 30px;
  text-align: center;
  padding: 20px 0 30px 0;
  color: rgb(255 150 94);
}

.result-container {
  margin: 24px 16px 16px;
  min-height: 240px;
  border-radius: 18px;
  background: #fff7f2;
  color: #888;
  padding: 18px 16px;
  font-size: 14px;
}

.result-summary {
  color: #666;
  margin-bottom: 12px;
}

.result-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.result-item {
  padding: 14px;
  border-radius: 14px;
  background: #fff;
}

.result-title {
  color: #333;
  font-size: 15px;
  font-weight: 600;
}

.result-meta {
  margin-top: 6px;
  color: #777;
  font-size: 12px;
}
</style>
