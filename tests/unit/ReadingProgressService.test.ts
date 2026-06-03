import {beforeEach, describe, expect, test} from 'vitest'
import {ReadingProgressService} from '@/services/ReadingProgressService'

describe('ReadingProgressService', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  test('restores saved progress when route does not specify page', () => {
    ReadingProgressService.record('album-a', 'chapter-a', 10, 20)

    expect(ReadingProgressService.getInitialPage(undefined, 'album-a', 'chapter-a', 20)).toBe(10)
  })

  test('route page overrides saved progress', () => {
    ReadingProgressService.record('album-a', 'chapter-a', 10, 20)

    expect(ReadingProgressService.getInitialPage('3', 'album-a', 'chapter-a', 20)).toBe(3)
  })

  test('keeps chapters isolated within the same album', () => {
    ReadingProgressService.record('album-a', 'chapter-a', 8, 20)

    expect(ReadingProgressService.getInitialPage(undefined, 'album-a', 'chapter-b', 20)).toBe(1)
  })

  test('clamps page by total pages', () => {
    ReadingProgressService.record('album-a', 'chapter-a', 99, 12)

    expect(ReadingProgressService.get('album-a', 'chapter-a')?.page).toBe(12)
    expect(ReadingProgressService.getInitialPage('99', 'album-a', 'chapter-a', 12)).toBe(12)
  })
})
