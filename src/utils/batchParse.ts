/** 解析出的有效 ID 条目（3 位及以上数字串） */
export interface ParsedIdItem {
  /** 数字串本身（即 album ID） */
  id: string
  /** 该 ID 所在行的完整文本 */
  lineText: string
  /** 行号 (0-based) */
  lineIndex: number
  /** 数字串在该行文本中的起始位置 */
  startIndex: number
  /** 数字串在该行文本中的结束位置（不含） */
  endIndex: number
}

/** 被过滤的无效数字串（2 位及以下），仅用于源文本标记 */
export interface InvalidIdItem {
  value: string
  lineIndex: number
  startIndex: number
  endIndex: number
}

export interface ParseResult {
  /** 有效 ID 列表（已去重、已过滤 2 位数字），按原文出现顺序排列 */
  items: ParsedIdItem[]
  /** 被位数过滤的数字串（length < 3），去重 ID 重复的不在此列 */
  invalidIds: InvalidIdItem[]
}

/**
 * 从文本中提取所有有效 album ID（3 位及以上数字串）。
 * 过滤 2 位及以下数字，对 3 位及以上数字去重（保留首次出现）。
 */
export function parseIdsFromText(text: string): ParseResult {
  // 统一换行符（处理 Windows \r\n 和旧 Mac \r）
  const normalized = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  const lines = normalized.split('\n')
  const items: ParsedIdItem[] = []
  const invalidIds: InvalidIdItem[] = []
  const seenIds = new Set<string>()

  const digitRe = /\d+/g

  for (let li = 0; li < lines.length; li++) {
    const line = lines[li]
    digitRe.lastIndex = 0
    let m: RegExpExecArray | null
    while ((m = digitRe.exec(line)) !== null) {
      const value = m[0]
      const startIndex = m.index
      const endIndex = startIndex + value.length

      if (value.length < 3) {
        invalidIds.push({ value, lineIndex: li, startIndex, endIndex })
        continue
      }

      if (seenIds.has(value)) continue
      seenIds.add(value)

      items.push({
        id: value,
        lineText: line,
        lineIndex: li,
        startIndex,
        endIndex,
      })
    }
  }

  return { items, invalidIds }
}
