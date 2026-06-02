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
              <span class="row-subtitle"
              >已用 {{ cacheInfo.usedMb }}MB / {{ cacheInfo.capacityMb }}MB</span
              >
            </div>
            <div class="row-right">
              <div class="usage-bar">
                <div class="usage-fill" :style="{ width: usagePercent + '%' }"/>
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
                min="64"
                max="2048"
                step="64"
                @change="onCacheCapacityChange"
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
                min="5"
                max="50"
                step="5"
                @change="onPreloadPagesChange"
              />
              <span class="unit">页</span>
            </div>
          </div>

          <div class="row divider">
            <div class="row-left">
              <span class="row-title">预加载并发数</span>
              <span class="row-subtitle">阅读时同时加载图片的线程数，下次启动生效</span>
            </div>
            <div class="row-right">
              <input
                class="num-input"
                type="number"
                :value="preloadConcurrency"
                min="1"
                max="12"
                @change="onPreloadConcurrencyChange"
              />
              <span class="unit">线程</span>
            </div>
          </div>

        <!-- 显示模式 -->
        <div class="row divider">
          <div class="row-left">
            <span class="row-title">显示模式</span>
          </div>
          <div class="row-right">
            <div class="segmented">
              <button
                :class="['seg-btn', {active: displayMode === 'vertical'}]"
                @click="onDisplayModeChange('vertical')"
              >纵向</button>
              <button
                :class="['seg-btn', {active: displayMode === 'horizontal'}]"
                @click="onDisplayModeChange('horizontal')"
              >横向</button>
            </div>
          </div>
        </div>

        <!-- 屏幕方向 -->
        <div class="row divider">
          <div class="row-left">
            <span class="row-title">屏幕方向</span>
          </div>
          <div class="row-right">
            <div class="segmented">
              <button
                :class="['seg-btn', {active: screenOrientation === 'auto'}]"
                @click="onScreenOrientationChange('auto')"
              >自动</button>
              <button
                :class="['seg-btn', {active: screenOrientation === 'portrait'}]"
                @click="onScreenOrientationChange('portrait')"
              >竖屏</button>
              <button
                :class="['seg-btn', {active: screenOrientation === 'landscape'}]"
                @click="onScreenOrientationChange('landscape')"
              >横屏</button>
            </div>
          </div>
        </div>

        <!-- 亮度 -->
        <div class="row divider">
          <div class="row-left">
            <span class="row-title">跟随系统亮度</span>
            <span class="row-subtitle">关闭后可手动调节阅读亮度</span>
          </div>
          <div class="row-right">
            <IonToggle
              :checked="brightnessFollowSystem"
              color="warning"
              @ion-change="onBrightnessFollowSystemChange"
            />
          </div>
        </div>
        <div v-if="!brightnessFollowSystem" class="row">
          <IonRange
            class="brightness-slider"
            :min="0"
            :max="1"
            :step="0.05"
            :value="brightnessValue"
            color="warning"
            @ion-change="onBrightnessChange"
          />
        </div>

        <!-- 防止熄屏 -->
        <div class="row divider">
          <div class="row-left">
            <span class="row-title">防止熄屏</span>
            <span class="row-subtitle">阅读时保持屏幕常亮</span>
          </div>
          <div class="row-right">
            <IonToggle
              :checked="keepScreenOn"
              color="warning"
              @ion-change="onKeepScreenOnChange"
            />
          </div>
        </div>

        <!-- 音量键翻页 -->
        <div class="row divider">
          <div class="row-left">
            <span class="row-title">音量键翻页</span>
            <span class="row-subtitle">横向模式翻页，纵向模式滚动</span>
          </div>
          <div class="row-right">
            <IonToggle
              :checked="volumeNavigation"
              color="warning"
              @ion-change="onVolumeNavigationChange"
            />
          </div>
        </div>
        </div>

        <!-- 分组：下载设置 -->
        <div class="section-label">下载设置</div>
        <div class="card">
          <!-- 下载并发数 -->
          <div class="row">
            <div class="row-left">
              <span class="row-title">下载并发数</span>
              <span class="row-subtitle">章节下载同时进行的图片线程数，下次启动生效</span>
            </div>
            <div class="row-right">
              <input
                class="num-input"
                type="number"
                :value="downloadConcurrency"
                min="1"
                max="12"
                @change="onConcurrencyChange"
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
                color="warning"
                @ion-change="onDownloadPublicChange"
              />
            </div>
          </div>
        </div>

        <!-- 分组：批量解析 -->
        <div class="section-label">批量解析</div>
        <div class="card">
          <div class="row">
            <div class="row-left">
              <span class="row-title">图片 OCR 识别</span>
              <span class="row-subtitle">开启后可在批量解析时通过图片上传识别 ID</span>
            </div>
            <div class="row-right">
              <IonToggle :checked="ocrEnabled" color="warning" @ion-change="onOcrEnabledChange"/>
            </div>
          </div>
        </div>

        <!-- 分组：导出设置 -->
        <div class="section-label">收藏夹导出设置</div>
        <div class="card">
          <div class="row">
            <div class="row-left">
              <span class="row-title">导出格式模板</span>
              <span class="row-subtitle">收藏夹导出时每行文本的格式</span>
            </div>
            <div class="row-right">
              <input
                class="text-input"
                type="text"
                :value="exportFormat"
                @change="onExportFormatChange"
              />
            </div>
          </div>
          <!-- 预览 -->
          <div class="row divider">
            <div class="row-left">
              <span class="row-title">可用变量</span>
              <div class="var-tags">
                <span class="var-tag">{id}</span>
                <span class="var-tag">{title}</span>
                <span class="var-tag">{author}</span>
                <span class="var-tag">{authors}</span>
                <span class="var-tag">{tags}</span>
              </div>
            </div>
          </div>
          <div class="row divider">
            <div class="row-left">
              <span class="row-title">预览</span>
              <span class="row-subtitle preview-mono">{{ exportPreview }}</span>
            </div>
          </div>
          <div class="row divider action" @click="resetExportFormat">
            <span class="row-title">重置为默认</span>
            <span class="row-subtitle" style="color: #b89a84; font-size: 12px">JM{id}{title}</span>
          </div>
        </div>

        <!-- 分组：PDF 导出设置 -->
        <div class="section-label">PDF 导出设置</div>
        <div class="card">
          <!-- PDF导出位置 -->
          <div class="row">
            <div class="row-left">
              <span class="row-title">PDF 导出位置</span>
              <span class="row-subtitle">PDF 文件的基础存储路径</span>
            </div>
          </div>
          <div class="row path-row">
            <span class="path-display">{{ pdfExportPath }}</span>
            <button class="browse-btn" @click="onBrowseFolder">浏览</button>
            <button class="reset-mini-btn" @click="resetPdfExportPath">重置</button>
          </div>

          <!-- 保存目录模板 -->
          <div class="row divider">
            <div class="row-left">
              <span class="row-title">保存目录模板</span>
              <span class="row-subtitle">PDF 存储子目录，"/" 表示子目录层级</span>
              <div class="var-tags">
                <span class="var-tag">{id}</span>
                <span class="var-tag">{title}</span>
                <span class="var-tag">{chapterId}</span>
                <span class="var-tag">{chapterName}</span>
                <span class="var-tag">{chapterTitle}</span>
                <span class="var-tag">{pageCount}</span>
                <span class="var-tag">{author}</span>
                <span class="var-tag">{authors}</span>
                <span class="var-tag">{tags}</span>
              </div>
              <div class="var-tags" style="margin-top: 4px">
                <span class="var-tag tag-cond">{tag=标签名}</span>
                <span class="var-tag tag-cond">{tag=标签A|标签B}</span>
                <span class="var-tag tag-cond">{tag=标签A&标签B}</span>
              </div>
            </div>
          </div>
          <div class="row template-row">
            <input
              class="text-input full-width"
              type="text"
              :value="pdfDirTemplate"
              @change="onPdfDirTemplateChange"
            />
            <button class="reset-mini-btn" @click="resetPdfDirTemplate">重置</button>
          </div>
          <div class="render-row">
            <span class="render-label">→</span>
            <span class="render-value">{{ pdfDirRender }}</span>
          </div>

          <!-- 保存名称模板 -->
          <div class="row divider">
            <div class="row-left">
              <span class="row-title">保存名称模板</span>
              <span class="row-subtitle">PDF 文件名（不含扩展名）</span>
            </div>
          </div>
          <div class="row template-row">
            <input
              class="text-input full-width"
              type="text"
              :value="pdfNameTemplate"
              @change="onPdfNameTemplateChange"
            />
            <button class="reset-mini-btn" @click="resetPdfNameTemplate">重置</button>
          </div>
          <div class="render-row">
            <span class="render-label">→</span>
            <span class="render-value">{{ pdfNameRender }}</span>
          </div>

          <!-- 预览 -->
          <div class="row divider">
            <div class="row-left">
              <span class="row-title">预览</span>
              <span class="row-subtitle preview-mono">{{ pdfPathPreview }}</span>
            </div>
          </div>

          <!-- 食用方法 -->
          <div class="row divider action" @click="goPdfTemplateHelp">
            <span class="row-title">食用方法</span>
            <span class="arrow">›</span>
          </div>
        </div>

        <!-- 分组：网络状态 -->
        <div class="section-label">网络状态</div>
        <div class="card">
          <div class="row action" @click="goNetworkStatus">
            <span class="row-title">网络状态</span>
            <span class="arrow">›</span>
          </div>
        </div>

        <!-- 分组：用户 -->
        <div class="section-label">用户</div>
        <div class="card">
          <div class="row action" @click="goUser">
            <span class="row-title">用户</span>
            <span class="row-value">{{ userInfo?.username ?? '未登录' }}</span>
            <span class="arrow">›</span>
          </div>
        </div>

        <!-- 分组：关于 -->
        <div class="section-label">关于</div>
        <div class="card">
          <div class="row action" @click="goAbout">
            <span class="row-title">关于</span>
            <span class="arrow">›</span>
          </div>
        </div>
      </div>

      <!-- 搬迁进度遮罩（阻塞式，不可关闭） -->
      <div v-if="showRelocationModal" class="relocation-overlay">
        <div class="relocation-dialog">
          <div class="relocation-title">正在搬迁文件...</div>

          <div class="relocation-progress-bar">
            <div class="relocation-progress-fill" :style="{ width: relocationPercent + '%' }"/>
          </div>
          <div class="relocation-percent">{{ relocationPercent }}%</div>

          <div class="relocation-count">
            已完成：{{ relocationCurrent }} / {{ relocationTotal }} 个文件
          </div>

          <div class="relocation-phase">{{ phaseLabel }}</div>
          <div v-if="relocationCurrentFile" class="relocation-file">
            {{ relocationCurrentFile }}
          </div>

          <div class="relocation-warning">请勿关闭应用</div>
        </div>
      </div>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
