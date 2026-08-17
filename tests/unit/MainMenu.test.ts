import {nextTick, ref} from 'vue'
import {mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, test, vi} from 'vitest'
import MainMenu from '@/components/menu/MainMenu.vue'
import {
  isMenuNavigation,
  leftMenuGestureEnabled,
  leftMenuOpen,
  rightMenuOpen,
} from '@/composables/useSideMenuState'

const mocks = vi.hoisted(() => ({
  gestureConfig: undefined as Record<string, any> | undefined,
  routerPush: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({path: '/home'}),
  useRouter: () => ({push: mocks.routerPush}),
}))

vi.mock('@/composables/useAuth', () => ({
  useAuth: () => ({userInfo: ref(null), isLoggedIn: ref(false)}),
}))

vi.mock('@ionic/vue', async () => {
  const {defineComponent: makeComponent, h: makeH} = await import('vue')
  const withSlot = (name: string, tag = 'div') =>
    makeComponent({
      name,
      inheritAttrs: false,
      setup(_, {attrs, slots}) {
        return () => makeH(tag, attrs, slots.default?.())
      },
    })

  return {
    createGesture: vi.fn((config: Record<string, any>) => {
      mocks.gestureConfig = config
      return {destroy: vi.fn(), enable: vi.fn()}
    }),
    IonContent: withSlot('IonContent'),
    IonHeader: withSlot('IonHeader'),
    IonIcon: withSlot('IonIcon'),
    IonItem: withSlot('IonItem', 'button'),
    IonLabel: withSlot('IonLabel'),
    IonList: withSlot('IonList'),
  }
})

const mountMenu = () => {
  const content = document.createElement('main')
  content.id = 'main-content'
  document.body.appendChild(content)
  return mount(MainMenu, {props: {contentId: 'main-content'}})
}

const getGesture = () => {
  expect(mocks.gestureConfig).toBeDefined()
  return mocks.gestureConfig!
}

beforeEach(() => {
  leftMenuOpen.value = false
  leftMenuGestureEnabled.value = true
  rightMenuOpen.value = false
  isMenuNavigation.value = false
  mocks.gestureConfig = undefined
  mocks.routerPush.mockClear()
})

afterEach(() => {
  document.getElementById('main-content')?.remove()
  leftMenuOpen.value = false
  leftMenuGestureEnabled.value = true
  rightMenuOpen.value = false
  isMenuNavigation.value = false
})

describe('MainMenu 自定义左侧手势', () => {
  test('使用低识别阈值、严格横向角度并只在捕获后锁滚动', () => {
    const wrapper = mountMenu()
    const gesture = getGesture()

    expect(gesture.threshold).toBe(6)
    expect(gesture.maxAngle).toBe(30)
    expect(gesture.direction).toBe('x')
    expect(gesture.passive).toBe(true)
    expect(gesture.disableScroll).toBe(true)

    wrapper.unmount()
  })

  test('短距离或低速横滑达到低门槛后打开菜单', async () => {
    const wrapper = mountMenu()
    const gesture = getGesture()

    gesture.onStart()
    gesture.onMove({deltaX: 60})
    gesture.onEnd({velocityX: 0})
    await nextTick()

    expect(leftMenuOpen.value).toBe(true)
    expect(wrapper.find('.main-menu').classes()).toContain('interactive')

    wrapper.unmount()
  })

  test('快速轻扫可以用更小的移动距离打开菜单', async () => {
    const wrapper = mountMenu()
    const gesture = getGesture()

    gesture.onStart()
    gesture.onMove({deltaX: 8})
    gesture.onEnd({velocityX: 0.13})
    await nextTick()

    expect(leftMenuOpen.value).toBe(true)
    wrapper.unmount()
  })

  test('打开状态下向左移动较小幅度即可关闭', async () => {
    const wrapper = mountMenu()
    leftMenuOpen.value = true
    await nextTick()

    const gesture = getGesture()
    gesture.onStart()
    gesture.onMove({deltaX: -60})
    gesture.onEnd({velocityX: 0})
    await nextTick()

    expect(leftMenuOpen.value).toBe(false)
    wrapper.unmount()
  })

  test('详情页禁用全局开启手势，但打开后仍允许关闭', () => {
    const wrapper = mountMenu()
    const gesture = getGesture()

    leftMenuGestureEnabled.value = false
    expect(gesture.canStart({event: {target: document.body}})).toBe(false)

    leftMenuOpen.value = true
    expect(gesture.canStart({event: {target: document.body}})).toBe(true)
    wrapper.unmount()
  })

  test('右侧菜单打开或交互控件触摸时不启动左侧栏', () => {
    const wrapper = mountMenu()
    const gesture = getGesture()
    const range = document.createElement('input')

    rightMenuOpen.value = true
    expect(gesture.canStart({event: {target: document.body}})).toBe(false)
    rightMenuOpen.value = false
    expect(gesture.canStart({event: {target: range}})).toBe(false)

    wrapper.unmount()
  })
})
