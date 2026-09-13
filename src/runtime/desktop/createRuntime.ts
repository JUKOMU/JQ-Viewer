import type { FrontendRuntime } from '../FrontendRuntime'
import type { RuntimePlatform } from '../FrontendRuntime'
import { createBackendClient, type BackendFetch } from './backendClient'
import { createBackendEvents } from './backendEvents'
import { createPlatformServices } from './platformServices'
import { createResourceResolver } from './resourceResolver'

/** 通过同源 HTTP 组装运行时。 */
export function createRuntime(platform: RuntimePlatform, fetcher?: BackendFetch): FrontendRuntime {
  const events = createBackendEvents()
  const backendFetch = fetcher ?? globalThis.fetch.bind(globalThis)
  return {
    platform,
    backend: createBackendClient(backendFetch),
    events,
    resources: createResourceResolver(),
    services: createPlatformServices(events, backendFetch),
  }
}
