import type { FrontendRuntime } from '../FrontendRuntime'
import type { RuntimePlatform } from '../FrontendRuntime'
import { createBackendClient, type BackendFetch } from './backendClient'
import { createBackendEvents } from './backendEvents'
import { createPlatformServices } from './platformServices'
import { createResourceResolver } from './resourceResolver'
import { RuntimeError } from '../errors'

type NavigatorWithPlatform = Navigator & {
  userAgentData?: { platform?: string }
}

/** 根据宿主浏览器暴露的系统平台构造运行时。 */
export function detectRuntimePlatform(): RuntimePlatform {
  const navigator = globalThis.navigator as NavigatorWithPlatform | undefined
  const value = [navigator?.userAgentData?.platform, navigator?.platform, navigator?.userAgent]
    .filter(Boolean)
    .join(' ')
    .toLowerCase()

  if (value.includes('win')) return 'windows'
  if (value.includes('mac') || value.includes('darwin')) return 'macos'
  if (value.includes('linux')) return 'linux'
  throw new RuntimeError('internal', '无法识别当前桌面平台')
}

/** 通过同源 HTTP 组装运行时。 */
export function createRuntime(
  platform: RuntimePlatform = detectRuntimePlatform(),
  fetcher?: BackendFetch,
): FrontendRuntime {
  const events = createBackendEvents()
  return {
    platform,
    backend: createBackendClient(fetcher),
    events,
    resources: createResourceResolver(),
    services: createPlatformServices(events),
  }
}
