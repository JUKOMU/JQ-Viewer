import packageInfo from '../../../package.json'
import type {
  AppInfo,
  Capability,
  PlatformServices,
  ReaderPlatformServices,
} from '../PlatformServices'
import type { BackendEvents } from '../BackendEvents'

function unavailableCapability<T>(reason: string): Capability<T> {
  return { available: false, reason }
}

function createUnavailableReaderServices(): ReaderPlatformServices {
  return {
    orientation: unavailableCapability('Screen orientation is unavailable on Desktop'),
    brightness: unavailableCapability('Screen brightness is unavailable on Desktop'),
    keepAwake: unavailableCapability('Keep-awake is unavailable on Desktop'),
    fullscreen: unavailableCapability('Fullscreen is unavailable on Desktop'),
    volumeKeys: unavailableCapability('Volume-key navigation is unavailable on Desktop'),
    hostState: unavailableCapability('Reader host state is unavailable on Desktop'),
  }
}

/** 提供明确的 Desktop 平台能力状态。 */
export function createDesktopPlatformServices(events: BackendEvents): PlatformServices {
  const appInfo: AppInfo = {
    name: 'JQ Viewer',
    version: packageInfo.version,
  }

  const desktopServices = {
    app: { getInfo: async () => appInfo },
    notifications: { kind: 'host-managed' },
    storage: unavailableCapability('Desktop storage is unavailable'),
    reader: createUnavailableReaderServices(),
    updater: unavailableCapability('Desktop updater is unavailable'),
    ocr: unavailableCapability('Desktop OCR is unavailable'),
    launchRoutes: unavailableCapability('Desktop launch routes are unavailable'),
    events,
  }

  // 未提供的服务保持缺失，避免将未实现能力伪装成可用接口。
  return desktopServices as unknown as PlatformServices
}
