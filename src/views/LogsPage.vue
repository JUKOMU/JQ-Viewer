<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar class="toolbar desktop-page-content">
        <IonButtons slot="start">
          <IonBackButton default-href="/setting" />
        </IonButtons>
        <IonTitle class="toolbar-title">应用日志</IonTitle>
        <IonButtons slot="end">
          <IonButton :disabled="loading" aria-label="刷新日志" @click="loadLogs">
            <IonIcon :icon="refreshOutline" :class="{ spinning: loading }" aria-hidden="true" />
          </IonButton>
        </IonButtons>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <div class="logs-page desktop-page-content">
        <div class="logs-meta">
          <span>{{ fileName || '当前日志' }}</span>
          <span v-if="updatedAt">更新于 {{ formatTime(updatedAt) }}</span>
        </div>
        <div v-if="errorMessage" class="logs-state logs-error" role="alert">
          {{ errorMessage }}
        </div>
        <div v-if="loading && !content && !errorMessage" class="logs-state">正在读取日志...</div>
        <pre v-if="content" ref="logOutputRef" class="log-output">{{ content }}</pre>
        <div v-if="!loading && !content && !errorMessage" class="logs-state">当前没有日志内容</div>
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({ name: 'LogsPage' })

import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import {
  IonBackButton,
  IonButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonIcon,
  IonPage,
  IonTitle,
  IonToolbar,
} from '@ionic/vue'
import { refreshOutline } from 'ionicons/icons'
import { getRuntime } from '@/runtime/runtimeContext'

const runtime = getRuntime()
const logOutputRef = ref<HTMLElement | null>(null)
const content = ref('')
const fileName = ref('')
const updatedAt = ref(0)
const loading = ref(false)
const errorMessage = ref('')
let nextLine = 0
let nextOffset = 0
let hasCursor = false
let refreshTimer: ReturnType<typeof setInterval> | null = null
const MAX_LOG_LINES = 500

const logs = computed(() => runtime.services.logs)

function formatTime(timestamp: number) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(timestamp)
}

function keepRecentLines(value: string) {
  const hasTrailingNewline = value.endsWith('\n')
  const lines = hasTrailingNewline ? value.slice(0, -1).split('\n') : value.split('\n')
  if (lines.length <= MAX_LOG_LINES) return value
  return lines.slice(-MAX_LOG_LINES).join('\n') + (hasTrailingNewline ? '\n' : '')
}

async function loadLogs() {
  if (!logs.value.available || loading.value) return
  loading.value = true
  try {
    const wasNearBottom = isNearBottom()
    const snapshot = await logs.value.api.getCurrent({
      fromLine: hasCursor ? nextLine : 0,
      fromOffset: hasCursor ? nextOffset : 0,
    })
    errorMessage.value = ''
    if (!hasCursor || snapshot.reset) content.value = keepRecentLines(snapshot.content)
    else if (snapshot.content) content.value = keepRecentLines(content.value + snapshot.content)
    fileName.value = snapshot.fileName
    updatedAt.value = snapshot.updatedAt
    nextLine = snapshot.nextLine
    nextOffset = snapshot.nextOffset
    hasCursor = true
    await nextTick()
    if (wasNearBottom) scrollToBottom()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '读取日志失败'
  } finally {
    loading.value = false
  }
}

function isNearBottom() {
  const output = logOutputRef.value
  if (!output) return true
  return output.scrollHeight - output.scrollTop - output.clientHeight < 80
}

function scrollToBottom() {
  const output = logOutputRef.value
  if (output) output.scrollTop = output.scrollHeight
}

onMounted(() => {
  void loadLogs()
  refreshTimer = setInterval(() => void loadLogs(), 1000)
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})
</script>

<style scoped>
.toolbar {
  width: 100%;
  max-width: 920px;
  margin-inline: auto;
}

.toolbar-title {
  font-size: 16px;
  font-weight: 600;
  color: #4c2a18;
}

.logs-page {
  padding: 18px 20px 24px;
}

.logs-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  color: #8c6b5a;
  font-size: 12px;
}

.logs-state {
  padding: 28px 16px;
  border-radius: 12px;
  background: #fff;
  color: #8c6b5a;
  text-align: center;
}

.logs-error {
  color: #b04a45;
}

.log-output {
  box-sizing: border-box;
  max-height: calc(100vh - 150px);
  margin: 0;
  overflow: auto;
  padding: 14px;
  border: 1px solid #f0e2d9;
  border-radius: 12px;
  background: #211a17;
  color: #f6eee9;
  font:
    12px/1.6 ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

ion-button ion-icon {
  color: #8c6b5a;
}

.spinning {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
