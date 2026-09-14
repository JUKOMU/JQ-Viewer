import { describe, expect, test, vi } from 'vitest'

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    addNetworkProbeListener: vi.fn().mockRejectedValue(new Error('unavailable')),
  },
}))

describe('networkProbeStore', () => {
  test('runtime 未实现域名状态方法时初始化不抛出同步异常', async () => {
    const { initNetworkProbeStore } = await import('@/composables/networkProbeStore')

    expect(() => initNetworkProbeStore()).not.toThrow()
    await Promise.resolve()
  })
})
