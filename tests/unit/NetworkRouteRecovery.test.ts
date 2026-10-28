/* eslint-disable vue/one-component-per-file -- test-only Ionic fixtures */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  addClientStateListener: vi.fn(),
  addNetworkProbeListener: vi.fn(),
  addStateInvalidatedListener: vi.fn(),
  getClientState: vi.fn(),
  getDomainStates: vi.fn(),
  getUsedDomain: vi.fn(),
  reprobeDomains: vi.fn(),
  measureLatency: vi.fn(),
  getAllSettings: vi.fn(),
  getImageRouteCandidates: vi.fn(),
  getImageRouteStatus: vi.fn(),
  measureImageHosts: vi.fn(),
  applyImageRoute: vi.fn(),
  routeRefresh: vi.fn(),
  routeSelect: vi.fn(),
  routeState: null as null | {
    mode: ReturnType<typeof ref>
    selectedDomain: ReturnType<typeof ref>
    currentDomain: ReturnType<typeof ref>
    domains: ReturnType<typeof ref>
    loading: ReturnType<typeof ref>
  },
}))

vi.mock('@ionic/vue', () => {
  const withSlot = (name: string, tag = 'div') =>
    defineComponent({
      name,
      setup(_, { slots }) {
        return () => h(tag, slots.default?.())
      },
    })

  return {
    IonBackButton: withSlot('IonBackButton', 'button'),
    IonButtons: withSlot('IonButtons'),
    IonContent: withSlot('IonContent', 'main'),
    IonHeader: withSlot('IonHeader', 'header'),
    IonIcon: withSlot('IonIcon', 'span'),
    IonItem: withSlot('IonItem', 'div'),
    IonLabel: withSlot('IonLabel'),
    IonModal: withSlot('IonModal'),
    IonPage: withSlot('IonPage'),
    IonRadio: withSlot('IonRadio'),
    IonTitle: withSlot('IonTitle'),
    IonToolbar: withSlot('IonToolbar'),
  }
})

vi.mock('ionicons/icons', () => ({
  closeOutline: 'close',
  flashOutline: 'flash',
  refreshOutline: 'refresh',
  speedometerOutline: 'speedometer',
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/home' }),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    addClientStateListener: mocks.addClientStateListener,
    addNetworkProbeListener: mocks.addNetworkProbeListener,
    addStateInvalidatedListener: mocks.addStateInvalidatedListener,
    getClientState: mocks.getClientState,
    getDomainStates: mocks.getDomainStates,
    getUsedDomain: mocks.getUsedDomain,
    reprobeDomains: mocks.reprobeDomains,
    measureLatency: mocks.measureLatency,
    getAllSettings: mocks.getAllSettings,
    getImageRouteCandidates: mocks.getImageRouteCandidates,
    getImageRouteStatus: mocks.getImageRouteStatus,
    measureImageHosts: mocks.measureImageHosts,
    applyImageRoute: mocks.applyImageRoute,
  },
}))

vi.mock('@/composables/useApiRoute', () => ({
  useApiRoute: () => mocks.routeState && {
    ...mocks.routeState,
    refresh: mocks.routeRefresh,
    select: mocks.routeSelect,
  },
}))

vi.mock('@/composables/useTimedReveal', () => ({
  useTimedReveal: () => ({
    visible: ref(true),
    reveal: vi.fn(),
    dispose: vi.fn(),
  }),
}))

import NetworkStatusPage from '@/views/NetworkStatusPage.vue'
import RouteSwitchButton from '@/components/common/RouteSwitchButton.vue'
import { disposeNetworkProbeStore } from '@/composables/networkProbeStore'

let clientStateListener: ((snapshot: { state: string; timestamp: number }) => void) | undefined

