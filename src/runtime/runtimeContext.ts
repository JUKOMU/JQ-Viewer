import type { FrontendRuntime } from './FrontendRuntime'

let runtime: FrontendRuntime | undefined

/**
 * 注入当前 FrontendRuntime。生产环境只在应用启动时配置一次，
 * 重复配置视为编程错误直接抛出。
 */
export function configureRuntime(value: FrontendRuntime): void {
  if (runtime) throw new Error('Frontend runtime already configured')
  runtime = value
}

/** 获取当前 FrontendRuntime；未配置即使用属于编程错误。 */
export function getRuntime(): FrontendRuntime {
  if (!runtime) throw new Error('Frontend runtime is not configured')
  return runtime
}

/** 仅供测试使用的重置入口；生产环境运行时只配置一次。 */
export function resetRuntimeForTests(): void {
  runtime = undefined
}
