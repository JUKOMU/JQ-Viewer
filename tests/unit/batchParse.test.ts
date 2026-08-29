import { describe, expect, test } from 'vitest'
import { parseIdsFromText, splitIdsIntoLines } from '@/utils/batchParse'

describe('splitIdsIntoLines', () => {
  test('按出现顺序提取有效 ID，并整理为单行单 ID', () => {
    expect(splitIdsIntoLines('jm1199508 jm1199507\n备注 jm1199506')).toBe(
      '1199508\n1199507\n1199506',
    )
  })

  test('与批量解析保持一致地过滤短数字并去重', () => {
    const text = '12 123456 123456\r\n99 654321'
    expect(splitIdsIntoLines(text)).toBe('123456\n654321')
    expect(splitIdsIntoLines(text)).toBe(
      parseIdsFromText(text)
        .items.map((item) => item.id)
        .join('\n'),
    )
  })

  test('没有有效 ID 时返回空文本', () => {
    expect(splitIdsIntoLines('只有文字和 12')).toBe('')
  })
})
