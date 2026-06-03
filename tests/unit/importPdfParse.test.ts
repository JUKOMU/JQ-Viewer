import { describe, expect, it } from 'vitest'
import { parseFilenamesForImport } from '@/utils/importPdfParse'

describe('parseFilenamesForImport', () => {
  it('does not mark one file as duplicate when the same id appears twice in its name', () => {
    const result = parseFilenamesForImport(
      ['/tmp/JM123456 123456.pdf'],
      ['JM123456 123456.pdf'],
    )

    expect(result.files[0].extractedIds).toEqual(['123456'])
    expect(result.files[0].duplicateIds).toEqual([])
  })

  it('marks ids duplicated across different files', () => {
    const result = parseFilenamesForImport(
      ['/tmp/JM123456.pdf', '/tmp/123456 copy.pdf'],
      ['JM123456.pdf', '123456 copy.pdf'],
    )

    expect(result.files[0].duplicateIds).toEqual(['123456'])
    expect(result.files[1].duplicateIds).toEqual(['123456'])
  })

  it('extracts chapter hints from simplified and traditional chapter suffixes', () => {
    const result = parseFilenamesForImport(
      ['/tmp/JM123456 第12话.pdf', '/tmp/JM654321 第３話.pdf'],
      ['JM123456 第12话.pdf', 'JM654321 第３話.pdf'],
    )

    expect(result.files[0].extractedIds).toEqual(['123456'])
    expect(result.files[0].chapterSortOrderHint).toBe(12)
    expect(result.files[1].extractedIds).toEqual(['654321'])
    expect(result.files[1].chapterSortOrderHint).toBe(3)
  })

  it('does not treat the chapter number as a candidate id', () => {
    const result = parseFilenamesForImport(
      ['/tmp/123456 第789话.pdf'],
      ['123456 第789话.pdf'],
    )

    expect(result.files[0].extractedIds).toEqual(['123456'])
    expect(result.files[0].status).toBe('resolved')
    expect(result.files[0].chapterSortOrderHint).toBe(789)
  })

  it('keeps candidate ids in first appearance order after dedupe', () => {
    const result = parseFilenamesForImport(
      ['/tmp/222222 111111 222222.pdf'],
      ['222222 111111 222222.pdf'],
    )

    expect(result.files[0].extractedIds).toEqual(['222222', '111111'])
    expect(result.files[0].status).toBe('ambiguous')
  })
})
