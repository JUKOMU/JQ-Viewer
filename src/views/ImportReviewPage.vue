<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <div class="toolbar-start">
          <IonBackButton :default-href="'/download'" @click.stop.prevent="onBack"/>
        </div>
        <div class="toolbar-title">导入 PDF 审核</div>
        <div class="toolbar-end">
          <button
            class="confirm-btn"
            :disabled="loading || !hasAnyResolved"
            @click="onConfirm"
          >
            确认导入
          </button>
        </div>
      </IonToolbar>
    </IonHeader>

    <IonContent>
      <!-- 空状态 -->
      <div v-if="files.length === 0 && !loading" class="empty-state">
        <IonIcon :icon="documentTextOutline" class="empty-icon"/>
        <p>所选文件夹中未找到 PDF 文件</p>
      </div>

      <!-- 统计栏 -->
      <div v-if="files.length > 0" class="stats-bar">
        <span class="stat resolved">已解析 {{ resolvedCount }}</span>
        <span class="stat ambiguous">多ID {{ ambiguousCount }}</span>
        <span class="stat missing">无ID {{ missingCount }}</span>
        <span v-if="duplicateCount > 0" class="stat duplicate">
          ID重复 {{ duplicateCount }}
        </span>
      </div>

      <!-- 文件卡片列表 -->
      <TransitionGroup v-if="files.length > 0" name="card-list" tag="div" class="file-list">
        <div
          v-for="(file, idx) in files"
          :key="file.filePath"
          class="file-card"
          :class="cardClass(file)"
        >
          <div class="card-header">
            <IonIcon
              :icon="statusIcon(file)"
              class="status-icon"
              :class="statusIconClass(file)"
            />
            <span class="file-name" :title="file.fileName">{{ file.fileName }}</span>
            <button class="edit-toggle" @click="toggleEdit(idx)">
              <IonIcon :icon="editingIdx === idx ? checkmarkOutline : createOutline"/>
            </button>
          </div>

          <!-- 只读展示 -->
          <div v-if="editingIdx !== idx" class="card-body">
            <div class="id-tags">
              <template v-if="file.status === 'resolved' && file.extractedIds.length === 1">
                <span class="tag tag-resolved">{{ file.extractedIds[0] }}</span>
                <span v-if="file.chapterSortOrder != null" class="tag tag-chapter">
                  第{{ file.chapterSortOrder }}话
                </span>
              </template>
              <template v-else-if="file.status === 'ambiguous'">
                <span class="tag tag-ambiguous">多个候选ID</span>
                <span v-for="id in file.extractedIds" :key="id" class="tag tag-candidate">{{ id }}</span>
              </template>
              <template v-else>
                <span class="tag tag-missing">未识别 ID</span>
              </template>
              <span v-if="file.duplicateIds.length > 0" class="tag tag-duplicate">
                与其他文件 ID 重复
              </span>
            </div>

            <!-- 相册详情预览 -->
            <div v-if="file.albumDetail" class="album-preview">
              <img
                v-if="file.albumDetail.image"
                :src="file.albumDetail.image"
                class="album-cover"
              />
              <div class="album-info">
                <span class="album-title">{{ file.albumDetail.title }}</span>
                <span class="album-authors">{{ file.albumDetail.authors?.join('，') }}</span>
              </div>
            </div>
            <div v-else-if="file.status === 'resolved' && file.extractedIds.length === 1 && file.albumDetail === null" class="album-preview no-detail">
              <span class="no-detail-text">无法获取本子信息（ID: {{ file.extractedIds[0] }}）</span>
            </div>

            <div v-if="file.chapterSortOrder != null" class="chapter-order-display">
              章节序号: {{ file.chapterSortOrder }}
            </div>
          </div>

          <!-- 编辑模式 -->
          <div v-if="editingIdx === idx" class="edit-area">
            <textarea
              v-model="editText"
              class="edit-textarea"
              rows="1"
              @keydown.enter.prevent="applyEdit(idx)"
            />
            <div class="edit-hints">
              <span class="hint">删除多余 ID 或添加章节序号（末尾空格+数字，如 <code>123456 3</code>）</span>
            </div>
            <button class="apply-btn" @click="applyEdit(idx)">应用</button>
          </div>
        </div>
      </TransitionGroup>

      <div class="bottom-spacer"/>
    </IonContent>
  </IonPage>

  <!-- 收藏夹选择器 -->
  <FavoriteFolderPicker
    v-model="showFolderPicker"
    :online-folders="[]"
    :offline-folders="offlineFolders"
    :online-folder-counts="{}"
    :hide-online="true"
    @select="onFavFolderSelect"
    @add-folder="onAddFolder"
  />
</template>

