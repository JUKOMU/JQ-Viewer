import {alertController} from '@ionic/vue'
import type {PlatformCapabilities} from './PlatformCapabilities'
import type {ImageReadyEvent, JmcomicClient} from '../services/jmcomic/JmcomicClient'
import {jmcomicNativeClient} from '../services/jmcomic/JmcomicNativeClient'

const native: JmcomicClient = jmcomicNativeClient

let downloadNotificationPrompted = false
let downloadNotificationPromptPromise: Promise<void> | null = null

async function ensureDownloadNotificationPermission(): Promise<void> {
  if (downloadNotificationPrompted) return
  if (downloadNotificationPromptPromise) return downloadNotificationPromptPromise

  downloadNotificationPromptPromise = (async () => {
    try {
      const check = await native.checkNotificationPermission()
      if (check.granted) {
        downloadNotificationPrompted = true
        return
      }

      const alert = await alertController.create({
        header: '需要通知权限',
        message: '章节下载将在后台进行，需要通过通知查看进度。拒绝后仍会继续下载，但不会显示系统通知。',
        buttons: [
          {text: '暂不授权', role: 'cancel'},
          {text: '允许通知', role: 'confirm'},
        ],
      })
      await alert.present()
      const dismissed = await alert.onDidDismiss()
      if (dismissed.role === 'confirm') {
        await native.requestNotificationPermission()
      }
    } catch {
      // Web 调试或旧系统异常时不阻塞下载提交。
    } finally {
      downloadNotificationPrompted = true
      downloadNotificationPromptPromise = null
    }
  })()

  return downloadNotificationPromptPromise
}

export const androidCapabilities: PlatformCapabilities = {
  support: {
    onlineFavorites: true,
    offlineFavorites: true,
    parseHistory: true,
    networkProbe: true,
    ocr: true,
    publicDownloads: true,
    notificationPermissionPrompt: true,
    nativeFolderPicker: true,
  },
  readerText: {
    volumeNavigationLabel: '音量键翻页',
    volumeNavigationHint: '使用音量键上/下翻页',
  },
  notification: {
    checkPermission() {
      return native.checkNotificationPermission()
    },
    requestPermission() {
      return native.requestNotificationPermission()
    },
    ensureDownloadPermission() {
      return ensureDownloadNotificationPermission()
    },
  },
  filePicker: {
    pickFolder() {
      return native.pickFolder()
    },
    requestManageStorage() {
      return native.requestManageStorage()
    },
    getExternalStoragePath() {
      return native.getExternalStoragePath()
    },
    checkFilesExist(paths: string[]) {
      return native.checkFilesExist({paths})
    },
  },
  maintenance: {
    getDiagnosticsStatus() {
      return Promise.resolve(null)
    },
    openDirectory() {
      return Promise.resolve({success: false})
    },
  },
  readerRuntime: {
    setDisplayMode(mode: string) {
      return native.setReaderDisplayMode({mode})
    },
    setScreenOrientation(orientation: string) {
      return native.setReaderScreenOrientation({orientation})
    },
    setBrightness(brightness: number) {
      return native.setReaderBrightness({brightness})
    },
    setKeepScreenOn(enabled: boolean) {
      return native.setReaderKeepScreenOn({enabled})
    },
    setFullscreen(enabled: boolean) {
      return native.setReaderFullscreen({enabled})
    },
    setVolumeNavigation(enabled: boolean) {
      return native.setReaderVolumeNavigation({enabled})
    },
    setState(isActive: boolean, isVertical: boolean) {
      return native.setReaderState({isActive, isVertical})
    },
  },
  events: {
    addImageReadyListener(photoId: string, handler: (sortOrder: number) => void) {
      return native.addListener('imageReady', (data: ImageReadyEvent) => {
        if (data.photoId === photoId) {
          handler(data.sortOrder)
        }
      })
    },
    addDownloadProgressListener(handler) {
      return native.addListener('downloadProgress', handler)
    },
    addRelocationProgressListener(handler) {
      return native.addListener('relocationProgress', handler)
    },
    addNetworkProbeListener(handler) {
      return native.addListener('networkProbe', handler)
    },
    addLaunchRouteListener(handler) {
      return native.addListener('launchRoute', handler)
    },
    addVolumeNavigationListener(handler) {
      return native.addListener('volumeKey', (data: { direction: 'up' | 'down' }) => {
        handler(data.direction)
      })
    },
    consumeLaunchRoute() {
      return native.consumeLaunchRoute()
    },
  },
}
