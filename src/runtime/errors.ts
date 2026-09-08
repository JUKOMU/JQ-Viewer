/** 跨平台稳定的错误码。页面据此分流交互，而不是依赖 transport 或后端异常文案。 */
export type RuntimeErrorCode =
  | 'unavailable'
  | 'permission-denied'
  | 'cancelled'
  | 'not-found'
  | 'conflict'
  | 'network'
  | 'internal'

/** 带稳定 code 的小型错误，用于在 adapter 边界统一各类底层 rejection。 */
export class RuntimeError extends Error {
  readonly code: RuntimeErrorCode

  constructor(code: RuntimeErrorCode, message: string, options?: { cause?: unknown }) {
    super(message, options)
    this.name = 'RuntimeError'
    this.code = code
  }
}

function isRuntimeErrorCode(value: unknown): value is RuntimeErrorCode {
  return (
    value === 'unavailable' ||
    value === 'permission-denied' ||
    value === 'cancelled' ||
    value === 'not-found' ||
    value === 'conflict' ||
    value === 'network' ||
    value === 'internal'
  )
}

/** 从任意值中提取安全的展示消息，避免把底层异常文本原样暴露给页面。 */
function getSafeMessage(value: unknown): string {
  if (value instanceof RuntimeError) return value.message
  if (value instanceof Error && value.message) return value.message
  if (typeof value === 'string' && value.trim()) return value
  const message = (value as { message?: unknown } | null)?.message
  if (typeof message === 'string' && message.trim()) return message
  return '平台操作失败'
}

/**
 * 将底层错误归一为 RuntimeError：
 * 只有机器可读的结构化 code（code/errorCode）才可跨 adapter 边界使用；
 * 自然语言的原生 rejection 消息有意保留为 internal，不据此猜业务错误码。
 */
export function normalizeRuntimeError(value: unknown, fallback = '平台操作失败'): RuntimeError {
  if (value instanceof RuntimeError) return value

  const candidate = value as { code?: unknown; errorCode?: unknown; message?: unknown } | null
  const rawCode = candidate?.code ?? candidate?.errorCode
  const code = isRuntimeErrorCode(rawCode) ? rawCode : 'internal'
  const message = getSafeMessage(value) || fallback
  return new RuntimeError(code, message, { cause: value })
}

/** 执行一个异步操作，并把抛出的任意底层错误归一为 RuntimeError。 */
export async function withRuntimeError<T>(operation: () => Promise<T>): Promise<T> {
  try {
    return await operation()
  } catch (error) {
    throw normalizeRuntimeError(error)
  }
}
