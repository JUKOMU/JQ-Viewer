import { afterEach, describe, expect, test, vi } from 'vitest'
import { useTimedReveal } from '@/composables/useTimedReveal'

describe('useTimedReveal', () => {
  afterEach(() => vi.useRealTimers())

  test('显示期间的操作不延长显示时长，隐藏后才允许再次显示', () => {
    vi.useFakeTimers()
    const reveal = useTimedReveal(1000)

    reveal.reveal()
    vi.advanceTimersByTime(500)
    reveal.reveal()
    vi.advanceTimersByTime(500)
    expect(reveal.visible.value).toBe(false)

    reveal.reveal()
    expect(reveal.visible.value).toBe(true)
    vi.advanceTimersByTime(1000)
    expect(reveal.visible.value).toBe(false)
  })
})
