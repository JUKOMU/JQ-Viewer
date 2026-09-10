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
    orientation: unavailableCapability('Screen orientation is unavailable in Desktop phase 1'),
    brightness: unavailableCapability('Screen brightness is unavailable in Desktop phase 1'),
    keepAwake: unavailableCapability('Keep-awake is unavailable in Desktop phase 1'),
    fullscreen: unavailableCapability('Fullscreen is unavailable in Desktop phase 1'),
    volumeKeys: unavailableCapability('Volume-key navigation is unavailable in Desktop phase 1'),
    hostState: unavailableCapability('Reader host state is unavailable in Desktop phase 1'),
  }
}

/** Supplies explicit unavailable capabilities until later Desktop phases add real services. */
export function createDesktopPlatformServices(events: BackendEvents): PlatformServices {
  const appInfo: AppInfo = {
    name: 'JQ Viewer',
    version: packageInfo.version,
  }

  const phase1Services = {
    app: { getInfo: async () => appInfo },
    notifications: { kind: 'host-managed' },
    storage: unavailableCapability('Desktop storage is unavailable in phase 1'),
    reader: createUnavailableReaderServices(),
    updater: unavailableCapability('Desktop updater is unavailable in phase 1'),
    ocr: unavailableCapability('Desktop OCR is unavailable in phase 1'),
    launchRoutes: unavailableCapability('Desktop launch routes are unavailable in phase 1'),
    events,
  }

  // Files and PDF services are intentionally absent until their Desktop phases are implemented.
  return phase1Services as unknown as PlatformServices
}
