import { describe, expect, test } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import DesktopRouteTrail from '@/components/common/DesktopRouteTrail.vue'
import {
  buildDesktopRouteTrail,
  getDesktopRouteLabel,
  truncateDesktopRouteStack,
  updateDesktopRouteStack,
} from '@/utils/desktopRouteTrail'

describe('桌面端路径栏轨迹', () => {
  test('固定路由和动态路由显示中文标题', () => {
    expect(getDesktopRouteLabel('/history')).toBe('历史')
    expect(getDesktopRouteLabel('/album/123')).toBe('本子详情(123)')
    expect(getDesktopRouteLabel('/album/123/preview/456?page=2')).toBe('章节预览')
  })

  test('不超过九项时保留完整轨迹并删除连续重复项', () => {
    const trail = buildDesktopRouteTrail(['/home', '/history', '/history'], '/setting')

    expect(trail.map((item) => item.path)).toEqual(['/home', '/history', '/setting'])
  })

  test('超过九项时保留第一项、省略项和包含当前项的最后八项', () => {
    const paths = Array.from({ length: 11 }, (_, index) => `/page-${index}`)
    const trail = buildDesktopRouteTrail(paths.slice(0, -1), paths.at(-1) ?? '')

    expect(trail).toHaveLength(10)
    expect(trail[0]).toMatchObject({ path: '/page-0' })
    expect(trail[1]).toMatchObject({ path: null, isEllipsis: true })
    expect(trail.slice(2).map((item) => item.path)).toEqual([
      '/page-3',
      '/page-4',
      '/page-5',
      '/page-6',
      '/page-7',
      '/page-8',
      '/page-9',
      '/page-10',
    ])
  })

  test('点击历史路径项后跳转到对应路由', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/home', component: { template: '<div />' } },
        { path: '/setting', component: { template: '<div />' } },
        { path: '/favorite', component: { template: '<div />' } },
      ],
    })
    await router.push('/favorite')
    await router.isReady()

    const wrapper = mount(DesktopRouteTrail, {
      props: {
        routeStack: ['/home', '/setting'],
        currentPath: '/favorite',
      },
      global: { plugins: [router] },
      attrs: { onNavigate: (payload: { path: string }) => void router.push(payload.path) },
    })

    await wrapper.get('a[aria-label="返回设置"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/setting')

    const currentLink = wrapper.get('a[aria-current="page"]')
    expect(currentLink.text()).toBe('收藏夹')
  })

  test('普通前进导航再次打开旧本子时继续追加轨迹', () => {
    const routeStack = ['/home', '/history', '/album/123']

    expect(updateDesktopRouteStack(routeStack, '/history', '/album/123', 'forward')).toEqual([
      '/home',
      '/history',
      '/album/123',
      '/history',
    ])
  })

  test('返回或路径栏跳转才截断到目标轨迹', () => {
    const routeStack = ['/home', '/history', '/album/123']

    expect(updateDesktopRouteStack(routeStack, '/history', '/album/123', 'back')).toEqual([
      '/home',
      '/history',
    ])
    expect(updateDesktopRouteStack(routeStack, '/history', '/album/123', 'trail')).toEqual([
      '/home',
      '/history',
    ])
  })

  test('重复路径点击按节点索引截断，而不是按 URL 误判当前节点', () => {
    const routeStack = ['/history', '/album/123']
    const trail = buildDesktopRouteTrail(routeStack, '/history')

    expect(trail[0]).toMatchObject({ path: '/history', trailIndex: 0 })
    expect(truncateDesktopRouteStack(routeStack, '/history', trail[0].trailIndex)).toEqual([])
  })

  test('历史导航监听区分后退与前进，普通 push 不残留方向', async () => {
    const history = createMemoryHistory()
    const router = createRouter({
      history,
      routes: [
        { path: '/history', component: { template: '<div />' } },
        { path: '/album/123', component: { template: '<div />' } },
      ],
    })
    let navigationDirection: 'back' | 'forward' | null = null
    const removeHistoryListener = history.listen((_to, _from, info) => {
      navigationDirection =
        info.direction === 'back' || info.direction === 'forward' ? info.direction : null
    })

    await router.push('/history')
    await router.push('/album/123')
    await router.back()
    expect(navigationDirection).toBe('back')

    await router.forward()
    expect(navigationDirection).toBe('forward')

    await router.back()
    expect(navigationDirection).toBe('back')

    navigationDirection = null
    await router.push('/album/123')
    expect(navigationDirection).toBeNull()
    removeHistoryListener()
  })
})