<script setup lang="ts">
defineOptions({ name: 'ImportReviewPage' })

import { ref, computed, onMounted } from 'vue'
import { IonPage, IonHeader, IonToolbar, IonBackButton, IonContent, IonIcon } from '@ionic/vue'
import { alertController } from '@ionic/vue'
import { useRouter } from 'vue-router'
import { documentTextOutline, createOutline, checkmarkOutline, checkmarkCircle, helpCircle, closeCircle, warning } from 'ionicons/icons'
import { PdfImportService } from '@/services/PdfImportService'
import { JmcomicService, sanitizeError, showToast } from '@/services/JmcomicService'
import { OfflineFavoriteService } from '@/services/OfflineFavoriteService'
import FavoriteFolderPicker from '@/components/favorite/FavoriteFolderPicker.vue'
import type { PdfFileParseItem } from '@/utils/importPdfParse'
import type { FolderEntry } from '@/services/JmcomicTypes'

const router = useRouter()

// ---- 状态 ----
const files = ref<PdfFileParseItem[]>([])
const loading = ref(true)
const editingIdx = ref<number | null>(null)
const editText = ref('')

// ---- 收藏夹 ----
const showFolderPicker = ref(false)
const offlineFolders = ref<FolderEntry[]>([])
const pendingResolvedFiles = ref<PdfFileParseItem[]>([])
const selectedFolderId = ref<string | undefined>(undefined)

// ---- 统计 ----
const resolvedCount = computed(() =>
  files.value.filter((f) => f.status === 'resolved' && f.extractedIds.length === 1).length,
)
const ambiguousCount = computed(() =>
  files.value.filter((f) => f.status === 'ambiguous').length,
)
const missingCount = computed(() =>
  files.value.filter((f) => f.status === 'missing').length,
)
const duplicateCount = computed(() =>
  files.value.filter((f) => f.duplicateIds.length > 0).length,
)
const hasAnyResolved = computed(() =>
  files.value.some((f) => f.editedIds?.length === 1),
)

// ---- 初始化 ----
onMounted(async () => {
  const cached = PdfImportService.getCachedParseResult()
  if (!cached || cached.files.length === 0) {
    loading.value = false
    await showToast('未接收到扫描数据，请返回重新选择文件夹', 'danger')
    return
  }
  files.value = cached.files
  loading.value = true

  // 加载离线收藏夹列表（备用）
  try {
    offlineFolders.value = await OfflineFavoriteService.getFolders()
  } catch {
    // 离线收藏夹加载失败不影响导入
  }

  // 并发获取相册详情（网络失败不阻塞）
  await PdfImportService.fetchAlbumDetails(files.value)
  loading.value = false
})

// ---- 编辑 ----
function toggleEdit(idx: number) {
  if (editingIdx.value === idx) {
    editingIdx.value = null
    return
  }
  const file = files.value[idx]
  // 用原始文件名 + ID 信息初始化编辑框
  if (file.editedLine) {
    editText.value = file.editedLine
  } else if (file.extractedIds.length > 0) {
    editText.value = file.extractedIds.join(' ')
    if (file.chapterSortOrder != null) {
      editText.value += ` ${file.chapterSortOrder}`
    }
  } else {
    editText.value = ''
  }
  editingIdx.value = idx
}

async function applyEdit(idx: number) {
  const { parseEditedLine } = await import('@/utils/importPdfParse')
  const file = files.value[idx]
  const { ids, chapterSortOrder } = parseEditedLine(editText.value.trim())

  const updated = { ...file, editedLine: editText.value.trim(), editedIds: ids, chapterSortOrder }

  // 编辑后重新判断状态
  if (ids.length === 0) {
    updated.status = 'missing'
  } else if (ids.length === 1) {
    updated.status = 'resolved'
  } else {
    updated.status = 'ambiguous'
  }

  // 如果用户编辑后只剩一个 ID 且之前没有 albumDetail，尝试获取
  if (ids.length === 1 && !updated.albumDetail) {
    try {
      const detail = await JmcomicService.getAlbum(ids[0])
      updated.albumDetail = detail && detail.id ? detail : null
    } catch {
      updated.albumDetail = null
    }
  }

  files.value[idx] = updated as PdfFileParseItem
  editingIdx.value = null
}

// ---- 卡片样式 ----
function cardClass(file: PdfFileParseItem) {
  return {
    'card-resolved': file.status === 'resolved' && file.extractedIds.length === 1 && file.duplicateIds.length === 0,
    'card-ambiguous': file.status === 'ambiguous',
    'card-missing': file.status === 'missing',
    'card-duplicate': file.duplicateIds.length > 0,
  }
}

