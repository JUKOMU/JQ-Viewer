export type RuntimeErrorCode =
  | 'unavailable'
  | 'permission-denied'
  | 'cancelled'
  | 'not-found'
  | 'conflict'
  | 'network'
  | 'internal'

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

function getSafeMessage(value: unknown): string {
  if (value instanceof RuntimeError) return value.message
  if (value instanceof Error && value.message) return value.message
  if (typeof value === 'string' && value.trim()) return value
  return '平台操作失败'
}

/**
 * Only structured codes cross the adapter boundary. Natural-language native
 * rejection messages are deliberately kept as internal errors.
 */
export function normalizeRuntimeError(value: unknown, fallback = '平台操作失败'): RuntimeError {
  if (value instanceof RuntimeError) return value

  const candidate = value as { code?: unknown; errorCode?: unknown; message?: unknown } | null
  const rawCode = candidate?.code ?? candidate?.errorCode
  const code = isRuntimeErrorCode(rawCode) ? rawCode : 'internal'
  const message = getSafeMessage(value) || fallback
  return new RuntimeError(code, message, { cause: value })
}

export async function withRuntimeError<T>(operation: () => Promise<T>): Promise<T> {
  try {
    return await operation()
  } catch (error) {
    throw normalizeRuntimeError(error)
  }
}
