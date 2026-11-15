import { JmcomicService } from './JmcomicService'
import type { DownloadTask, RetainedDownloadExport, RetainedExportCandidate } from './JmcomicTypes'

/** 候选来源仅由原图已清理的保留项提供，异常文件不能让该身份消失。 */
export const isRetainedDownload = (task: DownloadTask) =>
  Boolean(task.retainedExportCandidates?.length || task.retainedExports?.length)

export const retainedFileStatusLabel = (task: DownloadTask) => {
  if (!isRetainedDownload(task)) return ''
  return {
    unknown: '待校验',
    available: '',
    missing: '文件缺失',
    inaccessible: '无法读取',
    invalid: '文件损坏',
  }[task.retainedFileStatus ?? 'unknown']
}

/** 按整章选择同一导出任务的分卷，保留无可读来源时的诊断和删除范围。 */
export function selectRetainedExport(task: DownloadTask): DownloadTask {
  const candidates = task.retainedExportCandidates
  if (!candidates?.length) return task
  const groups = new Map<string, RetainedExportCandidate[]>()
  for (const volume of candidates) {
    const key = volume.exportId
    const group = groups.get(key) ?? []
    group.push(volume)
    groups.set(key, group)
  }
  const complete = (volumes: RetainedExportCandidate[]) =>
    volumes.every((volume) => volume.fileId != null && volume.availability === 'available') &&
    volumes.reduce((total, volume) => total + volume.pageCount, 0) === task.totalPages
  const volumes = [...groups.values()].find(complete) ?? [...groups.values()][0]
  const readable = complete(volumes)
  const status = readable ? 'available' : (
    volumes.find((volume) => volume.availability !== 'available')?.availability ?? 'invalid'
  )
  return {
    ...task,
    retainedExports: readable ? volumes as RetainedDownloadExport[] : [],
    retainedFileStatus: status,
  }
}

export function retainedDeletionFiles(task: DownloadTask): RetainedExportCandidate[] {
  const candidates = task.retainedExportCandidates ?? task.retainedExports ?? []
  const exportId = task.retainedExports?.[0]?.exportId ?? candidates[0]?.exportId
  return candidates.filter((volume) => volume.exportId === exportId) as RetainedExportCandidate[]
}

/** 下载视图独立校验当前页来源，共享分卷只校验一次。 */
export async function verifyRetainedDownloads(tasks: DownloadTask[]): Promise<DownloadTask[]> {
  const ids = [...new Set(tasks.flatMap((task) =>
    (task.retainedExportCandidates ?? []).flatMap((volume) =>
      volume.fileId == null ? [] : [volume.fileId]),
  ))]
  const files = ids.length ? (await JmcomicService.refreshLocalFileAvailability(ids)).files : []
  const verified = new Map(files.map((file) => [file.id, file]))
  return tasks.map((task) => {
    if (!task.retainedExportCandidates?.length) return task
    return selectRetainedExport({
      ...task,
      retainedExportCandidates: task.retainedExportCandidates.map((volume) => ({
        ...volume,
        availability: volume.fileId == null ? 'missing'
          : verified.get(volume.fileId)?.availability ?? 'missing',
      })),
    })
  })
}

/** 删除文件成功后才移除选中记录；共享文件的其他章节由自己的校验更新。 */
export async function deleteRetainedDownloads(tasks: DownloadTask[]): Promise<void> {
  const ids = new Set(tasks.flatMap((task) => retainedDeletionFiles(task)
    .flatMap((volume) => volume.fileId == null ? [] : [volume.fileId])))
  for (const id of ids) {
    const existing = await JmcomicService.getLocalFiles({ fileId: id, limit: 1 })
    if (existing.files.length) await JmcomicService.deleteLocalFile(id)
  }
  for (const task of tasks) {
    await JmcomicService.removeRetainedDownload(task.albumId, task.chapterId)
  }
}
