import { describe, expect, test } from 'vitest'
import { createPicacomicFixtureClient } from '@/features/picacomic/fixture'
import {
  PicacomicService,
  PicacomicServiceError,
  picacomicErrorMessage,
} from '@/features/picacomic/service'

describe('PicacomicService fake contract', () => {
  test('normal mapping and pagination stay inside the Picacomic façade', async () => {
    const fixture = createPicacomicFixtureClient()
    const service = new PicacomicService(fixture)

    await expect(service.getAuthState()).resolves.toEqual({ state: 'signed_out' })
    await service.login('fixture-user', 'fixture-password')

    const first = await service.search('fixture', 1)
    const second = await service.search('fixture', 2)

    expect(first).toMatchObject({ currentPage: 1, totalPages: 2, totalItems: 2 })
    expect(first.items[0].ref).toEqual({ provider: 'picacomic', albumId: 'fixture-album-1' })
    expect(second.items[0].ref.albumId).toBe('fixture-album-2')
    expect(first.items[0].cover?.cacheUrl).toContain('/picacomic/')
    expect(fixture.calls).not.toContain('jmcomic')
  })

  test('empty results are a valid page rather than an error', async () => {
    const fixture = createPicacomicFixtureClient({ initiallySignedIn: true })
    const service = new PicacomicService(fixture)

    await expect(service.search('empty', 1)).resolves.toEqual({
      currentPage: 1,
      totalPages: 1,
      totalItems: 0,
      items: [],
    })
  })

  test('401/403, network/parse and stale chapter retain stable UI codes', async () => {
    const fixture = createPicacomicFixtureClient({ initiallySignedIn: true })
    const service = new PicacomicService(fixture)

    const scenarios: Array<[string, string]> = [
      ['401', 'PICACOMIC_AUTH_EXPIRED'],
      ['403', 'PICACOMIC_AUTH_REQUIRED'],
      ['network', 'PICACOMIC_NETWORK'],
      ['parse', 'PICACOMIC_INVALID_RESPONSE'],
    ]
    for (const [query, code] of scenarios) {
      await expect(service.search(query)).rejects.toMatchObject({ code })
    }

    await expect(
      service.getPhoto({
        provider: 'picacomic',
        albumId: 'fixture-album-1',
        chapterId: 'stale',
        order: 1,
      }),
    ).rejects.toMatchObject({ code: 'PICACOMIC_STALE_RESOURCE' })
    expect(picacomicErrorMessage(new PicacomicServiceError('PICACOMIC_NETWORK', 'test'))).toContain(
      '网络',
    )
  })

  test('image scope rejects late events after chapter navigation and supports retry', async () => {
    const fixture = createPicacomicFixtureClient({
      initiallySignedIn: true,
      failImageKeys: ['pica-fixture-fixture-album-1-chapter-1-1'],
      deferImageEvents: true,
    })
    const service = new PicacomicService(fixture)
    const retryKey = 'pica-fixture-fixture-album-1-chapter-1-1'
    const lateKey = 'pica-fixture-fixture-album-1-chapter-1-2'
    const nextKey = 'pica-fixture-fixture-album-1-chapter-2-1'
    const ready: string[] = []
    const failed: string[] = []
    const scopeA = service.createImageScope({
      onReady: (event) => ready.push(`a:${event.imageKey}`),
      onFailed: (event) => failed.push(`a:${event.imageKey}`),
    })
    await scopeA.start()
    await scopeA.request([lateKey])
    await scopeA.dispose()

    const scopeB = service.createImageScope({
      onReady: (event) => ready.push(`b:${event.imageKey}`),
      onFailed: (event) => failed.push(`b:${event.imageKey}`),
    })
    await scopeB.start()
    await scopeB.request([nextKey])
    fixture.flushImageEvents()
    await Promise.resolve()

    expect(failed).toEqual([])
    expect(ready).toEqual([`b:${nextKey}`])
    await expect(scopeA.request([lateKey])).rejects.toMatchObject({ code: 'PICACOMIC_CANCELLED' })

    const retryScope = service.createImageScope({
      onReady: (event) => ready.push(`retry:${event.imageKey}`),
      onFailed: (event) => failed.push(`retry:${event.imageKey}`),
    })
    await retryScope.start()
    await retryScope.request([retryKey])
    fixture.flushImageEvents()
    await Promise.resolve()
    expect(failed).toEqual([`retry:${retryKey}`])
    await retryScope.retry(retryKey)
    fixture.flushImageEvents()
    await Promise.resolve()
    expect(ready).toContain(`retry:${retryKey}`)
    await retryScope.dispose()
    await scopeB.dispose()
  })

  test('logout clears only the fixture session and scope lifecycle is idempotent', async () => {
    const fixture = createPicacomicFixtureClient({ initiallySignedIn: true })
    const service = new PicacomicService(fixture)
    const scope = service.createImageScope({ onReady: () => {}, onFailed: () => {} })
    await scope.start()
    await service.logout()
    await scope.dispose()
    await scope.dispose()

    expect(await service.getAuthState()).toEqual({ state: 'signed_out' })
    expect(fixture.calls).toContain('logout')
  })
})
