export interface PageProgressInput {
  currentPages: number
  totalPages: number
}

export interface PageProgressSummary {
  currentPages: number
  totalPages: number
  percent: number
}

function normalizePages(value: number): number {
  return Number.isFinite(value) ? Math.max(0, value) : 0
}

/**
 * 按页数聚合多个任务的进度。总页数未知时使用当前批次的最大已知页数，
 * 并用“当前已观察页数 + 1”避免全部未知时过早显示 100%。
 */
export function calculatePageProgress(
  tasks: readonly PageProgressInput[],
): PageProgressSummary | null {
  if (tasks.length === 0) return null

  const normalized = tasks.map((task) => ({
    currentPages: normalizePages(task.currentPages),
    totalPages: normalizePages(task.totalPages),
  }))
  const knownMaxPages = normalized.reduce(
    (max, task) => (task.totalPages > 0 ? Math.max(max, task.totalPages) : max),
    0,
  )
  const observedUnknownMax = normalized.reduce(
    (max, task) => (task.totalPages > 0 ? max : Math.max(max, task.currentPages + 1)),
    0,
  )
  const unknownEstimate = Math.max(1, knownMaxPages, observedUnknownMax)

  let currentPages = 0
  let totalPages = 0
  for (const task of normalized) {
    const estimatedTotal = task.totalPages > 0 ? task.totalPages : unknownEstimate
    totalPages += estimatedTotal
    currentPages += Math.min(task.currentPages, estimatedTotal)
  }

  const percent = totalPages > 0 ? Math.min(100, Math.max(0, (currentPages / totalPages) * 100)) : 0

  return { currentPages, totalPages, percent }
}
