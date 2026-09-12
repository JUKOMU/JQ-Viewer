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
    orientation: unavailableCapability('当前平台不支持屏幕方向控制'),
    brightness: unavailableCapability('当前平台不支持屏幕亮度控制'),
    keepAwake: unavailableCapability('当前平台不支持防止熄屏'),
    fullscreen: unavailableCapability('当前平台不支持全屏控制'),
    volumeKeys: unavailableCapability('当前平台不支持音量键翻页'),
    hostState: unavailableCapability('当前平台不支持阅读器宿主状态控制'),
  }
}

/** 提供明确的当前平台能力状态。 */
export function createPlatformServices(events: BackendEvents): PlatformServices {
  const appInfo: AppInfo = {
    name: 'JQ Viewer',
    version: packageInfo.version,
  }

  const platformServices = {
    app: { getInfo: async () => appInfo },
    notifications: { kind: 'host-managed' },
    storage: unavailableCapability('当前平台不支持公开下载'),
    reader: createUnavailableReaderServices(),
    updater: unavailableCapability('当前平台不支持应用更新'),
    ocr: unavailableCapability('当前平台不支持 OCR'),
    launchRoutes: unavailableCapability('当前平台不支持启动路由'),
    events,
  }

  // 未提供的服务保持缺失，避免将未实现能力伪装成可用接口。
  return platformServices as unknown as PlatformServices
}