function statusIcon(file: PdfFileParseItem) {
  if (file.status === 'resolved' && file.extractedIds.length === 1) return checkmarkCircle
  if (file.status === 'ambiguous') return helpCircle
  if (file.status === 'missing') return closeCircle
  if (file.duplicateIds.length > 0) return warning
  return warning
}

function statusIconClass(file: PdfFileParseItem) {
  return {
    'icon-resolved': file.status === 'resolved' && file.extractedIds.length === 1 && file.duplicateIds.length === 0,
    'icon-warning': file.status === 'ambiguous' || file.duplicateIds.length > 0,
    'icon-danger': file.status === 'missing',
  }
}

// ---- 确认流程 ----
async function onConfirm() {
  const resolved = files.value.filter((f) => f.editedIds?.length === 1 || (f.status === 'resolved' && f.extractedIds.length === 1))

  // 确保 resolved 文件有 editedIds
  for (const f of resolved) {
    if (!f.editedIds || f.editedIds.length !== 1) {
      f.editedIds = [...f.extractedIds]
    }
  }

  const unresolved = files.value.filter((f) =>
    !(f.editedIds?.length === 1) && !(f.status === 'resolved' && f.extractedIds.length === 1),
  )

  if (resolved.length === 0) {
    await showToast('没有可导入的文件，请补充 ID 后重试', 'danger')
    return
  }

  if (unresolved.length > 0) {
    const alert = await alertController.create({
      header: '未解决的文件',
      message: `${unresolved.length} 个文件缺少有效 ID，将被忽略。仅导入 ${resolved.length} 个已解析的文件。`,
      buttons: [
        { text: '取消', role: 'cancel' },
        {
          text: '继续导入',
          handler: () => proceedToImport(resolved),
        },
      ],
    })
    await alert.present()
  } else {
    await proceedToImport(resolved)
  }
}

async function proceedToImport(resolvedFiles: PdfFileParseItem[]) {
  pendingResolvedFiles.value = resolvedFiles

  // 询问是否添加到离线收藏夹
  try {
    offlineFolders.value = await OfflineFavoriteService.getFolders()
  } catch {
    // 忽略
  }

  const favAlert = await alertController.create({
    header: '添加到离线收藏夹？',
    message: `是否将这 ${resolvedFiles.length} 个 PDF 添加到离线收藏夹？`,
    buttons: [
      {
        text: '跳过',
        handler: () => doImport(resolvedFiles, undefined),
      },
      {
        text: '选择收藏夹',
        handler: () => {
          showFolderPicker.value = true
        },
      },
    ],
  })
  await favAlert.present()
}

async function onFavFolderSelect(payload: { folderId: string; source: string }) {
  showFolderPicker.value = false
  selectedFolderId.value = payload.folderId
  await doImport(pendingResolvedFiles.value, payload.folderId)
}

async function onAddFolder() {
  // 创建新离线收藏夹
  const alert = await alertController.create({
    header: '新建收藏夹',
    inputs: [{ name: 'name', placeholder: '收藏夹名称' }],
    buttons: [
      { text: '取消', role: 'cancel' },
      {
        text: '创建',
        handler: async (data) => {
          if (data.name) {
            try {
              const folderId = await OfflineFavoriteService.createFolder(data.name)
              selectedFolderId.value = folderId
              // 刷新列表
              offlineFolders.value = await OfflineFavoriteService.getFolders()
              // 创建后直接使用该收藏夹
              showFolderPicker.value = false
              await doImport(pendingResolvedFiles.value, selectedFolderId.value)
            } catch {
              await showToast('创建收藏夹失败', 'danger')
            }
          }
        },
      },
    ],
  })
  await alert.present()
}

async function doImport(resolvedFiles: PdfFileParseItem[], folderId?: string) {
  loading.value = true
  try {
    const result = await PdfImportService.confirmImport(resolvedFiles, folderId)
    await showToast(
      `已导入 ${result.imported} 个 PDF` + (result.skipped > 0 ? `，${result.skipped} 个跳过` : ''),
      'success',
    )
    PdfImportService.clearCachedParseResult()
    router.replace('/download')
  } catch (e: any) {
    await showToast(
      sanitizeError(e, '导入失败'),
      'danger',
    )
  } finally {
    loading.value = false
  }
}

function onBack() {
  PdfImportService.clearCachedParseResult()
  router.replace('/download')
}
</script>

