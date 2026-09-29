import { describe, expect, test } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import DesktopRouteTrail from '@/components/common/DesktopRouteTrail.vue'
import { buildDesktopRouteTrail, getDesktopRouteLabel } from '@/utils/desktopRouteTrail'

describe('桌面端路径栏轨迹', () => {
  test('固定路由和动态路由显示中文标题', () => {
    expect(getDesktopRouteLabel('/history')).toBe('历史')
    expect(getDesktopRouteLabel('/album/123')).toBe('漫画详情')
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
    })

    await wrapper.get('a[aria-label="返回设置"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/setting')
  })
})
