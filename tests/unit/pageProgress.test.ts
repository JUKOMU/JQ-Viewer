import { describe, expect, test } from 'vitest'
import { calculatePageProgress } from '@/utils/pageProgress'

describe('calculatePageProgress', () => {
  test('按页数聚合多个任务，而不是按任务数量平均', () => {
    expect(
      calculatePageProgress([
        { currentPages: 50, totalPages: 100 },
        { currentPages: 0, totalPages: 10 },
      ]),
    ).toEqual({ currentPages: 50, totalPages: 110, percent: (50 / 110) * 100 })
  })

  test('未知总页数使用当前批次最大已知页数', () => {
    expect(
      calculatePageProgress([
        { currentPages: 40, totalPages: 100 },
        { currentPages: 10, totalPages: 0 },
        { currentPages: 15, totalPages: 60 },
      ]),
    ).toEqual({ currentPages: 65, totalPages: 260, percent: 25 })
  })

  test('全部未知时使用当前观察页数加一，避免过早显示 100%', () => {
    expect(
      calculatePageProgress([
        { currentPages: 1, totalPages: 0 },
        { currentPages: 0, totalPages: 0 },
      ]),
    ).toEqual({ currentPages: 1, totalPages: 4, percent: 25 })
  })

  test('真实总页数到达后会替换临时估算', () => {
    expect(
      calculatePageProgress([
        { currentPages: 55, totalPages: 180 },
        { currentPages: 15, totalPages: 60 },
      ]),
    ).toEqual({ currentPages: 70, totalPages: 240, percent: (70 / 240) * 100 })
  })

  test('页数异常值不会把进度带出 0% 到 100% 范围', () => {
    expect(
      calculatePageProgress([
        { currentPages: -10, totalPages: 0 },
        { currentPages: 120, totalPages: 100 },
      ]),
    ).toEqual({ currentPages: 100, totalPages: 200, percent: 50 })
  })

  test('没有任务时返回空状态', () => {
    expect(calculatePageProgress([])).toBeNull()
  })
})
