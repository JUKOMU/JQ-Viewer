import { jmcomicNativeClient } from '@/services/jmcomic/JmcomicNativeClient'
import type { JmcomicClient } from '@/services/jmcomic/JmcomicClient'
import { createAndroidBackendClient } from './androidBackendClient'
import { createAndroidBackendEvents } from './androidBackendEvents'
import { createAndroidPlatformServices } from './androidPlatformServices'
import { createAndroidResourceResolver } from './androidResourceResolver'
import type { FrontendRuntime } from '../FrontendRuntime'

/**
 * 装配完整的 Android FrontendRuntime：默认绑定现有 Capacitor 原生客户端，
 * 把后端、事件、资源与平台能力统一转换为平台无关契约。
 */
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
