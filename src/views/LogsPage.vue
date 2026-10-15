<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar class="toolbar desktop-page-content">
        <IonButtons slot="start">
          <IonBackButton default-href="/setting" />
        </IonButtons>
        <IonTitle class="toolbar-title">应用日志</IonTitle>
        <IonButtons slot="end">
          <IonButton
            :disabled="exporting"
            aria-label="导出日志"
            @click="openExportModal"
          >
            <IonIcon :icon="downloadOutline" aria-hidden="true" />
            <span>导出日志</span>
          </IonButton>
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
        <div v-if="exportMessage" class="logs-state logs-export-message" role="status">
          {{ exportMessage }}
        </div>
        <div v-if="errorMessage" class="logs-state logs-error" role="alert">
          {{ errorMessage }}
        </div>
        <div v-if="loading && !content && !errorMessage" class="logs-state">正在读取日志...</div>
        <pre v-if="content" ref="logOutputRef" class="log-output">{{ content }}</pre>
        <div v-if="!loading && !content && !errorMessage" class="logs-state">当前没有日志内容</div>
      </div>
    </IonContent>
    <Teleport to="body">
      <div v-if="exportModalOpen" class="sheet-backdrop" @click.self="exportModalOpen = false">
        <div class="sheet-panel">
          <div class="log-export-dialog">
            <header class="log-export-header">
              <h2>导出日志</h2>
              <button
                class="sheet-close"
                type="button"
                aria-label="关闭日志导出"
                @click="exportModalOpen = false"
              >
                <IonIcon :icon="closeOutline" aria-hidden="true" />
              </button>
            </header>
            <div v-if="!loadingFiles && logFiles.length" class="log-selection-header">
              <span>选择日志（{{ selectedFileNames.size }}/{{ logFiles.length }}）</span>
              <button type="button" class="select-all" @click="toggleAllFiles">
                {{ selectedFileNames.size === logFiles.length ? '取消全选' : '全选' }}
              </button>
            </div>
            <div class="log-files" aria-label="日志文件列表">
              <div v-if="loadingFiles" class="logs-state">正在读取日志文件...</div>
              <template v-else-if="logFiles.length">
                <label v-for="file in logFiles" :key="file.fileName" class="log-file-row">
                  <input
                    type="checkbox"
                    :checked="selectedFileNames.has(file.fileName)"
                    :aria-label="`选择 ${file.fileName}`"
                    @change="toggleFile(file.fileName)"
                  />
                  <span class="log-file-info">
                    <span class="log-file-name">{{ file.fileName }}</span>
                    <span class="log-file-meta">
                      {{ formatBytes(file.sizeBytes) }}<template v-if="file.current"> · 当前日志</template>
                    </span>
                  </span>
                </label>
              </template>
              <div v-else class="logs-state">当前没有可导出的日志文件</div>
            </div>
            <footer class="log-export-footer">
              <button
                class="btn-cancel"
                type="button"
                :disabled="exporting"
                @click="exportModalOpen = false"
              >
                取消
              </button>
              <button
                class="btn-confirm"
                type="button"
                :disabled="exporting || loadingFiles || selectedFileNames.size === 0"
                aria-label="导出选中日志"
                @click="exportSelectedLogs"
              >
                {{ exporting ? '导出中...' : '导出选中日志' }}
              </button>
            </footer>
          </div>
        </div>
      </div>
    </Teleport>
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
import { closeOutline, downloadOutline, refreshOutline } from 'ionicons/icons'
import { getRuntime } from '@/runtime/runtimeContext'

const runtime = getRuntime()
const logOutputRef = ref<HTMLElement | null>(null)
const content = ref('')
const fileName = ref('')
const updatedAt = ref(0)
const loading = ref(false)
const errorMessage = ref('')
const exportMessage = ref('')
const exporting = ref(false)
const exportModalOpen = ref(false)
const loadingFiles = ref(false)
const logFiles = ref<Array<{ fileName: string; updatedAt: number; sizeBytes: number; current: boolean }>>([])
const selectedFileNames = ref<Set<string>>(new Set())
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

async function loadLogFiles() {
  if (!logs.value.available || loadingFiles.value) return
  loadingFiles.value = true
  try {
    logFiles.value = await logs.value.api.listFiles()
    selectedFileNames.value = new Set(
      [...selectedFileNames.value].filter((name) => logFiles.value.some((file) => file.fileName === name)),
    )
  } finally {
    loadingFiles.value = false
  }
}

function formatBytes(sizeBytes: number) {
  if (sizeBytes < 1024) return `${sizeBytes} B`
  if (sizeBytes < 1024 * 1024) return `${(sizeBytes / 1024).toFixed(1)} KB`
  return `${(sizeBytes / (1024 * 1024)).toFixed(1)} MB`
}

function toggleFile(fileName: string) {
  const next = new Set(selectedFileNames.value)
  if (next.has(fileName)) next.delete(fileName)
  else next.add(fileName)
  selectedFileNames.value = next
}

function toggleAllFiles() {
  selectedFileNames.value =
    selectedFileNames.value.size === logFiles.value.length
      ? new Set()
      : new Set(logFiles.value.map((file) => file.fileName))
}

