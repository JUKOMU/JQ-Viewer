import { jmcomicNativeClient } from '@/services/jmcomic/JmcomicNativeClient'
import type { JmcomicClient } from '@/services/jmcomic/JmcomicClient'
import { createAndroidBackendClient } from './androidBackendClient'
import { createAndroidBackendEvents } from './androidBackendEvents'
import { createAndroidPlatformServices } from './androidPlatformServices'
import { createAndroidResourceResolver } from './androidResourceResolver'
import type { FrontendRuntime } from '../FrontendRuntime'

export function createAndroidRuntime(native: JmcomicClient = jmcomicNativeClient): FrontendRuntime {
  const events = createAndroidBackendEvents(native)
  return {
    platform: 'android',
    backend: createAndroidBackendClient(native),
    events,
    resources: createAndroidResourceResolver(native),
    services: createAndroidPlatformServices(native, events),
  }
}
