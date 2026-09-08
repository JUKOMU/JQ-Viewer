import { COMMON_BACKEND_METHODS, type BackendClient } from '../BackendClient'
import type { JmcomicClient } from '@/services/jmcomic/JmcomicClient'

/**
 * 构造 Android 的 BackendClient 实现。
 * 仅绑定经过审阅的通用方法白名单：完整的 Capacitor 契约仍对本 adapter 可用，
 * 但不会泄漏到公共业务服务，从而把 Android 专属能力隔离在 transport 边界内。
 */
export function createAndroidBackendClient(native: JmcomicClient): BackendClient {
  const backend = {} as BackendClient
  for (const method of COMMON_BACKEND_METHODS) {
    ;(backend as Record<string, unknown>)[method] = native[method].bind(native)
  }
  return backend
}
