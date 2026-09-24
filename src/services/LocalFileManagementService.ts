import { JmcomicService } from './JmcomicService'
import type { FileRef } from '@/runtime/FileReferences'
import type {
  LocalFileRecord,
  ExportProgressEvent,
  ExportStatus,
  ExportTaskRecord,
} from './JmcomicTypes'

const PAGE_SIZE = 50

export interface LocalFileFilters {
  formats?: LocalFileRecord['format'][]
  sourceType?: 'imported' | 'exported'
  folderId?: string
  query?: string
}

export interface ExportTaskFilters {
  format?: ExportTaskRecord['format']
  status?: ExportStatus
}

export interface LocalFilePage<T> {
  items: T[]
  nextCursor: string | null
}

function normalizedQuery(query?: string): string | undefined {
  const value = query?.trim()
  return value || undefined
}

export function mergeLocalFiles(
  current: LocalFileRecord[],
  incoming: LocalFileRecord[],
): LocalFileRecord[] {
  const byId = new Map(current.map((file) => [file.id, file]))
  for (const file of incoming) byId.set(file.id, file)
  return Array.from(byId.values())
}

export function mergeExportTasks(
  current: ExportTaskRecord[],
  incoming: ExportTaskRecord[],
): ExportTaskRecord[] {
  const byId = new Map(current.map((task) => [task.exportId, task]))
  for (const task of incoming) {
    const existing = byId.get(task.exportId)
    if (!existing || task.snapshotRevision >= existing.snapshotRevision) {
      byId.set(task.exportId, task)
    }
  }
  return Array.from(byId.values())
}

export function applyExportProgressEvent(
  tasks: ExportTaskRecord[],
  event: ExportProgressEvent,
): ExportTaskRecord[] | null {
  const index = tasks.findIndex((task) => task.exportId === event.exportId)
  if (index < 0) return null
  if (event.snapshotRevision < tasks[index].snapshotRevision) return tasks
  const next = tasks.slice()
  next[index] = { ...next[index], ...event }
  return next
}

export const LocalFileManagementService = {
  async getFiles(
    filters: LocalFileFilters,
    cursor?: string,
  ): Promise<LocalFilePage<LocalFileRecord>> {
    const result = await JmcomicService.getLocalFiles({
      ...filters,
      query: normalizedQuery(filters.query),
      cursor,
      limit: PAGE_SIZE,
    })
    return { items: result.files, nextCursor: result.nextCursor || null }
  },

  async refreshFiles(ids: number[]): Promise<LocalFileRecord[]> {
    if (!ids.length) return []
    return (await JmcomicService.refreshLocalFileAvailability(ids)).files
  },

  async getTasks(
    filters: ExportTaskFilters,
    cursor?: string,
  ): Promise<LocalFilePage<ExportTaskRecord>> {
    const result = await JmcomicService.getExportTasks({
      ...filters,
      cursor,
      limit: PAGE_SIZE,
    })
    return { items: result.tasks, nextCursor: result.nextCursor || null }
  },

  getManagementState: JmcomicService.getLocalFileManagementState,
  acknowledgeDatabaseReset: JmcomicService.acknowledgeLocalFileDatabaseReset,
  getFolders: JmcomicService.getOfflineFolders,
  getTask: JmcomicService.getExportTask,
  addProgressListener: JmcomicService.addExportProgressListener,
  inspectFileForDeletion: JmcomicService.inspectLocalFileForDeletion,
  openFolder: (file: FileRef) => JmcomicService.openLocalFileFolder(file),
  removeFile: JmcomicService.removeLocalFileFromLibrary,
  deleteFile: JmcomicService.deleteLocalFile,
  verifyFile: JmcomicService.verifyLocalFile,
  cancelTask: JmcomicService.cancelExport,
  retryTask: JmcomicService.retryExport,
  deleteTaskRecord: JmcomicService.deleteExportTask,
  pickFolder: JmcomicService.pickFolder,
}