async function openExportModal() {
  if (exporting.value || !logs.value.available) return
  exportMessage.value = ''
  exportModalOpen.value = true
  if (!logFiles.value.length) {
    try {
      await loadLogFiles()
    } catch (error) {
      errorMessage.value = error instanceof Error ? error.message : '读取日志文件失败'
    }
  }
}

async function exportSelectedLogs() {
  if (exporting.value || selectedFileNames.value.size === 0 || !logs.value.available) return
  exporting.value = true
  exportMessage.value = ''
  try {
    const folder = await runtime.services.files.pickFolder('logs')
    if (!folder) return
    const result = await logs.value.api.exportFiles({
      folder: folder.ref,
      fileNames: [...selectedFileNames.value],
    })
    const parts: string[] = []
    if (result.exported.length) parts.push(`已导出 ${result.exported.length} 个`)
    if (result.skipped.length) parts.push(`跳过 ${result.skipped.length} 个（目标目录已是日志目录）`)
    if (result.failed.length) {
      const failedNames = result.failed.map((item) => item.fileName).join('、')
      parts.push(`失败 ${result.failed.length} 个：${failedNames}`)
    }
    exportMessage.value = `${parts.join('；') || '没有需要导出的日志文件'}${
      result.exported.length ? `，目标目录：${folder.displayPath}` : ''
    }`
    exportModalOpen.value = false
  } catch (error) {
    exportMessage.value = error instanceof Error ? error.message : '导出日志失败'
  } finally {
    exporting.value = false
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

.log-files {
  margin-bottom: 12px;
  border: 1px solid #f0e2d9;
  border-radius: 12px;
  background: #fff;
}

.log-file-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 42px;
  padding: 0 12px;
}

.log-selection-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
  color: #8c6b5a;
  font-size: 12px;
}

.select-all {
  border: 0;
  background: transparent;
  color: #a45d37;
  cursor: pointer;
  font: inherit;
}

.log-file-row + .log-file-row {
  border-top: 1px solid #f5ece7;
}

.log-file-info {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 1px;
}

.log-file-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #4c2a18;
  font-size: 14px;
}

.log-file-meta {
  color: #b89a84;
  font: 11px ui-monospace, SFMono-Regular, Consolas, monospace;
}

.logs-export-message {
  margin-bottom: 12px;
  padding: 12px 16px;
  text-align: left;
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

.log-export-dialog {
  display: flex;
  box-sizing: border-box;
  width: 100%;
  flex-direction: column;
  padding: 0 18px;
  background: linear-gradient(180deg, #fffaf6 0%, #fff3eb 100%);
}

.sheet-backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  background: rgb(16 12 10 / 0.28);
  backdrop-filter: blur(2px);
}

.sheet-panel {
  display: flex;
  width: 100%;
  max-width: 480px;
  max-height: min(85vh, 720px);
  flex-direction: column;
  padding-bottom: var(--ion-safe-area-bottom);
  overflow: hidden;
  border-radius: 20px 20px 0 0;
  background: linear-gradient(180deg, #fffaf6 0%, #fff3eb 100%);
  box-shadow: 0 -8px 36px rgb(76 42 24 / 0.16);
  animation: slideUp 0.25s ease-out;
}

@keyframes slideUp {
  from {
    transform: translateY(100%);
  }

  to {
    transform: translateY(0);
  }
}

.log-export-header,
.log-export-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.log-export-header {
  flex-shrink: 0;
  padding-bottom: 10px;
}

.log-export-header h2 {
  margin: 0;
  color: #4c2a18;
  font-size: 17px;
  font-weight: 600;
}

.sheet-close {
  display: flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  margin: -4px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: #b89a84;
  cursor: pointer;
  font-size: 22px;
}

.sheet-close:active {
  background: #f0d8c8;
}

.log-export-dialog .log-files {
  max-height: min(40vh, 280px);
  overflow: auto;
  border-color: #f0d8c8;
  border-radius: 10px;
  background: #fefcf9;
}

@media (min-width: 1264px) {
  .sheet-panel {
    max-width: min(960px, calc(100% - clamp(150px, 15cqw, 300px) - 24px));
  }
}

.log-export-dialog .log-selection-header {
  margin-bottom: 6px;
  color: #b89a84;
}

.log-export-dialog .log-file-row {
  min-height: 0;
  padding: 10px 14px;
  cursor: pointer;
  user-select: none;
}

.log-export-dialog .log-file-row + .log-file-row {
  border-top-color: #f5ebe4;
}

.log-export-dialog .log-file-row:active {
  background: #fdf5ef;
}

.log-export-dialog .log-file-row input {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  accent-color: #bf8350;
}

.log-export-footer {
  flex-shrink: 0;
  justify-content: stretch;
  padding-top: 0;
}

.log-export-footer button {
  flex: 1;
  height: 42px;
  border: 0;
  border-radius: 10px;
  cursor: pointer;
  font-size: 15px;
  font-weight: 500;
  transition: opacity 0.15s;
}

.log-export-footer button:disabled {
  cursor: default;
  opacity: 0.5;
}

.log-export-footer .btn-cancel {
  background: #f0e4db;
  color: #8a6048;
}

.log-export-footer .btn-confirm {
  background: #e8843c;
  color: #fff;
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
