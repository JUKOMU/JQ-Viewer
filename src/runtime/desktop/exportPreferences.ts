import { asFolderRef } from '../FileReferences'
import type {
  ExportFolderSelection,
  ExportPreferences,
  ExportPreferencesStore,
} from '../ExportPreferences'
import { requestBackend, type BackendFetch } from './backendClient'

interface ExportPreferencesResponse {
  exportFolder: { folderRef: string; displayPath: string } | null
  directoryTemplate: string
  fileNameTemplate: string
}

function toPreferences(response: ExportPreferencesResponse): ExportPreferences {
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
export function createDesktopExportPreferencesStore(fetcher: BackendFetch): ExportPreferencesStore {
  return {
    get: () =>
      requestBackend<ExportPreferencesResponse>(fetcher, 'getExportPreferences', {}).then(
        toPreferences,
      ),
    setExportFolder: (selection: ExportFolderSelection | null) =>
      requestBackend(fetcher, 'setExportFolder', {
        folder: selection
          ? { folderRef: String(selection.folderRef), displayPath: selection.displayPath }
          : null,
      }).then(() => undefined),
    setDirectoryTemplate: (template) =>
      requestBackend(fetcher, 'setExportDirectoryTemplate', { value: template }).then(
        () => undefined,
      ),
    setFileNameTemplate: (template) =>
      requestBackend(fetcher, 'setExportFileNameTemplate', { value: template }).then(
        () => undefined,
      ),
  }
}
