import { beforeEach, describe, expect, test, vi } from 'vitest'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  vi.resetModules()
})

describe('ReaderSettingPersistence', () => {
  test('同一设置串行保存，连续失败时仅最新操作回滚到最后确认值', async () => {
    const first = deferred<void>()
    const second = deferred<void>()
    const persistFirst = vi.fn(() => first.promise)
    const persistSecond = vi.fn(() => second.promise)
    const { persistReaderSettingValue } = await import('@/services/ReaderSettingPersistence')

    const firstOperation = persistReaderSettingValue(
      'orientation',
      'portrait',
      'auto',
      persistFirst,
    )
    const secondOperation = persistReaderSettingValue(
      'orientation',
      'landscape',
      'portrait',
      persistSecond,
    )

    await vi.waitFor(() => expect(persistFirst).toHaveBeenCalledTimes(1))
    expect(persistSecond).not.toHaveBeenCalled()
    const firstFailure = expect(firstOperation.promise).rejects.toThrow('first failure')
    first.reject(new Error('first failure'))
    await firstFailure
    await vi.waitFor(() => expect(persistSecond).toHaveBeenCalledTimes(1))

    const secondFailure = expect(secondOperation.promise).rejects.toThrow('second failure')
    second.reject(new Error('second failure'))
    await secondFailure

    expect(firstOperation.isLatest()).toBe(false)
    expect(secondOperation.isLatest()).toBe(true)
    expect(secondOperation.getConfirmedValue()).toBe('auto')
  })

  test('前一操作成功而最新操作失败时回滚到前一确认值', async () => {
    const first = deferred<void>()
    const second = deferred<void>()
    const persistFirst = vi.fn(() => first.promise)
    const persistSecond = vi.fn(() => second.promise)
    const { persistReaderSettingValue } = await import('@/services/ReaderSettingPersistence')

    const firstOperation = persistReaderSettingValue('brightness', 0.5, -1, persistFirst)
    const secondOperation = persistReaderSettingValue('brightness', 0.8, 0.5, persistSecond)

    await vi.waitFor(() => expect(persistFirst).toHaveBeenCalledTimes(1))
    first.resolve()
    await firstOperation.promise
    await vi.waitFor(() => expect(persistSecond).toHaveBeenCalledTimes(1))

    const secondFailure = expect(secondOperation.promise).rejects.toThrow('second failure')
    second.reject(new Error('second failure'))
    await secondFailure

    expect(secondOperation.getConfirmedValue()).toBe(0.5)
  })
})
