import type {PlatformCapabilities} from './PlatformCapabilities'
import type {JmcomicListenerHandle} from '../services/jmcomic/JmcomicClient'

const noopListenerHandle: JmcomicListenerHandle = {
  remove: () => Promise.resolve(),
}

export const desktopCapabilities: PlatformCapabilities = {
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
    pickFolder() {
      return Promise.resolve({path: '', cancelled: true})
    },
    requestManageStorage() {
      return Promise.resolve({granted: false, permissionType: 'not_supported', apiLevel: 0})
    },
    getExternalStoragePath() {
      return Promise.resolve({path: ''})
    },
    checkFilesExist() {
      return Promise.resolve({existing: []})
    },
  },
  readerRuntime: {
    setDisplayMode() {
      return Promise.resolve({success: true})
    },
    setScreenOrientation() {
      return Promise.resolve({success: true})
    },
    setBrightness() {
      return Promise.resolve({success: true})
    },
    setKeepScreenOn() {
      return Promise.resolve({success: true})
    },
    setFullscreen() {
      return Promise.resolve({success: true})
    },
    setVolumeNavigation() {
      return Promise.resolve({success: true})
    },
    setState() {
      return Promise.resolve({success: true})
    },
  },
  events: {
    addImageReadyListener() {
      return Promise.resolve(noopListenerHandle)
    },
    addDownloadProgressListener() {
      return Promise.resolve(noopListenerHandle)
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
    addVolumeNavigationListener() {
      return Promise.resolve(noopListenerHandle)
    },
    consumeLaunchRoute() {
      return Promise.resolve({})
    },
  },
}
