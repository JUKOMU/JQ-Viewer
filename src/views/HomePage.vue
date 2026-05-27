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
        <div class="home-title" @click="reDisplay">
          <span class="title-text">{{ displayText }}</span>
          <span v-if="cursorVisible" class="title-cursor">|</span>
        </div>
        <KeywordSearchBar class="search-bar" @search="handleSearch"/>
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({name: 'HomePage'})

import {IonContent, IonHeader, IonPage, IonToolbar} from '@ionic/vue'
import {nextTick, onMounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import KeywordSearchBar from '@/components/search/KeywordSearchBar.vue'
import type {SearchQuery} from '@/services/JmcomicTypes'

const router = useRouter()

const TITLE = 'JQ Viewer'
const displayText = ref('')
const cursorVisible = ref(true)

onMounted(async () => {
  displayText.value = ''
  cursorVisible.value = true
  await nextTick()

  for (let i = 0; i <= TITLE.length; i++) {
    displayText.value = TITLE.slice(0, i)
    if (i < TITLE.length) {
      await new Promise(r => setTimeout(r, 100 + Math.random() * 150))
    }
  }

  for (let i = 0; i < 1; i++) {
    cursorVisible.value = false
    await new Promise(r => setTimeout(r, 300))
    cursorVisible.value = true
    await new Promise(r => setTimeout(r, 300))
  }
  cursorVisible.value = false
})

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

const reDisplay = async () => {
  displayText.value = ''
  cursorVisible.value = true
  await nextTick()

  for (let i = 0; i <= TITLE.length; i++) {
    displayText.value = TITLE.slice(0, i)
    if (i < TITLE.length) {
      await new Promise(r => setTimeout(r, 100 + Math.random() * 150))
    }
  }

  cursorVisible.value = true
  await new Promise(r => setTimeout(r, 200))
  cursorVisible.value = false
}
</script>

<style scoped>
.toolbar-start {
  padding: 0 0 8px 14px;
}

.home-content {
  padding-top: 16vh;
}

.home-title {
  text-align: center;
  margin-bottom: 40px;
  transition: transform 0.3s ease-out;
}

.title-text {
  font-size: 44px;
  font-weight: 700;
  color: #3a261d;
  letter-spacing: 2px;
  transition: transform 0.3s ease-out;
  margin-bottom: 20px;
}

.title-cursor {
  font-size: 42px;
  color: #e87c4b;
  font-weight: 400;
  animation: cursor-blink 0.7s step-end infinite;
}

@keyframes cursor-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

:deep(ion-header),
:deep(ion-toolbar),
:deep(button) {
  overflow: visible;
}

:deep(ion-toolbar) {
  --min-height: auto;
}
</style>
