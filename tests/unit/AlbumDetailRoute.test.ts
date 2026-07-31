import {describe, expect, test} from 'vitest'
import router from '@/router'

describe('AlbumDetailPage 路由状态', () => {
  test('详情页加入 keep-alive 缓存以支持从阅读页返回', () => {
    const route = router.getRoutes().find((item) => item.path === '/album/:id')

    expect(route?.name).toBe('AlbumDetailPage')
    expect(route?.meta.keepAlive).toBe(true)
  })
})
