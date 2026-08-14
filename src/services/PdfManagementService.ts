import { JmcomicService } from './JmcomicService'
import type {
  ImportedPdf,
  PdfExportProgressEvent,
  PdfExportStatus,
  PdfExportTaskRecord,
} from './JmcomicTypes'

const PAGE_SIZE = 50

export interface PdfFileFilters {
  sourceType?: 'imported' | 'exported'
  folderId?: string
  query?: string
}

export interface PdfTaskFilters {
  status?: PdfExportStatus
}

export interface PdfPage<T> {
  items: T[]
  nextCursor: string | null
}

function normalizedQuery(query?: string): string | undefined {
  const value = query?.trim()
  return value || undefined
}

export function mergePdfFiles(current: ImportedPdf[], incoming: ImportedPdf[]): ImportedPdf[] {
  const byId = new Map(current.map((file) => [file.id, file]))
  for (const file of incoming) byId.set(file.id, file)
  return Array.from(byId.values())
}

export function mergePdfTasks(
  current: PdfExportTaskRecord[],
  incoming: PdfExportTaskRecord[],
): PdfExportTaskRecord[] {
  const byId = new Map(current.map((task) => [task.exportId, task]))
  for (const task of incoming) {
    const existing = byId.get(task.exportId)
    if (!existing || task.snapshotRevision >= existing.snapshotRevision) {
      byId.set(task.exportId, task)
    }
  }
  return Array.from(byId.values())
}

export function applyPdfProgressEvent(
  tasks: PdfExportTaskRecord[],
  event: PdfExportProgressEvent,
): PdfExportTaskRecord[] | null {
  const index = tasks.findIndex((task) => task.exportId === event.exportId)
  if (index < 0) return null
  if (event.snapshotRevision < tasks[index].snapshotRevision) return tasks
  const next = tasks.slice()
  next[index] = { ...next[index], ...event }
  return next
}

export const PdfManagementService = {
  async getFiles(filters: PdfFileFilters, cursor?: string): Promise<PdfPage<ImportedPdf>> {
    const result = await JmcomicService.getPdfFiles({
      ...filters,
      query: normalizedQuery(filters.query),
      cursor,
      limit: PAGE_SIZE,
    })
    return { items: result.files, nextCursor: result.nextCursor || null }
  },

  async refreshFiles(ids: number[]): Promise<ImportedPdf[]> {
    if (!ids.length) return []
    return (await JmcomicService.refreshPdfFileAvailability(ids)).files
  },

  async getTasks(filters: PdfTaskFilters, cursor?: string): Promise<PdfPage<PdfExportTaskRecord>> {
    const result = await JmcomicService.getPdfExportTasks({
      ...filters,
      cursor,
      limit: PAGE_SIZE,
    })
    return { items: result.tasks, nextCursor: result.nextCursor || null }
  },

  getManagementState: JmcomicService.getPdfManagementState,
  acknowledgeDatabaseReset: JmcomicService.acknowledgePdfDatabaseReset,
  getFolders: JmcomicService.getOfflineFolders,
  getTask: JmcomicService.getPdfExportTask,
  addProgressListener: JmcomicService.addPdfExportProgressListener,
  inspectFileForDeletion: JmcomicService.inspectPdfFileForDeletion,
  openFolder: JmcomicService.openPdfFolder,
  removeFile: JmcomicService.removePdfFromLibrary,
  deleteFile: JmcomicService.deletePdfFile,
  verifyFile: JmcomicService.verifyPdfFile,
  cancelTask: JmcomicService.cancelPdfExport,
  retryTask: JmcomicService.retryPdfExport,
  deleteTaskRecord: JmcomicService.deletePdfExportTask,
  pickFolder: JmcomicService.pickFolder,
}
