<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonButtons slot="start">
          <IonBackButton default-href="/setting"/>
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
        <div class="info-card">
          <div class="info-row" @click="checkUpdate">
            <span class="info-label">检查更新</span>
            <span v-if="updateChecking" class="info-value">检查中...<span class="arrow">&#8250;</span></span>
            <span v-else-if="updateError" class="info-value error">检查失败<span class="arrow">&#8250;</span></span>
            <span v-else-if="hasUpdate" class="info-value update">发现新版本 {{ latestVersion }}<span class="arrow">&#8250;</span></span>
            <span v-else class="info-value">已是最新<span class="arrow">&#8250;</span></span>
          </div>
        </div>

        <!-- 仓库地址 -->
        <div class="info-card" @click="openRepo">
          <div class="info-row">
            <span class="info-label">仓库地址</span>
            <span class="info-value repo-url"><a href="https://github.com/JUKOMU/JQ-Viewer">JQ Viewer</a></span>
          </div>
          <div class="info-row">
            <span class="info-label"></span>
            <span class="info-value repo-url"><a href="https://github.com/JUKOMU/JMComic-Api-Java">JMComic-Api-Java</a></span>
          </div>
        </div>

        <div class="info-card author-note">
          <div class="note-title">关于这个应用</div>
          <div class="note-content">
            <p>全称叫 JMComic Quick Viewer, 当然快不快得看JM的服务器。</p>
            <p></p>
            <p>本来是因为批量解析的功能才开始的整个项目, 下次遇到发一串车牌号的可以方便点查看。</p>
            <p></p>
            <p>还有一个原因是本项目依赖的 JMComic API 库, 这是我的另一个开源项目, 我想用来做点有用的东西。</p>
            <p></p>
            <p>如果你觉得好用，欢迎分享给朋友。遇到问题或有什么建议，可以在 GitHub 提交<a
              href="https://github.com/JUKOMU/JQ-Viewer/issues/new">Issue</a>。</p>
            <div style="height: 1000px"></div>
            <p>没有了, 别看了</p>
            <div style="height: 2000px"></div>
            <p>还看?</p>
            <div style="height: 3000px"></div>
            <p><img src="../../public/000.jpg"></p>
          </div>
        </div>
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({name: 'AboutPage'})

import {nextTick, onMounted, ref} from 'vue'
import {App} from '@capacitor/app'
import {alertController, IonBackButton, IonButtons, IonContent, IonHeader, IonPage, IonTitle, IonToolbar,} from '@ionic/vue'
import {showToast} from '@/services/JmcomicService'
import {compareVersion, RELEASES_API, sanitizeReleaseBody} from '@/utils/version'

const appVersion = ref('1.0.0')
const updateChecking = ref(false)
const updateError = ref(false)
const hasUpdate = ref(false)
const latestVersion = ref('')

const REPO_URL = 'https://github.com/jukomu/jq-viewer'

const TITLE = 'JQ Viewer'
const displayText = ref('')
const cursorVisible = ref(true)

onMounted(async () => {
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

async function checkUpdate() {
  updateChecking.value = true
  updateError.value = false
  hasUpdate.value = false
  latestVersion.value = ''

  try {
    const resp = await fetch(RELEASES_API, {
      headers: {Accept: 'application/vnd.github.v3+json'},
    })
    if (!resp.ok) throw new Error('API error')
    const data = await resp.json()
    const remote = (data.tag_name || '').replace(/^v/, '')
    if (remote && compareVersion(remote, appVersion.value) > 0) {
      hasUpdate.value = true
      latestVersion.value = remote
      await showUpdateAlert(remote, data.body || '', data.html_url || '')
    }
  } catch {
    updateError.value = true
  } finally {
    updateChecking.value = false
  }
}

async function showUpdateAlert(version: string, body: string, htmlUrl: string) {
  const cleaned = sanitizeReleaseBody(body || '')

  const alert1 = await alertController.create({
    header: `发现新版本 v${version}`,
    message: cleaned || '（无更新说明）',
    cssClass: 'update-alert',
    buttons: [
      { text: '忽略', role: 'cancel' },
      {
        text: '好',
        handler: async () => {
          const alert2 = await alertController.create({
            header: '前往下载',
            message: htmlUrl,
            cssClass: 'update-alert',
            buttons: [
              { text: '取消', role: 'cancel' },
              {
                text: '打开',
                handler: () => {
                  window.open(htmlUrl, '_blank')
                },
              },
            ],
          })
          await alert2.present()
        },
      },
    ],
  })
  await alert1.present()
}

async function openRepo() {
  try {
    await navigator.clipboard.writeText(REPO_URL)
    await showToast('仓库地址已复制', 'success')
  } catch {
    window.open(REPO_URL, '_blank')
  }
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

.repo-url {
  font-family: monospace;
  font-size: 13px;
  word-break: break-all;
  text-align: right;
  max-width: 60%;
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

.arrow {
  font-size: 20px;
  color: #c4a494;
  font-weight: 300;
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
  0%, 100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}
</style>
