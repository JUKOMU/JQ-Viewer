import type {EventPort} from '../services/platform/EventPort'
import type {FilePickerPort} from '../services/platform/FilePickerPort'
import type {MaintenancePort} from '../services/platform/MaintenancePort'
import type {NotificationPort} from '../services/platform/NotificationPort'
import type {ReaderRuntimePort} from '../services/platform/ReaderRuntimePort'

export interface PlatformFeatureSupport {
  onlineFavorites: boolean
  offlineFavorites: boolean
  parseHistory: boolean
  networkProbe: boolean
  ocr: boolean
  publicDownloads: boolean
  notificationPermissionPrompt: boolean
  nativeFolderPicker: boolean
}

export interface PlatformReaderText {
  volumeNavigationLabel: string
  volumeNavigationHint: string
}

export interface PlatformCapabilities {
  support: PlatformFeatureSupport
  readerText: PlatformReaderText
  notification: NotificationPort
  filePicker: FilePickerPort
  maintenance: MaintenancePort
  readerRuntime: ReaderRuntimePort
  events: EventPort
}
