import type { FolderRef } from './FileReferences'

export const DEFAULT_EXPORT_PATH = 'Download/JQ-Viewer/'
export const DEFAULT_EXPORT_DIRECTORY_TEMPLATE = '{id}'
export const DEFAULT_EXPORT_FILE_NAME_TEMPLATE = '【{author}】{title}_{id} {chapterRange}'

export interface ExportFolderSelection {
  folderRef: FolderRef
  displayPath: string
}

export interface ExportPreferences {
  exportFolder: ExportFolderSelection | null
  directoryTemplate: string
  fileNameTemplate: string
}

/** PDF 导出设置的窄持久化端口；页面只通过 ExportService 使用它。 */
export interface ExportPreferencesStore {
  get(): Promise<ExportPreferences>
  setExportFolder(selection: ExportFolderSelection | null): Promise<void>
  setDirectoryTemplate(template: string | null): Promise<void>
  setFileNameTemplate(template: string | null): Promise<void>
}

export function defaultExportPreferences(): ExportPreferences {
  return {
    exportFolder: null,
    directoryTemplate: DEFAULT_EXPORT_DIRECTORY_TEMPLATE,
    fileNameTemplate: DEFAULT_EXPORT_FILE_NAME_TEMPLATE,
  }
}
