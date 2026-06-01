<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <div class="toolbar-start">
          <IonBackButton :default-href="'/download'"/>
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
          <!-- 封面区 -->
          <div class="cover-wrap">
            <img
              v-if="file.albumDetail?.image"
              :src="file.albumDetail.image"
              class="cover-img"
            />
            <div v-else class="cover-placeholder">
              <IonIcon :icon="documentTextOutline"/>
            </div>
          </div>

          <!-- 信息区 -->
          <div class="info" @click="editingIdx !== idx ? undefined : undefined">
            <h3 class="item-title">
              {{ file.albumDetail?.title || '未识别本子' }}
            </h3>
            <div class="item-meta file-name-line">{{ file.fileName }}</div>
            <div v-if="file.albumDetail?.authors?.length" class="item-meta">
              作者：{{ file.albumDetail.authors.join(' / ') }}
            </div>
            <div v-if="file.albumDetail?.tags?.length" class="item-tags">
              <span
                v-for="t in file.albumDetail.tags.slice(0, 10)"
                :key="t"
                class="tag-chip"
              >{{ t }}</span>
              <span v-if="file.albumDetail.tags.length > 10" class="tag-chip tag-more">...</span>
            </div>
            <div class="status-row">
              <span class="status-tag" :class="statusTagClass(file)">{{ statusTagText(file) }}</span>
              <span v-if="file.chapterSortOrder != null" class="status-tag chapter-tag">
                第{{ file.chapterSortOrder }}话
              </span>
            </div>
          </div>

          <!-- 编辑按钮 -->
          <button class="edit-btn" @click.stop="toggleEdit(idx)">
            <IonIcon :icon="editingIdx === idx ? checkmarkOutline : createOutline"/>
          </button>

          <!-- 编辑模式遮罩 -->
          <div v-show="editingIdx === idx" class="edit-overlay">
            <textarea
              v-model="editText"
              class="edit-textarea"
              rows="2"
              @keydown.enter.prevent="applyEdit(idx)"
            />
            <div class="edit-actions">
              <span class="edit-hint">末尾空格+数字=章节序号，如 <code>123456 3</code></span>
              <button class="apply-btn" @click="applyEdit(idx)">应用</button>
            </div>
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

import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { IonPage, IonHeader, IonToolbar, IonBackButton, IonContent, IonIcon } from '@ionic/vue'
import { alertController } from '@ionic/vue'
import { useRouter } from 'vue-router'
import { documentTextOutline, createOutline, checkmarkOutline } from 'ionicons/icons'
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

function statusTagText(file: PdfFileParseItem): string {
  const id = file.editedIds?.[0] || file.extractedIds[0]
  if (file.status === 'missing') return '未识别ID'
  if (file.status === 'ambiguous') {
    return `候选: ${file.extractedIds.slice(0, 3).join(', ')}${file.extractedIds.length > 3 ? '...' : ''}`
  }
  if (file.duplicateIds.length > 0) return `ID重复 #${id}`
  return `已解析 #${id}`
}

function statusTagClass(file: PdfFileParseItem): string {
  if (file.status === 'missing') return 'tag-missing'
  if (file.status === 'ambiguous') return 'tag-ambiguous'
  if (file.duplicateIds.length > 0) return 'tag-duplicate'
  return 'tag-resolved'
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

onBeforeUnmount(() => {
  PdfImportService.clearCachedParseResult()
})
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

/* ---- 文件卡片（SearchResultContainer 风格）---- */
.file-card {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  background: #fffaf6;
  border-radius: 12px;
  box-shadow: 5px 12px 28px rgb(76 42 24 / 0.2);
  margin-bottom: 10px;
  padding: 0 10px 0 0;
  overflow: hidden;
  border-left: 4px solid transparent;
  transition: border-color 0.2s;
  position: relative;
  min-height: 108px;
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

/* ---- 封面 ---- */
.cover-wrap {
  width: 80px;
  aspect-ratio: 3 / 4;
  flex-shrink: 0;
  border-radius: 6px;
  overflow: hidden;
  background: linear-gradient(145deg, #f3ded0, #ffece0);
}

.cover-img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
}

.cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c4a48d;
  font-size: 28px;
}

/* ---- 信息区 ---- */
.info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 8px 2px 8px 4px;
}

.item-title {
  font-size: 11px;
  font-weight: 700;
  line-height: 1.4;
  color: #30201a;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin: 0;
}

.item-meta {
  font-size: 11px;
  line-height: 1.35;
  color: #876653;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-name-line {
  word-break: break-all;
  white-space: normal;
}

/* ---- 标签 chips ---- */
.item-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.tag-chip {
  max-width: 120px;
  padding: 3px 7px;
  border-radius: 999px;
  background: #fff0e7;
  color: #9b5a35;
  font-size: 10px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tag-chip.tag-more {
  background: transparent;
  color: #9b5a35;
  padding: 3px 4px;
}

/* ---- 状态标签行 ---- */
.status-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 2px;
}

.status-tag {
  font-size: 10px;
  padding: 3px 7px;
  border-radius: 999px;
  font-weight: 500;
  white-space: nowrap;
}

.status-tag.tag-resolved {
  background: #e8f5e9;
  color: #2e7d32;
}

.status-tag.tag-ambiguous {
  background: #fff8e1;
  color: #e65100;
}

.status-tag.tag-missing {
  background: #ffebee;
  color: #c62828;
}

.status-tag.tag-duplicate {
  background: #ede7f6;
  color: #4527a0;
}

.status-tag.chapter-tag {
  background: #e3f2fd;
  color: #1565c0;
}

/* ---- 编辑按钮（右上角，仿 SearchResultContainer 的 ⋮ 按钮位置）---- */
.edit-btn {
  position: absolute;
  right: 2px;
  top: 50%;
  transform: translateY(-50%);
  background: none;
  border: none;
  padding: 6px;
  cursor: pointer;
  color: #b0886a;
  font-size: 16px;
  z-index: 1;
}

/* ---- 编辑遮罩 ---- */
.edit-overlay {
  position: absolute;
  inset: 0;
  background: #fffaf6;
  border-radius: 12px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 2;
}

.edit-textarea {
  flex: 1;
  width: 100%;
  border: 1px solid #e0c8b0;
  border-radius: 6px;
  padding: 8px;
  font-size: 13px;
  font-family: monospace;
  resize: none;
  min-height: 40px;
  background: #fff;
  color: #4c2a18;
}

.edit-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.edit-hint {
  font-size: 11px;
  color: #b09880;
}

.edit-hint code {
  background: #f0ede8;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 10px;
}

.apply-btn {
  background: var(--ion-color-primary, #3880ff);
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 5px 12px;
  font-size: 12px;
  cursor: pointer;
  flex-shrink: 0;
}

/* ---- 底部间距 ---- */
.bottom-spacer {
  height: 80px;
}
</style>
