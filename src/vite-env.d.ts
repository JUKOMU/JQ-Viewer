/// <reference types="vite/client" />

import type { RuntimePlatform } from './runtime/FrontendRuntime'

declare global {
  const __JQ_RUNTIME_PLATFORM__: RuntimePlatform
}

export {}
