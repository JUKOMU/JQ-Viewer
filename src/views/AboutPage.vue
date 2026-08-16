<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonBackButton default-href="/setting" />
        </IonButtons>
        <IonTitle class="toolbar-title">关于</IonTitle>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <div class="about-container">
        <!-- 应用信息 -->
        <div class="home-title" @click="reDisplay">
          <span class="title-text">{{ displayText }}</span>
          <span v-if="cursorVisible" class="title-cursor">|</span>
        </div>
        <div class="info-card">
          <div class="info-row">
            <span class="info-label">版本</span>
            <span class="info-value">{{ appVersion }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">作者</span>
            <span class="info-value">JUKOMU</span>
          </div>
        </div>

        <!-- 检查更新 -->
        <div
          class="info-card update-check-card"
          :style="updateCheckCardStyle"
        >
          <button
            class="info-row info-row-action"
            type="button"
            :disabled="updateCheckDisabled"
            @click="checkUpdate"
          >
            <span class="info-label">检查更新</span>
            <span class="info-action-value">
              <span v-if="isDownloading" class="info-value update">下载中</span>
              <span v-else-if="updateChecking" class="info-value">检查中...</span>
              <span v-else-if="updateError" class="info-value error">检查失败</span>
              <span v-else-if="hasUpdate" class="info-value update"
                >发现新版本 {{ latestVersion }}</span
              >
              <span v-else-if="updateChecked" class="info-value">已是最新</span>
              <span v-else class="info-value">点击检查</span>
              <IonSpinner
                v-if="updateChecking || isDownloading"
                name="circular"
                class="entry-spinner"
                aria-hidden="true"
              />
              <IonIcon
                v-else
                :icon="chevronForwardOutline"
                class="entry-arrow"
                aria-hidden="true"
              />
            </span>
          </button>
        </div>

        <div v-if="hasUpdate && latestManifest" class="info-card update-card">
          <div class="info-row">
            <span class="info-label">更新版本</span>
            <span class="info-value">{{ latestManifest.versionName }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">安装包大小</span>
            <span class="info-value">{{ formatMiB(latestManifest.sizeBytes) }}</span>
          </div>
          <div class="info-row update-progress-row">
            <span class="info-label">更新状态</span>
            <span class="info-value update-status-value">
              <span>{{ updateStatusLabel }}</span>
              <template v-if="downloadProgress">
                <span aria-hidden="true"> </span>
                <RollingNumber :value="downloadProgress.percent" :decimals="0" />
                <span aria-hidden="true">%</span>
              </template>
            </span>
          </div>
          <div v-if="downloadProgress" class="update-progress" aria-live="polite">
            <span class="update-progress-size">
              <RollingNumber :value="downloadProgress.bytesMiB" />
              <span aria-hidden="true"> / </span>
              <RollingNumber :value="downloadProgress.totalMiB" />
              <span> MiB</span>
            </span>
            <span class="update-progress-speed">
              <RollingNumber :value="downloadProgress.speedMiB" />
              <span> MiB/s</span>
            </span>
          </div>
          <div v-if="updateState.error" class="update-error">
            {{ updateState.error }}
          </div>
          <div v-if="latestManifest.releaseNotes" class="release-notes">
            <div class="note-title">发布说明</div>
            <!-- eslint-disable-next-line vue/no-v-html -- Markdown is sanitized before rendering. -->
            <div class="release-markdown" v-html="renderedReleaseNotes" />
          </div>
          <button
            class="update-action"
            type="button"
            :disabled="updateActionDisabled"
            @click="handleUpdateAction"
          >
            {{ updateActionLabel }}
          </button>
        </div>

        <!-- 仓库地址 -->
        <div class="info-card" @click="openRepo(REPO_URL)">
          <div class="info-row">
            <span class="info-label">仓库地址</span>
            <span class="info-value repo-url"
              ><a :href="REPO_URL" @click.prevent.stop="openRepo(REPO_URL)"
                ><IonIcon :icon="logoGithub" class="repo-icon" aria-hidden="true" />
                <span>JQ Viewer</span></a
              ></span
            >
          </div>
          <div class="info-row">
            <span class="info-label"></span>
            <span class="info-value repo-url"
              ><a :href="JMCOMIC_API_REPO_URL" @click.prevent.stop="openRepo(JMCOMIC_API_REPO_URL)"
                ><IonIcon :icon="logoGithub" class="repo-icon" aria-hidden="true" />
                <span>JMComic-Api-Java</span></a
              ></span
            >
          </div>
        </div>

        <div class="info-card author-note">
          <div class="note-title">关于这个应用</div>
          <div class="note-content">
            <p>全称叫 JMComic Quick Viewer, 当然快不快得看JM的服务器。</p>
            <p></p>
            <p>本来是因为批量解析的功能才开始的整个项目, 下次遇到发一串车牌号的可以方便点查看。</p>
            <p></p>
            <p>
              还有一个原因是本项目依赖的 JMComic API 库, 这是我的另一个开源项目,
              我想用来做点有用的东西。
            </p>
            <p></p>
            <p>
              如果你觉得好用，欢迎分享给朋友。遇到问题或有什么建议，可以在 GitHub 提交<a
                :href="`${REPO_URL}/issues/new`"
                >Issue</a
              >。
            </p>
            <div style="height: 1000px"></div>
            <p>没有了, 别看了</p>
            <div style="height: 2000px"></div>
            <p>还看?</p>
            <div style="height: 3000px"></div>
            <p><img src="../../public/000.jpg" /></p>
          </div>
        </div>
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({ name: 'AboutPage' })

import { computed, nextTick, onMounted, ref } from 'vue'
import { App } from '@capacitor/app'
import {
  IonBackButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonIcon,
  IonPage,
  IonSpinner,
  IonTitle,
  IonToolbar,
} from '@ionic/vue'
import { chevronForwardOutline, logoGithub } from 'ionicons/icons'
import { showToast } from '@/services/JmcomicService'
import { UpdateService } from '@/services/UpdateService'
import { presentUpdatePrompt } from '@/services/UpdatePromptService'
import RollingNumber from '@/components/update/RollingNumber.vue'
import type { UpdateManifest } from '@/services/JmcomicTypes'
import { renderReleaseNotesMarkdown } from '@/utils/releaseNotesMarkdown'

const appVersion = ref('1.0.0')
const updateChecking = ref(false)
const updateError = ref(false)
const latestVersion = ref('')
const updateChecked = ref(false)
const latestManifest = UpdateService.manifest
const updateState = UpdateService.state
const renderedReleaseNotes = computed(() => {
  const manifest = latestManifest.value
  return manifest ? renderReleaseNotesMarkdown(manifest.releaseNotes, manifest.versionName) : ''
})
const manualHasUpdate = ref(false)
const hasUpdate = computed(
  () =>
    manualHasUpdate.value ||
    (!!latestManifest.value &&
      updateState.value.phase !== 'idle' &&
      updateState.value.phase !== 'up_to_date'),
)

const isDownloading = computed(() => ['racing', 'selected'].includes(updateState.value.phase))
const updateCheckDisabled = computed(() =>
  updateChecking.value ||
  ['racing', 'selected', 'verifying', 'installing'].includes(updateState.value.phase),
)

const REPO_URL = 'https://github.com/jukomu/jq-viewer'
const JMCOMIC_API_REPO_URL = 'https://github.com/JUKOMU/JMComic-Api-Java'

const formatMiB = UpdateService.formatMiB
const updateStatusLabel = computed(() => {
  switch (updateState.value.phase) {
    case 'racing':
    case 'selected':
      return '下载中'
    case 'verifying':
      return '校验中'
    case 'ready_to_install':
      return '准备安装'
    case 'install_permission_required':
      return '等待安装权限'
    case 'installing':
      return '正在打开安装器'
    case 'failed':
      return '更新失败'
    case 'cancelled':
      return '已取消'
    default:
      return hasUpdate.value ? '等待下载' : '未检查'
  }
})
const downloadProgress = computed(() => {
  const phase = updateState.value.phase
  const progressPhases = [
    'racing',
    'selected',
    'verifying',
    'ready_to_install',
    'install_permission_required',
    'installing',
  ]
  if (!progressPhases.includes(phase)) return null

  const source = updateState.value.source
  const rawBytes =
    phase === 'selected' && (source === 'GitHub' || source === 'Gitee')
      ? source === 'GitHub'
        ? updateState.value.githubBytes
        : updateState.value.giteeBytes
      : Math.max(updateState.value.githubBytes, updateState.value.giteeBytes)
  const total = Math.max(0, updateState.value.totalBytes || latestManifest.value?.sizeBytes || 0)
  const bytes = total > 0 ? Math.min(total, Math.max(0, rawBytes)) : Math.max(0, rawBytes)
  const percent = total > 0 ? Math.min(100, Math.max(0, (bytes / total) * 100)) : 0

  return {
    bytesMiB: bytes / (1024 * 1024),
    totalMiB: total / (1024 * 1024),
    speedMiB: Math.max(0, updateState.value.speedBytesPerSecond) / (1024 * 1024),
    percent,
  }
})
const updateCheckCardStyle = computed(() => ({
  '--update-progress': `${downloadProgress.value?.percent ?? 0}%`,
}))
const updateActionLabel = computed(() => {
  switch (updateState.value.phase) {
    case 'racing':
    case 'selected':
      return '取消下载'
    case 'verifying':
    case 'installing':
      return '处理中...'
    case 'ready_to_install':
    case 'install_permission_required':
      return '安装更新'
    default:
      return '下载更新'
  }
})
const updateActionDisabled = computed(() =>
  ['verifying', 'installing'].includes(updateState.value.phase),
)

const TITLE = 'JQ Viewer'
const displayText = ref('')
const cursorVisible = ref(true)

onMounted(async () => {
  await UpdateService.init()
  if (latestManifest.value) {
    latestVersion.value = latestManifest.value.versionName
    updateChecked.value = true
  }
  try {
    const info = await App.getInfo()
    appVersion.value = info.version
  } catch {
    /* keep default */
  }
  displayText.value = ''
  cursorVisible.value = true
  await nextTick()

  for (let i = 0; i <= TITLE.length; i++) {
    displayText.value = TITLE.slice(0, i)
    if (i < TITLE.length) {
      await new Promise((r) => setTimeout(r, 100 + Math.random() * 150))
    }
  }

  for (let i = 0; i < 1; i++) {
    cursorVisible.value = false
    await new Promise((r) => setTimeout(r, 300))
    cursorVisible.value = true
    await new Promise((r) => setTimeout(r, 300))
  }
  cursorVisible.value = false
})

async function checkUpdate() {
  updateChecking.value = true
  updateError.value = false
  manualHasUpdate.value = false
  latestVersion.value = ''
  updateChecked.value = false

  try {
    const result = await UpdateService.check()
    if (result.updateAvailable) {
      manualHasUpdate.value = true
      latestVersion.value = result.manifest.versionName
      await UpdateService.runPrompt(() => showUpdateAlert(result.manifest))
    }
  } catch {
    updateError.value = true
  } finally {
    await new Promise((resolve) => setTimeout(resolve, 1000))
    updateChecking.value = false
    updateChecked.value = true
  }
}

async function showUpdateAlert(update: UpdateManifest) {
  const confirmed = await presentUpdatePrompt(update)
  if (confirmed) {
    await startUpdate()
  }
}

async function startUpdate() {
  updateError.value = false
  try {
    const result = await UpdateService.start()
    if (result.blocked === 'notification_permission') {
      updateError.value = true
      await showToast('未授予通知权限，更新未开始', 'medium', 2500)
    }
  } catch {
    updateError.value = true
  }
}

async function handleUpdateAction() {
  if (updateState.value.phase === 'racing' || updateState.value.phase === 'selected') {
    await UpdateService.cancel()
    await showToast('更新已取消', 'medium')
    return
  }
  if (
    updateState.value.phase === 'ready_to_install' ||
    updateState.value.phase === 'install_permission_required'
  ) {
    await UpdateService.install()
    return
  }
  await startUpdate()
}

async function openRepo(url: string) {
  try {
    await navigator.clipboard.writeText(url)
    await showToast('仓库地址已复制', 'success')
  } catch {
    window.open(url, '_blank')
  }
}

const reDisplay = async () => {
  displayText.value = ''
  cursorVisible.value = true
  await nextTick()

  for (let i = 0; i <= TITLE.length; i++) {
    displayText.value = TITLE.slice(0, i)
    if (i < TITLE.length) {
      await new Promise((r) => setTimeout(r, 100 + Math.random() * 150))
    }
  }

  cursorVisible.value = true
  await new Promise((r) => setTimeout(r, 200))
  cursorVisible.value = false
}
</script>

<style scoped>
.about-container {
  padding: 24px 20px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.toolbar-title {
  font-size: 16px;
  font-weight: 600;
  color: #4c2a18;
}

.info-card {
  width: 100%;
  max-width: 400px;
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 2px 12px rgba(115, 67, 38, 0.06);
  margin-bottom: 14px;
  overflow: hidden;
}

.app-name {
  text-align: center;
  font-size: 20px;
  font-weight: 700;
  color: #4c2a18;
  padding: 20px 18px 6px;
}

.app-name + .info-row {
  border-top: none;
}

.info-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  min-height: 48px;
}

.info-row-action {
  width: 100%;
  appearance: none;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.info-row-action:active {
  background: #faf4ef;
}

.info-row-action:disabled {
  cursor: default;
}

.info-row-action:focus-visible {
  outline: 2px solid #e8843c;
  outline-offset: -2px;
}

.info-row + .info-row {
  border-top: 1px solid #f5ebe4;
}

.info-label {
  font-size: 15px;
  color: #4c2a18;
  font-weight: 500;
}

.info-value {
  font-size: 14px;
  color: #8c6b5a;
}

.info-value.update {
  color: #e8843c;
  font-weight: 500;
}

.info-value.error {
  color: #d44;
}

.update-card {
  border: 1px solid rgba(232, 132, 60, 0.22);
}

.update-check-card {
  --update-progress: 0%;
  background: linear-gradient(
    to right,
    rgba(232, 132, 60, 0.16) var(--update-progress),
    #fff var(--update-progress)
  );
  transition: background 180ms ease;
}

.update-progress-row {
  align-items: center;
}

.update-status-value {
  display: inline-flex;
  align-items: baseline;
  gap: 0.25em;
  white-space: nowrap;
}

.update-progress {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 0 18px 12px;
  color: #8c6b5a;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.update-progress-size,
.update-progress-speed {
  display: inline-flex;
  align-items: baseline;
  min-width: 0;
}

.update-progress-speed {
  flex: 0 0 auto;
}

.update-error {
  padding: 0 18px 12px;
  color: #d44;
  font-size: 13px;
  line-height: 1.5;
}

.release-notes {
  border-top: 1px solid #f5ebe4;
  padding: 12px 18px;
}

.release-notes .note-title {
  padding: 0 0 6px;
}

.update-action {
  width: 100%;
  border: 0;
  border-top: 1px solid #f5ebe4;
  background: transparent;
  color: #e8843c;
  font: inherit;
  padding: 13px 18px;
  cursor: pointer;
}

.update-action:disabled {
  color: #bba79b;
  cursor: default;
}

.info-action-value {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  min-width: 0;
  margin-left: 12px;
  text-align: right;
}

.repo-url {
  font-family: monospace;
  font-size: 13px;
  word-break: break-all;
  text-align: right;
  max-width: 60%;
}

.repo-url a {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #8c6b5a;
  text-decoration: none;
  border-bottom: 1px solid transparent;
  transition:
    color 0.2s ease,
    border-color 0.2s ease;
}

.repo-url a:hover,
.repo-url a:focus-visible,
.repo-url a:active {
  color: #e8843c;
  border-bottom-color: rgba(232, 132, 60, 0.45);
}

.repo-icon {
  flex: 0 0 auto;
  font-size: 16px;
}

.card-action {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 18px;
  border-top: 1px solid #f5ebe4;
  font-size: 14px;
  color: #f0a060;
  cursor: pointer;
  user-select: none;
}

.card-action:active {
  background: #faf4ef;
}

.entry-arrow {
  flex: 0 0 20px;
  width: 20px;
  height: 20px;
  margin-left: 8px;
  font-size: 20px;
  color: #c4a494;
}

.entry-spinner {
  flex: 0 0 20px;
  width: 20px;
  height: 20px;
  margin-left: 8px;
  color: #e8843c;
}

.author-note {
  padding: 0;
}

.note-title {
  font-size: 15px;
  font-weight: 600;
  color: #4c2a18;
  padding: 16px 18px 0;
}

.note-content {
  padding: 10px 18px 18px;
  font-size: 12px;
  color: #6b4e3e;
  line-height: 1.8;
}

.note-content p {
  margin: 0 0 6px;
}

.note-content p:last-child {
  margin-bottom: 0;
}

.home-title {
  text-align: center;
  margin-bottom: 40px;
  transition: transform 0.3s ease-out;
}

.title-text {
  font-size: 34px;
  font-weight: 700;
  color: #3a261d;
  letter-spacing: 2px;
  transition: transform 0.3s ease-out;
  margin-bottom: 20px;
}

.title-cursor {
  font-size: 32px;
  color: #e87c4b;
  font-weight: 400;
  animation: cursor-blink 0.7s step-end infinite;
}

@keyframes cursor-blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}
</style>
