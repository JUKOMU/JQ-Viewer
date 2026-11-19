export type ExportErrorContext =
  | 'create'
  | 'submit'
  | 'overwrite'
  | 'task'
  | 'load'
  | 'cancel'
  | 'retry'
  | 'delete'
  | 'progress'

const exportErrorMessages: Record<string, string> = {
  TARGET_INVALID: '导出路径无效。请重新选择导出目录或检查文件名后重试。',
  TARGET_INACCESSIBLE: '导出目录不可用。请选择可写的导出目录后重试。',
  OUTPUT_EXISTS: '目标文件已存在。确认覆盖后重试。',
  DOWNLOAD_NOT_COMPLETED: '下载内容未完成。请先完成下载后重试。',
  DOWNLOAD_METADATA_INCOMPLETE: '下载信息不完整。请刷新下载任务后重试。',
  DOWNLOAD_MANIFEST_INVALID: '下载内容不完整。请重新下载后重试。',
  DOWNLOAD_FILE_INVALID: '下载文件无效。请重新下载后重试。',
  TASK_CONFLICT: '导出任务已存在。请等待当前任务完成。',
  QUEUE_REJECTED: '导出队列不可用。请稍后重试。',
  PROCESS_INTERRUPTED: '导出任务已中断。请点击重试重新导出。',
  CANCELLED: '导出已取消。请点击重试重新导出。',
  RETRY_INPUT_CHANGED: '导出内容已变化。请重新提交导出。',
  SOURCE_CLEANUP_FAILED: '导出已完成，但源文件清理失败。请稍后检查文件管理。',
  EXPORT_FAILED: '导出失败。请检查导出目录和存储空间后重试。',
  INTERNAL_ERROR: '导出失败。请检查导出目录和存储空间后重试。',
  INTERNAL: '导出失败。请检查导出目录和存储空间后重试。',
}

const contextFallbacks: Record<ExportErrorContext, string> = {
  create: '导出任务无法创建。请检查导出设置后重试。',
  submit: '导出启动失败。请检查导出目录和下载内容后重试。',
  overwrite: '覆盖导出失败。请检查导出目录和下载内容后重试。',
  task: '导出失败。请检查导出目录和存储空间后重试。',
  load: '导出任务加载失败。请稍后重试。',
  cancel: '取消导出失败。请刷新任务列表后重试。',
  retry: '重试导出失败。请检查导出目录和下载内容后重试。',
  delete: '导出任务记录删除失败。请稍后重试。',
  progress: '导出进度暂时不可用。请稍后刷新任务列表。',
}

function normalizedCode(value: unknown): string | undefined {
  if (typeof value !== 'string' || !value.trim()) return undefined
  const code = value.trim().toUpperCase()
  return code.replace(/^(PDF|CBZ|ZIP)_/, '')
}

function errorCode(error: unknown): string | undefined {
  if (typeof error === 'string') return normalizedCode(error)
  const candidate = error as { code?: unknown; errorCode?: unknown } | null
  return normalizedCode(candidate?.errorCode ?? candidate?.code)
}

function runtimeErrorMessage(code: string): string | undefined {
  if (code === 'PERMISSION-DENIED') return '导出目录不可用。请检查存储权限后重试。'
  if (code === 'NOT-FOUND') return '导出任务不存在。请刷新任务列表后重试。'
  if (code === 'CONFLICT') return '导出任务状态已变化。请刷新后重试。'
  if (code === 'NETWORK') return '导出请求失败。请检查网络连接后重试。'
  if (code === 'UNAVAILABLE') return '导出功能暂时不可用。请稍后重试。'
  return undefined
}

export function getExportErrorMessage(
  code: unknown,
  context: ExportErrorContext = 'task',
): string {
  const normalized = normalizedCode(code)
  if (!normalized) return contextFallbacks[context]
  return exportErrorMessages[normalized] ?? runtimeErrorMessage(normalized) ?? contextFallbacks[context]
}

export function getExportErrorMessageFrom(
  error: unknown,
  context: ExportErrorContext,
): string {
  const normalized = errorCode(error)
  return normalized ? getExportErrorMessage(normalized, context) : contextFallbacks[context]
}
