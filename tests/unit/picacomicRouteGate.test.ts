import { describe, expect, test } from 'vitest'
import router, { registerPicacomicRoutes } from '@/router'
import {
  picacomicAuthGuard,
  redirectFromPicacomicRoute,
  shouldExposePicacomicRoutes,
} from '@/features/picacomic/routeGate'
import { usePicacomicAuth } from '@/features/picacomic/auth'
import { createPicacomicRoutes } from '@/features/picacomic/routes'

describe('Picacomic debug route gate', () => {
  test('only test/development or native debug builds expose the route set', () => {
    expect(shouldExposePicacomicRoutes('production')).toBe(false)
    expect(shouldExposePicacomicRoutes('production', true)).toBe(true)
    expect(shouldExposePicacomicRoutes('development')).toBe(true)
    expect(shouldExposePicacomicRoutes('test')).toBe(true)
  })

  test('safe redirect contains only structured route identity', () => {
    expect(
      redirectFromPicacomicRoute({
        name: 'PicacomicReaderPage',
        params: { albumId: 'album-1', chapterId: 'chapter-1' },
      } as never),
    ).toEqual({
      name: 'PicacomicReaderPage',
      params: { albumId: 'album-1', chapterId: 'chapter-1' },
    })
    expect(
      JSON.stringify(
        redirectFromPicacomicRoute({
          name: 'PicacomicReaderPage',
          params: { albumId: 'album-1', chapterId: 'chapter-1' },
        } as never),
      ),
    ).not.toMatch(/password|token|https?:\/\//i)
  })

  test('route records stay internal and out of the main menu', () => {
    const routes = createPicacomicRoutes()
    expect(routes.map((route) => route.name)).toEqual([
      'PicacomicLoginPage',
      'PicacomicBrowsePage',
      'PicacomicAlbumPage',
      'PicacomicReaderPage',
    ])
    expect(routes.every((route) => route.meta?.menu === false)).toBe(true)
    expect(routes.every((route) => route.meta?.picacomicInternal === true)).toBe(true)
    expect(router.getRoutes().some((route) => route.name === 'PicacomicBrowsePage')).toBe(false)
  })

  test('auth guard stores only a structured redirect for signed-out readers', async () => {
    const result = await picacomicAuthGuard({
      name: 'PicacomicReaderPage',
      params: { albumId: 'fixture-album-1', chapterId: 'chapter-1' },
    } as never)

    expect(result).toEqual({ name: 'PicacomicLoginPage' })
    expect(usePicacomicAuth().consumeRedirect()).toEqual({
      name: 'PicacomicReaderPage',
      params: { albumId: 'fixture-album-1', chapterId: 'chapter-1' },
    })
  })

  test('test mode registers the isolated route set dynamically', async () => {
    await expect(registerPicacomicRoutes()).resolves.toBe(true)
    expect(router.getRoutes().map((route) => route.name)).toContain('PicacomicBrowsePage')
  })
})