beforeEach(() => {
  vi.clearAllMocks()
  clientStateListener = undefined
  mocks.addClientStateListener.mockImplementation(async (listener) => {
    clientStateListener = listener
    return { remove: vi.fn().mockResolvedValue(undefined) }
  })
  mocks.addNetworkProbeListener.mockResolvedValue({
    remove: vi.fn().mockResolvedValue(undefined),
  })
  mocks.addStateInvalidatedListener.mockResolvedValue(null)
  mocks.getClientState.mockResolvedValue({ state: 'initializing', timestamp: 1 })
  mocks.getDomainStates.mockResolvedValue({
    domains: [{ domain: 'api.example', reachable: true }],
    alive: 1,
    total: 1,
    allDeadFallback: false,
  })
  mocks.getUsedDomain.mockResolvedValue({ domain: 'api.example' })
  mocks.reprobeDomains.mockResolvedValue({ success: true })
  mocks.measureLatency.mockResolvedValue({ results: [] })
  mocks.getAllSettings.mockResolvedValue({ imageRouteMode: 'auto', imageRouteHost: '' })
  mocks.getImageRouteCandidates.mockResolvedValue({ hosts: [] })
  mocks.getImageRouteStatus.mockResolvedValue({
    runId: 0,
    revision: 0,
    running: false,
    hosts: [],
  })
  mocks.measureImageHosts.mockResolvedValue({
    selectedHost: 'fast.example',
    hosts: [{ host: 'fast.example', available: true, elapsedMs: 100, bytes: 1000, bytesPerSecond: 10000 }],
    runId: 1,
    revision: 1,
  })
  mocks.routeState = {
    mode: ref('auto'),
    selectedDomain: ref(''),
    currentDomain: ref(null),
    domains: ref([]),
    loading: ref(false),
  }
  mocks.routeRefresh.mockRejectedValue(new Error('在线客户端不可用'))
})

afterEach(async () => {
  await disposeNetworkProbeStore()
})

describe('在线客户端初始化后的线路状态恢复', () => {
  test('网络状态页清除初始化期间的线路错误并重新读取线路', async () => {
    const wrapper = mount(NetworkStatusPage)
    await flushPromises()

    expect(wrapper.text()).toContain('在线客户端不可用')
    mocks.routeRefresh.mockResolvedValue(undefined)
    clientStateListener?.({ state: 'ready', timestamp: 2 })
    await flushPromises()

    expect(mocks.routeRefresh).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('在线客户端不可用')
    wrapper.unmount()
  })

  test('线路弹窗清除初始化期间的错误并重新读取线路', async () => {
    const wrapper = mount(RouteSwitchButton)
    await flushPromises()
    await wrapper.find('button.route-switch-button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('在线客户端不可用')
    const refreshCallsBeforeReady = mocks.routeRefresh.mock.calls.length
    mocks.routeRefresh.mockResolvedValue(undefined)
    clientStateListener?.({ state: 'ready', timestamp: 2 })
    await flushPromises()

    expect(mocks.routeRefresh).toHaveBeenCalledTimes(refreshCallsBeforeReady + 1)
    expect(wrapper.text()).not.toContain('在线客户端不可用')
    wrapper.unmount()
  })
})

describe('图片线路手动模式', () => {
  test('手动模式点击测速只展示结果，不应用测速选出的 Host', async () => {
    mocks.getAllSettings.mockResolvedValue({ imageRouteMode: 'manual', imageRouteHost: 'fixed.example' })
    mocks.getImageRouteCandidates.mockResolvedValue({ hosts: ['fixed.example', 'fast.example'] })
    const wrapper = mount(NetworkStatusPage)
    await flushPromises()

    await wrapper.find('button[aria-label="测量图片线路"]').trigger('click')
    await flushPromises()

    expect(mocks.measureImageHosts).toHaveBeenCalledTimes(1)
    expect(mocks.applyImageRoute).not.toHaveBeenCalled()
    expect(wrapper.find('.image-route-card .summary-domain').text()).toBe('fixed.example')
    expect(wrapper.find('.image-route-card button.domain-row[aria-pressed="true"]').exists()).toBe(true)
    wrapper.unmount()
  })

  test('设置读取失败时保留错误并禁用图片线路操作', async () => {
    mocks.getAllSettings.mockRejectedValue(new Error('设置读取失败'))
    mocks.getImageRouteCandidates.mockResolvedValue({ hosts: ['fast.example'] })
    const wrapper = mount(NetworkStatusPage)
    await flushPromises()

    expect(wrapper.text()).toContain('设置读取失败')
    expect(wrapper.find('button[aria-label="测量图片线路"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.image-route-card button.route-mode-row').attributes('disabled')).toBeDefined()

    await wrapper.find('button[aria-label="测量图片线路"]').trigger('click')
    expect(mocks.measureImageHosts).not.toHaveBeenCalled()
    expect(mocks.applyImageRoute).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
