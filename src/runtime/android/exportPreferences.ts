import { asFolderRef } from '../FileReferences'
import {
  DEFAULT_EXPORT_DIRECTORY_TEMPLATE,
  DEFAULT_EXPORT_FILE_NAME_TEMPLATE,
  defaultExportPreferences,
  type ExportFolderSelection,
  type ExportPreferencesStore,
} from '../ExportPreferences'

const KEY_EXPORT_PATH = 'jq-pdf-export-path'
const KEY_DIR_TEMPLATE = 'jq-pdf-dir-template'
const KEY_NAME_TEMPLATE = 'jq-pdf-name-template'

function readExportFolder(): ExportFolderSelection | null {
  try {
    const raw = localStorage.getItem(KEY_EXPORT_PATH)
    if (!raw) return null
    if (!raw.trim().startsWith('{')) {
      localStorage.removeItem(KEY_EXPORT_PATH)
      return null
    }

    const value: unknown = JSON.parse(raw)
    if (
      !value ||
      typeof value !== 'object' ||
      typeof (value as { folderRef?: unknown }).folderRef !== 'string' ||
      typeof (value as { displayPath?: unknown }).displayPath !== 'string' ||
      !(value as { folderRef: string }).folderRef
    ) {
      localStorage.removeItem(KEY_EXPORT_PATH)
      return null
    }

    const folder = value as { folderRef: string; displayPath: string }
    return { folderRef: asFolderRef(folder.folderRef), displayPath: folder.displayPath }
  } catch {
    localStorage.removeItem(KEY_EXPORT_PATH)
    return null
  }
}

function readText(key: string, fallback: string): string {
  try {
    return localStorage.getItem(key) || fallback
  } catch {
    return fallback
  }
}

/** Android 保留现有 localStorage 行为，Desktop 不使用此 adapter。 */
export function createAndroidExportPreferencesStore(): ExportPreferencesStore {
  return {
    get: async () => ({
      ...defaultExportPreferences(),
      exportFolder: readExportFolder(),
      directoryTemplate: readText(KEY_DIR_TEMPLATE, DEFAULT_EXPORT_DIRECTORY_TEMPLATE),
      fileNameTemplate: readText(KEY_NAME_TEMPLATE, DEFAULT_EXPORT_FILE_NAME_TEMPLATE),
    }),
    setExportFolder: async (selection) => {
      if (!selection) {
        localStorage.removeItem(KEY_EXPORT_PATH)
        return
      }
      localStorage.setItem(
        KEY_EXPORT_PATH,
        JSON.stringify({
          folderRef: String(selection.folderRef),
          displayPath: selection.displayPath,
        }),
      )
    },
    setDirectoryTemplate: async (template) => {
      if (template === null) localStorage.removeItem(KEY_DIR_TEMPLATE)
      else localStorage.setItem(KEY_DIR_TEMPLATE, template)
    },
    setFileNameTemplate: async (template) => {
      if (template === null) localStorage.removeItem(KEY_NAME_TEMPLATE)
      else localStorage.setItem(KEY_NAME_TEMPLATE, template)
    },
  }
}
