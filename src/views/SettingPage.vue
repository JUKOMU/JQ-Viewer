<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <div class="toolbar-start">
          <MenuToggleButton/>
        </div>
        <div class="toolbar-title">设置</div>
      </IonToolbar>
    </IonHeader>
    <IonContent>
      <div class="settings-list">

        <!-- 分组：图片缓存 -->
        <div class="section-label">图片缓存</div>
        <div class="card">
          <!-- 缓存用量 -->
          <div class="row">
            <div class="row-left">
              <span class="row-title">缓存用量</span>
              <span class="row-subtitle">已用 {{ cacheInfo.usedMb }}MB / {{ cacheInfo.capacityMb }}MB</span>
            </div>
            <div class="row-right">
              <div class="usage-bar">
                <div class="usage-fill" :style="{ width: usagePercent + '%' }" />
              </div>
              <span class="usage-text">{{ usagePercent }}%</span>
            </div>
          </div>

          <!-- 缓存上限 -->
          <div class="row divider">
            <div class="row-left">
              <span class="row-title">缓存上限</span>
            </div>
            <div class="row-right">
              <input
                class="num-input"
                type="number"
                :value="cacheInputMb"
                @change="onCacheCapacityChange"
                min="64"
                max="2048"
                step="64"
              />
              <span class="unit">MB</span>
            </div>
          </div>

          <!-- 清空缓存 -->
          <div class="row divider action" @click="onClearCache">
            <span class="row-title">清空缓存</span>
            <span class="arrow">›</span>
          </div>
        </div>

        <!-- 分组：阅读设置 -->
        <div class="section-label">阅读设置</div>
        <div class="card">
          <div class="row">
            <div class="row-left">
              <span class="row-title">预加载页数</span>
              <span class="row-subtitle">阅读时前后预载图片数量</span>
            </div>
            <div class="row-right">
              <input
                class="num-input"
                type="number"
                :value="preloadPages"
                @change="onPreloadPagesChange"
                min="5"
                max="50"
                step="5"
              />
              <span class="unit">页</span>
            </div>
          </div>
        </div>

        <!-- 分组：下载设置 -->
        <div class="section-label">下载设置</div>
        <div class="card">
          <!-- 并发数 -->
          <div class="row">
            <div class="row-left">
              <span class="row-title">并发下载数</span>
              <span class="row-subtitle">同时下载的图片线程数</span>
            </div>
            <div class="row-right">
              <input
                class="num-input"
                type="number"
                :value="downloadConcurrency"
                @change="onConcurrencyChange"
                min="1"
                max="12"
              />
              <span class="unit">线程</span>
            </div>
          </div>

          <!-- 公开下载 -->
          <div class="row divider">
            <div class="row-left">
              <span class="row-title">公开下载内容</span>
              <span class="row-subtitle">开启后新下载的图片可在系统相册中查看</span>
            </div>
            <div class="row-right">
              <IonToggle
                :checked="downloadPublic"
                @ion-change="onDownloadPublicChange"
                color="warning"
              />
            </div>
          </div>
        </div>

        <!-- 分组：关于 -->
        <div class="section-label">关于</div>
        <div class="card">
          <div class="row">
            <span class="row-title">版本</span>
            <span class="row-value">{{ appVersion }}</span>
          </div>
        </div>

      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { IonContent, IonHeader, IonPage, IonToggle, IonToolbar, alertController } from '@ionic/vue'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import { JmcomicService, showToast } from '@/services/JmcomicService'
import { SettingsStore } from '@/services/SettingsService'
import type { CacheCapacityInfo } from '@/services/JmcomicTypes'

const appVersion = ref('0.0.1')

const cacheInfo = ref<CacheCapacityInfo>({ capacityMb: 0, usedMb: 0 })
const cacheInputMb = ref(640)
const preloadPages = ref(15)
const downloadConcurrency = ref(6)
const downloadPublic = ref(false)

const usagePercent = computed(() => {
  if (cacheInfo.value.capacityMb <= 0) return 0
  const pct = Math.round((cacheInfo.value.usedMb / cacheInfo.value.capacityMb) * 100)
  return Math.min(pct, 100)
})

onMounted(async () => {
  // 加载缓存状态
  try {
    const info = await JmcomicService.getCacheCapacityInfo()
    cacheInfo.value = info
    cacheInputMb.value = info.capacityMb
  } catch (e) { /* ignore */ }

  // 加载前端设置
  preloadPages.value = SettingsStore.getReaderPreloadPages()
  downloadConcurrency.value = SettingsStore.getDownloadConcurrency()
  downloadPublic.value = SettingsStore.getDownloadPublic()

  // 从 Android 侧同步公开状态
  try {
    const result = await JmcomicService.getDownloadPublic()
    if (result.downloadPublic !== downloadPublic.value) {
      downloadPublic.value = result.downloadPublic
      SettingsStore.setDownloadPublic(result.downloadPublic)
    }
  } catch (e) { /* ignore */ }
})

