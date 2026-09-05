import type { FrontendRuntime } from './FrontendRuntime'

let runtime: FrontendRuntime | undefined

export function configureRuntime(value: FrontendRuntime): void {
  if (runtime) throw new Error('Frontend runtime already configured')
  runtime = value
}

export function getRuntime(): FrontendRuntime {
  if (!runtime) throw new Error('Frontend runtime is not configured')
  return runtime
}

/** Test-only reset hook. Production bootstrap configures the runtime once. */
export function resetRuntimeForTests(): void {
  runtime = undefined
}
