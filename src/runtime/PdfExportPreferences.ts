import type { FolderRef } from './FileReferences'

export const DEFAULT_PDF_EXPORT_PATH = 'Download/JQ-Viewer/'
export const DEFAULT_PDF_DIRECTORY_TEMPLATE = '{id}'
export const DEFAULT_PDF_FILE_NAME_TEMPLATE = '【{author}】{title}_{id} {chapterRange}'

export interface PdfExportFolderSelection {
  folderRef: FolderRef
  displayPath: string
}

export interface PdfExportPreferences {
  exportFolder: PdfExportFolderSelection | null
  directoryTemplate: string
  fileNameTemplate: string
}

/** PDF 导出设置的窄持久化端口；页面只通过 PdfExportService 使用它。 */
export interface PdfExportPreferencesStore {
  get(): Promise<PdfExportPreferences>
  setExportFolder(selection: PdfExportFolderSelection | null): Promise<void>
  setDirectoryTemplate(template: string | null): Promise<void>
  setFileNameTemplate(template: string | null): Promise<void>
}

export function defaultPdfExportPreferences(): PdfExportPreferences {
  return {
    exportFolder: null,
    directoryTemplate: DEFAULT_PDF_DIRECTORY_TEMPLATE,
    fileNameTemplate: DEFAULT_PDF_FILE_NAME_TEMPLATE,
  }
}
