import { describe, expect, test } from 'vitest'
import router from '@/router'

describe('图片缓存页路由', () => {
  test('通过独立的非菜单路由返回设置页', () => {
    const route = router.getRoutes().find((item) => item.path === '/cache')

    expect(route?.name).toBe('CachePage')
    expect(route?.meta.menu).toBe(false)
    expect(route?.meta.keepAlive).toBe(false)
  })
})
