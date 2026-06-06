import type {EventPort} from '../services/platform/EventPort'
import type {FilePickerPort} from '../services/platform/FilePickerPort'
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

export interface PlatformCapabilities {
  support: PlatformFeatureSupport
  notification: NotificationPort
  filePicker: FilePickerPort
  readerRuntime: ReaderRuntimePort
  events: EventPort
}
