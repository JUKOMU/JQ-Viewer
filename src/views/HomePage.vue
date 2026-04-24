<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <div class="toolbar-start">
          <MenuToggleButton/>
        </div>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <div class="home-content">
        <KeywordSearchBar class="search-bar" @search="handleSearch"/>
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import {IonContent, IonHeader, IonPage, IonToolbar} from "@ionic/vue";
import {useRouter} from 'vue-router'
import MenuToggleButton from "@/components/common/MenuToggleButton.vue";
import KeywordSearchBar from "@/components/search/KeywordSearchBar.vue";
import type {SearchQuery} from "@/services/JmcomicTypes";

const router = useRouter()

const handleSearch = (query: SearchQuery) => {
  void router.push({
    path: '/search',
    query: {
      keyword: query.keyword ?? '',
      orderBy: query.orderBy,
      time: query.time,
      searchMainTag: String(query.searchMainTag),
      page: '1',
    },
  })
}
</script>

<style scoped>
.toolbar-start {
  padding: 0 0 8px 14px;
}

.home-content {
  padding-top: 22vh;
}

:deep(ion-header),
:deep(ion-toolbar),
:deep(button){
  overflow: visible;
}

:deep(ion-toolbar) {
  --min-height: auto;
}
</style>
