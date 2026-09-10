import type { FrontendRuntime } from '../FrontendRuntime'
import { createDesktopBackendClient, type DesktopFetch } from './desktopBackendClient'
import { createDesktopBackendEvents } from './desktopBackendEvents'
import { createDesktopPlatformServices } from './desktopPlatformServices'
import { createDesktopResourceResolver } from './desktopResourceResolver'

/** Assembles the phase-1 Desktop runtime over same-origin HTTP. */
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