defineOptions({name: 'SettingPage'})

import {computed, onMounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import {alertController, IonContent, IonHeader, IonPage, IonRange, IonToggle, IonToolbar} from '@ionic/vue'
import {App} from '@capacitor/app'
import type {PluginListenerHandle} from '@capacitor/core'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import {JmcomicService, sanitizeError, showToast} from '@/services/JmcomicService'
import {initSettings, SettingsStore} from '@/services/SettingsService'
import {ExportFormatService} from '@/services/ExportFormatService'
import {PdfExportService, PDF_SAMPLE_DATA} from '@/services/PdfExportService'
import {useAuth} from '@/composables/useAuth'
import type {CacheCapacityInfo, RelocationProgress} from '@/services/JmcomicTypes'

const router = useRouter()
const {userInfo} = useAuth()
const appVersion = ref('1.0.0')

function goNetworkStatus() {
  router.push('/network-status')
}

function goUser() {
  router.push('/user')
}

function goAbout() {
  router.push('/about')
}

function goPdfTemplateHelp() {
  router.push('/pdf-template-help')
}

const cacheInfo = ref<CacheCapacityInfo>({capacityMb: 0, usedMb: 0})
const cacheInputMb = ref(SettingsStore.getCacheCapacityMb())
const preloadPages = ref(SettingsStore.getReaderPreloadPages())
const preloadConcurrency = ref(SettingsStore.getPreloadConcurrency())
const downloadConcurrency = ref(SettingsStore.getDownloadConcurrency())
const downloadPublic = ref(SettingsStore.getDownloadPublic())
const ocrEnabled = ref(SettingsStore.getOcrEnabled())
const exportFormat = ref(ExportFormatService.getExportFormat())
const displayMode = ref(SettingsStore.getReaderDisplayMode())
const screenOrientation = ref(SettingsStore.getReaderScreenOrientation())
const brightnessFollowSystem = ref(SettingsStore.getReaderBrightness() < 0)
const brightnessValue = ref(brightnessFollowSystem.value ? 0.5 : SettingsStore.getReaderBrightness())
const keepScreenOn = ref(SettingsStore.getReaderKeepScreenOn())
const volumeNavigation = ref(SettingsStore.getReaderVolumeNavigation())

const exportPreview = computed(() => ExportFormatService.previewExportFormat(exportFormat.value))

// PDF导出设置
const pdfExportPath = ref(PdfExportService.getExportPath())
const pdfDirTemplate = ref(PdfExportService.getDirTemplate())
const pdfNameTemplate = ref(PdfExportService.getNameTemplate())

const pdfPathPreview = computed(() => PdfExportService.previewPath())

const pdfDirRender = computed(() =>
  PdfExportService.renderTemplate(pdfDirTemplate.value, PDF_SAMPLE_DATA),
)
const pdfNameRender = computed(() =>
  PdfExportService.renderTemplate(pdfNameTemplate.value, PDF_SAMPLE_DATA),
)

// ---- 搬迁弹窗状态 ----
const showRelocationModal = ref(false)
const relocationCurrent = ref(0)
const relocationTotal = ref(0)
const relocationPhase = ref('')
const relocationCurrentFile = ref('')

const relocationPercent = computed(() => {
  if (relocationTotal.value <= 0) return 0
  return Math.round((relocationCurrent.value / relocationTotal.value) * 100)
})

const phaseLabel = computed(() => {
  switch (relocationPhase.value) {
    case 'copying':
      return '正在复制...'
    case 'verifying':
      return '正在校验...'
    case 'deleting':
      return '正在清理...'
    case 'scanning':
      return '正在通知系统相册...'
    default:
      return ''
  }
})

const usagePercent = computed(() => {
  if (cacheInfo.value.capacityMb <= 0) return 0
  const pct = Math.round((cacheInfo.value.usedMb / cacheInfo.value.capacityMb) * 100)
  return Math.min(pct, 100)
})

onMounted(async () => {
  // 确保设置缓存已从 DB 加载（App.vue 已调用，此处为幂等安全网）
  await initSettings()

  // 获取应用版本
  try {
    const info = await App.getInfo()
    appVersion.value = info.version
  } catch {
    /* keep default */
  }

  // 加载缓存状态（从 ImageRegistry 运行时读取）
  try {
    const info = await JmcomicService.getCacheCapacityInfo()
    cacheInfo.value = info
  } catch (e) {
    /* ignore */
  }

  // 从缓存刷新 UI（SettingsStore 已被 initSettings 填充）
  preloadPages.value = SettingsStore.getReaderPreloadPages()
  preloadConcurrency.value = SettingsStore.getPreloadConcurrency()
  downloadConcurrency.value = SettingsStore.getDownloadConcurrency()
  downloadPublic.value = SettingsStore.getDownloadPublic()
  cacheInputMb.value = SettingsStore.getCacheCapacityMb()
  ocrEnabled.value = SettingsStore.getOcrEnabled()
  displayMode.value = SettingsStore.getReaderDisplayMode()
  screenOrientation.value = SettingsStore.getReaderScreenOrientation()
  brightnessFollowSystem.value = SettingsStore.getReaderBrightness() < 0
  brightnessValue.value = brightnessFollowSystem.value ? 0.5 : SettingsStore.getReaderBrightness()
  keepScreenOn.value = SettingsStore.getReaderKeepScreenOn()
  volumeNavigation.value = SettingsStore.getReaderVolumeNavigation()

  // 将 PDF 导出默认相对路径解析为绝对路径（与文件夹选择器返回的绝对路径保持一致）
  try {
    const result = await JmcomicService.getExternalStoragePath()
    PdfExportService.ensureAbsolutePath(result.path)
    pdfExportPath.value = PdfExportService.getExportPath()
  } catch {
    /* keep default */
  }
})

// ---- 缓存上限 ----
async function onCacheCapacityChange(e: Event) {
  const val = parseInt((e.target as HTMLInputElement).value, 10)
  if (!Number.isFinite(val)) return
  const mb = Math.max(64, Math.min(2048, val))
  const prev = cacheInputMb.value
  cacheInputMb.value = mb
  SettingsStore.setCacheCapacityMb(mb)
  try {
    await JmcomicService.setCacheCapacity(mb)
  } catch {
    cacheInputMb.value = prev
    SettingsStore.setCacheCapacityMb(prev)
    await showToast('设置失败', 'danger')
  }
}

// ---- 清空缓存 ----
async function onClearCache() {
  const alert = await alertController.create({
    header: '清空缓存',
    message: '确定清空全部图片缓存吗？',
    buttons: [
      {text: '取消', role: 'cancel'},
      {
        text: '清空',
        role: 'destructive',
        cssClass: 'danger-alert',
        handler: async () => {
          try {
            await JmcomicService.clearImageCache()
            cacheInfo.value = {...cacheInfo.value, usedMb: 0}
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
async function onPreloadPagesChange(e: Event) {
  const val = parseInt((e.target as HTMLInputElement).value, 10)
  if (!Number.isFinite(val)) return
  const n = Math.max(5, Math.min(50, val))
  preloadPages.value = n
  SettingsStore.setReaderPreloadPages(n)
  try {
    await JmcomicService.setReaderPreloadPages(n)
    await showToast('已保存，下次阅读生效', 'success')
  } catch {
    await showToast('保存失败', 'danger')
  }
}

// ---- 预加载并发数 ----
async function onPreloadConcurrencyChange(e: Event) {
  const val = parseInt((e.target as HTMLInputElement).value, 10)
  if (!Number.isFinite(val)) return
  const n = Math.max(1, Math.min(12, val))
  preloadConcurrency.value = n
  SettingsStore.setPreloadConcurrency(n)
  try {
    await JmcomicService.setPreloadConcurrency(n)
    await showToast('已保存，下次启动生效', 'success')
  } catch {
    await showToast('保存失败', 'danger')
  }
}

// ---- 下载并发数 ----
async function onConcurrencyChange(e: Event) {
  const val = parseInt((e.target as HTMLInputElement).value, 10)
  if (!Number.isFinite(val)) return
  const n = Math.max(1, Math.min(12, val))
  downloadConcurrency.value = n
  SettingsStore.setDownloadConcurrency(n)
  try {
    await JmcomicService.setDownloadConcurrency(n)
    await showToast('已保存，下次启动生效', 'success')
  } catch {
    await showToast('保存失败', 'danger')
  }
}

// ---- OCR 开关 ----
async function onOcrEnabledChange(e: CustomEvent) {
  const enabled = e.detail.checked
  ocrEnabled.value = enabled
  SettingsStore.setOcrEnabled(enabled)
  try {
    await JmcomicService.setOcrEnabled(enabled)
  } catch {
    ocrEnabled.value = !enabled
    SettingsStore.setOcrEnabled(!enabled)
    await showToast('保存失败', 'danger')
  }
}

// ---- 导出格式 ----
function onExportFormatChange(e: Event) {
  const val = (e.target as HTMLInputElement).value.trim()
  exportFormat.value = val || ExportFormatService.getExportFormat()
  ExportFormatService.setExportFormat(val)
}

function resetExportFormat() {
  ExportFormatService.resetExportFormat()
  exportFormat.value = ExportFormatService.getExportFormat()
  showToast('已重置为默认格式', 'success')
}

// ---- PDF 导出设置 ----
async function onBrowseFolder() {
  try {
    const result = await JmcomicService.pickFolder()
    if (!result.cancelled && result.path) {
      // 确保路径以 / 结尾
      const path = result.path.endsWith('/') ? result.path : result.path + '/'
      pdfExportPath.value = path
      PdfExportService.setExportPath(path)
    }
  } catch (e: any) {
    await showToast(sanitizeError(e, '选择文件夹失败'), 'danger')
  }
}

function onPdfDirTemplateChange(e: Event) {
  const val = (e.target as HTMLInputElement).value.trim()
  pdfDirTemplate.value = val
  PdfExportService.setDirTemplate(val)
}

function onPdfNameTemplateChange(e: Event) {
  const val = (e.target as HTMLInputElement).value.trim()
  pdfNameTemplate.value = val
  PdfExportService.setNameTemplate(val)
}

function resetPdfExportPath() {
  PdfExportService.resetExportPath()
  pdfExportPath.value = PdfExportService.getExportPath()
}

function resetPdfDirTemplate() {
  PdfExportService.resetDirTemplate()
  pdfDirTemplate.value = PdfExportService.getDirTemplate()
}

function resetPdfNameTemplate() {
  PdfExportService.resetNameTemplate()
  pdfNameTemplate.value = PdfExportService.getNameTemplate()
}

// ---- 公开下载 ----
const isSwitchingDownloadPublic = ref(false)

async function onDownloadPublicChange(e: CustomEvent) {
  if (isSwitchingDownloadPublic.value) return
  isSwitchingDownloadPublic.value = true

  const open = e.detail.checked
  downloadPublic.value = open

  // 开启时根据 API 版本申请合适的权限
  if (open) {
    try {
      const result = await JmcomicService.requestManageStorage()
      if (!result.granted) {
        downloadPublic.value = false
        if (result.permissionType === 'not_supported') {
          await showToast('Android 10 不支持公开下载', 'danger')
        } else if (result.permissionType === 'WRITE_EXTERNAL_STORAGE') {
          await showToast('请在弹出对话框中授予存储权限后重新开启', 'danger')
        } else {
          await showToast('请在系统设置中授予"所有文件访问权限"后重新开启', 'danger')
        }
        return
      }
    } catch (e: any) {
      downloadPublic.value = false
      await showToast(sanitizeError(e, '权限检查失败'), 'danger')
      return
    }
  }

  // 显示阻塞弹窗
  showRelocationModal.value = true
  relocationCurrent.value = 0
  relocationTotal.value = 0
  relocationPhase.value = ''
  relocationCurrentFile.value = ''

  // 注册进度监听
  let handle: PluginListenerHandle | null = null
  try {
    handle = await JmcomicService.addRelocationProgressListener((data: RelocationProgress) => {
      relocationCurrent.value = data.current
      relocationTotal.value = data.total
      relocationPhase.value = data.phase
      if (data.currentFile) {
        relocationCurrentFile.value = data.currentFile
      }
    })
  } catch {
    /* 监听注册失败不影响搬迁 */
  }

  try {
    const result = await JmcomicService.setDownloadPublic(open)
    SettingsStore.setDownloadPublic(open)
    const msg =
      result.moved > 0
        ? `已搬迁 ${result.moved} 个文件` + (open ? '，系统相册可查看' : '')
        : open
          ? '已设为公开，无文件需搬迁'
          : '已设为仅应用内可见'
    await showToast(msg, 'success')
  } catch (e: any) {
    downloadPublic.value = !open
    SettingsStore.setDownloadPublic(!open)
    await showToast(sanitizeError(e, '切换失败'), 'danger')
  } finally {
    handle?.remove()
    showRelocationModal.value = false
    isSwitchingDownloadPublic.value = false
  }
}

// ---- 显示模式 ----
function onDisplayModeChange(mode: string) {
  displayMode.value = mode
  SettingsStore.setReaderDisplayMode(mode)
  JmcomicService.setReaderDisplayMode(mode).catch(() => {})
}

// ---- 屏幕方向 ----
function onScreenOrientationChange(orientation: string) {
  screenOrientation.value = orientation
  SettingsStore.setReaderScreenOrientation(orientation)
  JmcomicService.setReaderScreenOrientation(orientation).catch(() => {})
}

// ---- 亮度跟随系统 ----
async function onBrightnessFollowSystemChange(e: CustomEvent) {
  const follow = e.detail.checked
  brightnessFollowSystem.value = follow
  if (follow) {
    SettingsStore.setReaderBrightness(-1)
    try { await JmcomicService.setReaderBrightness(-1) } catch { /* ignore */ }
  } else {
    SettingsStore.setReaderBrightness(brightnessValue.value)
    try { await JmcomicService.setReaderBrightness(brightnessValue.value) } catch { /* ignore */ }
  }
}

// ---- 亮度滑块 ----
function onBrightnessChange(e: CustomEvent) {
  const val = Number(e.detail.value)
  brightnessValue.value = val
  SettingsStore.setReaderBrightness(val)
  JmcomicService.setReaderBrightness(val).catch(() => {})
}

// ---- 防止熄屏 ----
async function onKeepScreenOnChange(e: CustomEvent) {
  const enabled = e.detail.checked
  keepScreenOn.value = enabled
  SettingsStore.setReaderKeepScreenOn(enabled)
  try { await JmcomicService.setReaderKeepScreenOn(enabled) } catch { /* ignore */ }
}

// ---- 音量键翻页 ----
function onVolumeNavigationChange(e: CustomEvent) {
  const enabled = e.detail.checked
  volumeNavigation.value = enabled
  SettingsStore.setReaderVolumeNavigation(enabled)
  JmcomicService.setReaderVolumeNavigation(enabled).catch(() => {})
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

/* 导出格式 */
.text-input {
  width: 160px;
  height: 32px;
  border: 1px solid #e0cfc4;
  border-radius: 8px;
  padding: 0 10px;
  text-align: left;
  font-size: 13px;
  color: #4c2a18;
  background: #fdfaf8;
  outline: none;
  font-family: monospace;
}

.text-input:focus {
  border-color: #f0a060;
}

.var-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}

.var-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 999px;
  background: #fff0e7;
  color: #9b5a35;
  font-size: 11px;
  font-family: monospace;
}

.var-tag.tag-cond {
  background: #fdf0f0;
  color: #b06060;
  font-style: italic;
}

.preview-mono {
  font-family: monospace;
  word-break: break-all;
}

/* 搬迁进度遮罩 */
.relocation-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
}

.relocation-dialog {
  background: #fff;
  border-radius: 16px;
  padding: 32px 28px 24px;
  width: 300px;
  text-align: center;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.18);
}

.relocation-title {
  font-size: 16px;
  font-weight: 600;
  color: #4c2a18;
  margin-bottom: 20px;
}

.relocation-progress-bar {
  height: 8px;
  background: #f0e4db;
  border-radius: 4px;
  overflow: hidden;
}

.relocation-progress-fill {
  height: 100%;
  background: linear-gradient(135deg, #f0a060, #e8843c);
  border-radius: 4px;
  transition: width 0.25s ease;
}

.relocation-percent {
  font-size: 28px;
  font-weight: 700;
  color: #e8843c;
  margin: 12px 0 8px;
}

.relocation-count {
  font-size: 13px;
  color: #8c6b5a;
  margin-bottom: 4px;
}

.relocation-phase {
  font-size: 12px;
  color: #b89a84;
  margin-bottom: 2px;
}

.relocation-file {
  font-size: 11px;
  color: #c4a494;
  margin-bottom: 16px;
  word-break: break-all;
  max-height: 32px;
  overflow: hidden;
}

.relocation-warning {
  font-size: 12px;
  color: #d4a08a;
  margin-top: 8px;
}

/* 分段按钮 */
.segmented {
  display: flex;
  border: 1px solid #e0cfc4;
  border-radius: 8px;
  overflow: hidden;
}

.seg-btn {
  padding: 6px 14px;
  border: 0;
  background: #fdfaf8;
  color: #8c6b5a;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.seg-btn:not(:last-child) {
  border-right: 1px solid #e0cfc4;
}

.seg-btn.active {
  background: #f0a060;
  color: #fff;
}

/* PDF 导出设置 */
.path-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 16px 12px;
}

.path-display {
  flex: 1;
  min-width: 0;
  padding: 6px 10px;
  background: #fdf5ef;
  border: 1px solid #f0d8c8;
  border-radius: 8px;
  font-size: 12px;
  font-family: monospace;
  color: #4c2a18;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.browse-btn {
  height: 32px;
  padding: 0 12px;
  border: 1px solid #f0a060;
  border-radius: 8px;
  background: #fff0e7;
  color: #e8843c;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  flex-shrink: 0;
}

.browse-btn:active {
  background: #fde0c8;
}

.reset-mini-btn {
  height: 32px;
  padding: 0 8px;
  border: 1px solid #e0cfc4;
  border-radius: 8px;
  background: #fdfaf8;
  color: #b89a84;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
  flex-shrink: 0;
}

.reset-mini-btn:active {
  background: #f0e4db;
}

.template-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 16px 8px;
}

.render-row {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  padding: 0 16px 10px;
}

.render-label {
  font-size: 11px;
  color: #c4a494;
  font-family: monospace;
  flex-shrink: 0;
  padding-top: 1px;
}

.render-value {
  font-size: 12px;
  font-family: monospace;
  color: #8a6048;
  word-break: break-all;
}

.text-input.full-width {
  flex: 1;
  width: auto;
  min-width: 0;
}

/* 亮度滑块 */
.brightness-slider {
  flex: 1;
  --bar-background: #f0e4db;
  --bar-background-active: #f0a060;
  --knob-background: #f0a060;
  --knob-size: 18px;
  --height: 4px;
}
</style>
