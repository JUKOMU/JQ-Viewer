import { describe, expect, test } from 'vitest'
import { useMultiSelect } from '@/composables/useMultiSelect'

describe('useMultiSelect', () => {
  test('进入时保留已有选择，全选和反选只提交完整范围', () => {
    const selection = useMultiSelect<string>()
    selection.enter('a')
    selection.enter('b')
    expect(selection.count.value).toBe(2)

    selection.select(['a', 'b', 'c'])
    selection.invert(['a', 'b', 'c'])
    expect([...selection.selected.value]).toEqual([])
    selection.toggle('c')
    selection.exit()
    expect(selection.active.value).toBe(false)
    expect(selection.count.value).toBe(0)
  })

  test('全选后锁定全选按钮，已选数量变化后解除锁定', () => {
    const selection = useMultiSelect<string>()
    selection.selectAll(['a', 'b'])

    expect(selection.allSelected.value).toBe(true)

    selection.select(['c', 'd'])

    expect(selection.allSelected.value).toBe(true)

    selection.toggle('c')

    expect(selection.count.value).toBe(1)
    expect(selection.allSelected.value).toBe(false)
  })
})
