import type { PicacomicErrorCode } from './types'

export class PicacomicServiceError extends Error {
  readonly code: PicacomicErrorCode
  readonly retryable: boolean

  constructor(code: PicacomicErrorCode, operation: string, cause?: unknown) {
    super(`${operation} failed (${code})`)
    this.name = 'PicacomicServiceError'
    this.code = code
    this.retryable = ['PICACOMIC_NETWORK', 'PICACOMIC_RATE_LIMITED', 'PICACOMIC_UPSTREAM'].includes(
      code,
    )
    if (cause !== undefined) this.cause = cause
  }
}