<style scoped>
/* ---- 通用布局 ---- */
IonHeader {
  --ion-background-color: var(--ion-background-color, #fff);
}

.toolbar-start {
  padding: 0 0 8px 14px;
  display: flex;
  align-items: center;
}

.toolbar-title {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  font-size: 16px;
  font-weight: 600;
  white-space: nowrap;
  color: var(--ion-text-color, #222);
}

.toolbar-end {
  padding: 0 14px 8px 0;
  display: flex;
  align-items: center;
}

.confirm-btn {
  background: var(--ion-color-primary, #3880ff);
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
}

.confirm-btn:disabled {
  opacity: 0.4;
}

/* ---- 空状态 ---- */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 20px;
  color: #999;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 12px;
}

/* ---- 统计栏 ---- */
.stats-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px 16px;
  margin: 0 14px;
  background: var(--ion-card-background, #fff);
  border-radius: 10px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.stat {
  font-size: 13px;
  padding: 4px 10px;
  border-radius: 12px;
  font-weight: 500;
}

.stat.resolved {
  background: #e8f5e9;
  color: #2e7d32;
}

.stat.ambiguous {
  background: #fff8e1;
  color: #f57c00;
}

.stat.missing {
  background: #ffebee;
  color: #c62828;
}

.stat.duplicate {
  background: #ede7f6;
  color: #4527a0;
}

/* ---- 文件列表 ---- */
.file-list {
  padding: 12px 14px;
}

/* TransitionGroup 动画 */
.card-list-enter-active,
.card-list-leave-active {
  transition: all 0.3s ease;
}

.card-list-enter-from {
  opacity: 0;
  transform: translateY(16px);
}

.card-list-leave-to {
  opacity: 0;
  transform: translateX(-20px);
}

.card-list-move {
  transition: transform 0.3s ease;
}

/* ---- 文件卡片 ---- */
.file-card {
  background: var(--ion-card-background, #fff);
  border-radius: 10px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.06);
  margin-bottom: 10px;
  overflow: hidden;
  border-left: 4px solid transparent;
  transition: border-color 0.2s;
}

.file-card.card-resolved {
  border-left-color: #4caf50;
}

.file-card.card-ambiguous {
  border-left-color: #ff9800;
}

.file-card.card-missing {
  border-left-color: #f44336;
}

.file-card.card-duplicate {
  border-left-color: #7c4dff;
}

/* ---- 卡片头部 ---- */
.card-header {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  gap: 8px;
}

.status-icon {
  font-size: 20px;
  flex-shrink: 0;
}

.icon-resolved {
  color: #4caf50;
}

.icon-warning {
  color: #ff9800;
}

.icon-danger {
  color: #f44336;
}

.file-name {
  flex: 1;
  font-size: 13px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ion-text-color, #222);
}

.edit-toggle {
  background: none;
  border: none;
  padding: 4px;
  cursor: pointer;
  color: #666;
  font-size: 18px;
}

/* ---- ID 标签 ---- */
.card-body {
  padding: 0 12px 10px 40px;
}

.id-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.tag {
  font-size: 12px;
  padding: 3px 8px;
  border-radius: 10px;
  font-weight: 500;
}

.tag-resolved {
  background: #e8f5e9;
  color: #2e7d32;
}

.tag-ambiguous {
  background: #fff8e1;
  color: #e65100;
}

.tag-candidate {
  background: #f5f5f5;
  color: #666;
}

.tag-missing {
  background: #ffebee;
  color: #c62828;
}

.tag-duplicate {
  background: #ede7f6;
  color: #4527a0;
}

.tag-chapter {
  background: #e3f2fd;
  color: #1565c0;
}

/* ---- 相册预览 ---- */
.album-preview {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  background: #f9f9f9;
  border-radius: 8px;
}

.album-preview.no-detail {
  background: #fff8e1;
}

.album-cover {
  width: 48px;
  height: 64px;
  object-fit: contain;
  border-radius: 4px;
  flex-shrink: 0;
}

.album-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow: hidden;
}

.album-title {
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.album-authors {
  font-size: 12px;
  color: #888;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.no-detail-text {
  font-size: 13px;
  color: #e65100;
}

/* ---- 章节序号 ---- */
.chapter-order-display {
  margin-top: 6px;
  font-size: 12px;
  color: #1565c0;
  font-weight: 500;
}

/* ---- 编辑区 ---- */
.edit-area {
  padding: 8px 12px 10px 40px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.edit-textarea {
  width: 100%;
  border: 1px solid #ddd;
  border-radius: 6px;
  padding: 8px;
  font-size: 14px;
  font-family: monospace;
  resize: none;
  min-height: 36px;
  background: var(--ion-background-color, #fff);
  color: var(--ion-text-color, #222);
}

.edit-hints {
  font-size: 12px;
  color: #999;
}

.edit-hints code {
  background: #f0f0f0;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 11px;
}

.apply-btn {
  align-self: flex-end;
  background: var(--ion-color-primary, #3880ff);
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 13px;
  cursor: pointer;
}

/* ---- 底部间距 ---- */
.bottom-spacer {
  height: 80px;
}
</style>