// ---- 缓存上限 ----
function onCacheCapacityChange(e: Event) {
  const val = parseInt((e.target as HTMLInputElement).value, 10)
  if (!Number.isFinite(val)) return
  const mb = Math.max(64, Math.min(2048, val))
  cacheInputMb.value = mb
  JmcomicService.setCacheCapacity(mb)
}

// ---- 清空缓存 ----
async function onClearCache() {
  const alert = await alertController.create({
    header: '清空缓存',
    message: '确定清空全部图片缓存吗？',
    buttons: [
      { text: '取消', role: 'cancel' },
      {
        text: '清空',
        role: 'destructive',
        cssClass: 'danger-alert',
        handler: async () => {
          try {
            await JmcomicService.clearImageCache()
            cacheInfo.value = { ...cacheInfo.value, usedMb: 0 }
            await showToast('缓存已清空', 'success')
          } catch (e) {
            await showToast('清空失败', 'danger')
          }
        },
      },
    ],
  })
  await alert.present()
}

// ---- 预加载页数 ----
function onPreloadPagesChange(e: Event) {
  const val = parseInt((e.target as HTMLInputElement).value, 10)
  if (!Number.isFinite(val)) return
  const n = Math.max(5, Math.min(50, val))
  preloadPages.value = n
  SettingsStore.setReaderPreloadPages(n)
  showToast('已保存，下次阅读生效', 'success')
}

// ---- 并发数 ----
function onConcurrencyChange(e: Event) {
  const val = parseInt((e.target as HTMLInputElement).value, 10)
  if (!Number.isFinite(val)) return
  const n = Math.max(1, Math.min(12, val))
  downloadConcurrency.value = n
  SettingsStore.setDownloadConcurrency(n)
  JmcomicService.setDownloadConcurrency(n)
  showToast('已保存', 'success')
}

// ---- 公开下载 ----
async function onDownloadPublicChange(e: CustomEvent) {
  const open = e.detail.checked
  downloadPublic.value = open
  SettingsStore.setDownloadPublic(open)
  try {
    await JmcomicService.setDownloadPublic(open)
    showToast(open ? '已公开全部下载内容，系统相册可查看' : '已设为仅应用内可见', 'success')
  } catch (e: any) {
    // 切换失败，回退开关
    downloadPublic.value = !open
    SettingsStore.setDownloadPublic(!open)
    showToast(String(e?.message ?? '切换失败'), 'danger')
  }
}
</script>

<style scoped>
.toolbar-start {
  padding: 0 0 8px 14px;
}

.toolbar-title {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  font-size: 16px;
  font-weight: 600;
  color: #4c2a18;
}

:deep(ion-header),
:deep(ion-toolbar) {
  overflow: visible;
}

:deep(ion-toolbar) {
  --min-height: auto;
}

.settings-list {
  padding: 8px 16px 32px;
}

.section-label {
  font-size: 12px;
  font-weight: 600;
  color: #b89a84;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin: 20px 0 8px 6px;
}

.card {
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 2px 12px rgba(115, 67, 38, 0.06);
  overflow: hidden;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  min-height: 48px;
}

.row.divider {
  border-top: 1px solid #f5ebe4;
}

.row.action {
  cursor: pointer;
  user-select: none;
}

.row.action:active {
  background: #faf4ef;
}

.row-left {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  margin-right: 12px;
}

.row-title {
  font-size: 15px;
  color: #4c2a18;
  font-weight: 500;
}

.row-subtitle {
  font-size: 12px;
  color: #b89a84;
}

.row-value {
  font-size: 14px;
  color: #8c6b5a;
}

.row-right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.arrow {
  font-size: 20px;
  color: #c4a494;
  font-weight: 300;
}

.num-input {
  width: 60px;
  height: 32px;
  border: 1px solid #e0cfc4;
  border-radius: 8px;
  text-align: center;
  font-size: 14px;
  color: #4c2a18;
  background: #fdfaf8;
  outline: none;
}

.num-input:focus {
  border-color: #f0a060;
}

.unit {
  font-size: 13px;
  color: #b89a84;
}

/* 缓存用量条 */
.usage-bar {
  width: 80px;
  height: 6px;
  background: #f0e4db;
  border-radius: 3px;
  overflow: hidden;
}

.usage-fill {
  height: 100%;
  background: linear-gradient(135deg, #f0a060, #e8843c);
  border-radius: 3px;
  transition: width 0.3s ease;
}

.usage-text {
  font-size: 12px;
  color: #b89a84;
  width: 30px;
  text-align: right;
}
</style>
