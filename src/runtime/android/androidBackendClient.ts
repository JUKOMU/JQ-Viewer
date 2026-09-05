import { COMMON_BACKEND_METHODS, type BackendClient } from '../BackendClient'
import type { JmcomicClient } from '@/services/jmcomic/JmcomicClient'

/**
 * Bind only the reviewed common method allowlist. The full Capacitor contract
 * remains available to this adapter, but cannot leak into common services.
 */
export function createAndroidBackendClient(native: JmcomicClient): BackendClient {
  const backend = {} as BackendClient
  for (const method of COMMON_BACKEND_METHODS) {
    ;(backend as Record<string, unknown>)[method] = native[method].bind(native)
  }
  return backend
}
