import type { FrontendRuntime } from '../FrontendRuntime'
import { createDesktopBackendClient, type DesktopFetch } from './desktopBackendClient'
import { createDesktopBackendEvents } from './desktopBackendEvents'
import { createDesktopPlatformServices } from './desktopPlatformServices'
import { createDesktopResourceResolver } from './desktopResourceResolver'

/** 通过同源 HTTP 组装 Desktop 运行时。 */
export function createDesktopRuntime(fetcher?: DesktopFetch): FrontendRuntime {
  const events = createDesktopBackendEvents()
  return {
    platform: 'linux',
    backend: createDesktopBackendClient(fetcher),
    events,
    resources: createDesktopResourceResolver(),
    services: createDesktopPlatformServices(events),
  }
}
