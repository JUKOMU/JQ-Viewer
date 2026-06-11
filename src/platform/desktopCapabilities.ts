import type {PlatformCapabilities} from './PlatformCapabilities'
import type {JmcomicListenerHandle} from '../services/jmcomic/JmcomicClient'
import type {AllSettings, DownloadProgressEvent} from '../services/JmcomicTypes'
import {desktopEventUrl, desktopRequest, jsonBody} from '../services/jmcomic/http'
import type {FolderPickPurpose} from '../services/platform/FilePickerPort'

const noopListenerHandle: JmcomicListenerHandle = {
  remove: () => Promise.resolve(),
}

let brightnessOverlay: HTMLDivElement | null = null
let wakeLock: {release: () => Promise<void>} | null = null
let volumeNavigationEnabled = false

type DesktopRoots = {
  pdfRootDir: string
  pdfExportDir: string
}

async function pickDesktopFolder(purpose: FolderPickPurpose = 'pdfRoot') {
  const picked = await desktopRequest<{path?: string; cancelled: boolean}>('/files/pick-directory', {
    method: 'POST',
    body: jsonBody({purpose}),
  })
  if (picked.cancelled || !picked.path) {
    return {path: '', cancelled: true}
  }

  const patch = purpose === 'pdfExport'
    ? {pdfExportDir: picked.path}
    : {pdfRootDir: picked.path}
  const roots = await desktopRequest<DesktopRoots>('/files/roots', {
    method: 'POST',
    body: jsonBody(patch),
  })
  return {
    path: purpose === 'pdfExport' ? roots.pdfExportDir : roots.pdfRootDir,
    cancelled: false,
  }
}

async function updateSettings(patch: Partial<AllSettings>) {
  await desktopRequest<AllSettings>('/settings', {
    method: 'POST',
    body: jsonBody(patch),
  })
  return {success: true}
}

function applyBrightnessOverlay(brightness: number) {
  if (brightness < 0) {
    brightnessOverlay?.remove()
    brightnessOverlay = null
    return
  }

  if (!brightnessOverlay) {
    brightnessOverlay = document.createElement('div')
    brightnessOverlay.style.position = 'fixed'
    brightnessOverlay.style.inset = '0'
    brightnessOverlay.style.pointerEvents = 'none'
    brightnessOverlay.style.zIndex = '2147483647'
    brightnessOverlay.style.background = '#000'
    document.body.appendChild(brightnessOverlay)
  }
  brightnessOverlay.style.opacity = String(Math.max(0, Math.min(0.75, (1 - brightness) * 0.75)))
}

export const desktopCapabilities: PlatformCapabilities = {
  support: {
    onlineFavorites: false,
    offlineFavorites: false,
    parseHistory: false,
    networkProbe: false,
    ocr: false,
    publicDownloads: false,
    notificationPermissionPrompt: false,
    nativeFolderPicker: true,
  },
  readerText: {
    volumeNavigationLabel: '键盘翻页',
    volumeNavigationHint: '方向键、PageUp/PageDown、空格',
  },
  notification: {
    checkPermission() {
      return Promise.resolve({granted: true})
    },
    requestPermission() {
      return Promise.resolve({granted: true})
    },
    ensureDownloadPermission() {
      return Promise.resolve()
    },
  },
  filePicker: {
    pickFolder(purpose) {
      return pickDesktopFolder(purpose)
    },
    requestManageStorage() {
      return Promise.resolve({granted: false, permissionType: 'not_supported', apiLevel: 0})
    },
    async getExternalStoragePath() {
      const roots = await desktopRequest<DesktopRoots>('/files/roots')
      return {path: roots.pdfExportDir}
    },
    checkFilesExist(paths) {
      return desktopRequest<{existing: string[]}>('/files/check', {
        method: 'POST',
        body: jsonBody({paths}),
      })
    },
  },
  readerRuntime: {
    setDisplayMode(mode: string) {
      return updateSettings({readerDisplayMode: mode})
    },
    setScreenOrientation(orientation: string) {
      return updateSettings({readerScreenOrientation: orientation})
    },
    async setBrightness(brightness: number) {
      applyBrightnessOverlay(brightness)
      return updateSettings({readerBrightness: brightness})
    },
    async setKeepScreenOn(enabled: boolean) {
      if (!enabled) {
        if (wakeLock) {
          await wakeLock.release().catch(() => {})
        }
        wakeLock = null
        return updateSettings({readerKeepScreenOn: false})
      }

      const nav = navigator as Navigator & {
        wakeLock?: {request: (type: 'screen') => Promise<{release: () => Promise<void>}>}
      }
      if (!wakeLock && nav.wakeLock) {
        wakeLock = await nav.wakeLock.request('screen').catch(() => null)
      }
      return updateSettings({readerKeepScreenOn: true})
    },
    async setFullscreen(enabled: boolean) {
      if (enabled && !document.fullscreenElement) {
        const requestFullscreen = document.documentElement.requestFullscreen?.bind(document.documentElement)
        if (requestFullscreen) {
          await requestFullscreen().catch(() => {})
        }
      } else if (!enabled && document.fullscreenElement) {
        const exitFullscreen = document.exitFullscreen?.bind(document)
        if (exitFullscreen) {
          await exitFullscreen().catch(() => {})
        }
      }
      return Promise.resolve({success: true})
    },
    async setVolumeNavigation(enabled: boolean) {
      volumeNavigationEnabled = enabled
      return updateSettings({readerVolumeNavigation: enabled})
    },
    setState() {
      return Promise.resolve({success: true})
    },
  },
  events: {
    addImageReadyListener() {
      return Promise.resolve(noopListenerHandle)
    },
    async addDownloadProgressListener(handler) {
      const source = new EventSource(desktopEventUrl('/events'))
      const onMessage = (event: MessageEvent) => {
        try {
          handler(JSON.parse(event.data) as DownloadProgressEvent)
        } catch {
          // Ignore malformed local event payloads.
        }
      }
      source.addEventListener('downloadProgress', onMessage)
      return {
        remove: async () => {
          source.removeEventListener('downloadProgress', onMessage)
          source.close()
        },
      }
    },
    addRelocationProgressListener() {
      return Promise.resolve(noopListenerHandle)
    },
    addNetworkProbeListener() {
      return Promise.resolve(noopListenerHandle)
    },
    addLaunchRouteListener() {
      return Promise.resolve(noopListenerHandle)
    },
    async addVolumeNavigationListener(handler) {
      desktopRequest<AllSettings>('/settings')
        .then((settings) => {
          volumeNavigationEnabled = settings.readerVolumeNavigation
        })
        .catch(() => {})

      const onKeyDown = (event: KeyboardEvent) => {
        if (!volumeNavigationEnabled) return
        if (event.defaultPrevented) return
        if (event.key === 'ArrowUp' || event.key === 'ArrowLeft' || event.key === 'PageUp') {
          handler('up')
        } else if (
          event.key === 'ArrowDown' ||
          event.key === 'ArrowRight' ||
          event.key === 'PageDown' ||
          event.key === ' '
        ) {
          handler('down')
        } else {
          return
        }
        event.preventDefault()
      }
      window.addEventListener('keydown', onKeyDown)
      return {
        remove: async () => {
          window.removeEventListener('keydown', onKeyDown)
        },
      }
    },
    consumeLaunchRoute() {
      return Promise.resolve({})
    },
  },
}
