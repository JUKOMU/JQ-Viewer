import { asFolderRef } from '../FileReferences'
import type {
  PdfExportFolderSelection,
  PdfExportPreferences,
  PdfExportPreferencesStore,
} from '../PdfExportPreferences'
import { requestBackend, type BackendFetch } from './backendClient'

interface PdfExportPreferencesResponse {
  exportFolder: { folderRef: string; displayPath: string } | null
  directoryTemplate: string
  fileNameTemplate: string
}

function toPreferences(response: PdfExportPreferencesResponse): PdfExportPreferences {
  return {
    exportFolder: response.exportFolder
      ? {
          folderRef: asFolderRef(response.exportFolder.folderRef),
          displayPath: response.exportFolder.displayPath,
        }
      : null,
    directoryTemplate: response.directoryTemplate,
    fileNameTemplate: response.fileNameTemplate,
  }
}

/** Desktop PDF 导出设置通过 bridge 持久化到 SQLite。 */
export function createDesktopPdfExportPreferencesStore(
  fetcher: BackendFetch,
): PdfExportPreferencesStore {
  return {
    get: () =>
      requestBackend<PdfExportPreferencesResponse>(fetcher, 'getPdfExportPreferences', {}).then(
        toPreferences,
      ),
    setExportFolder: (selection: PdfExportFolderSelection | null) =>
      requestBackend(fetcher, 'setPdfExportFolder', {
        folder: selection
          ? { folderRef: String(selection.folderRef), displayPath: selection.displayPath }
          : null,
      }).then(() => undefined),
    setDirectoryTemplate: (template) =>
      requestBackend(fetcher, 'setPdfExportDirectoryTemplate', { value: template }).then(
        () => undefined,
      ),
    setFileNameTemplate: (template) =>
      requestBackend(fetcher, 'setPdfExportFileNameTemplate', { value: template }).then(
        () => undefined,
      ),
  }
}
