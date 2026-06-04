import type {EventPort} from '../services/platform/EventPort'
import type {FilePickerPort} from '../services/platform/FilePickerPort'
import type {NotificationPort} from '../services/platform/NotificationPort'
import type {ReaderRuntimePort} from '../services/platform/ReaderRuntimePort'

export interface PlatformCapabilities {
  notification: NotificationPort
  filePicker: FilePickerPort
  readerRuntime: ReaderRuntimePort
  events: EventPort
}
