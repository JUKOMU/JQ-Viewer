/**
 * 以固定并发数执行异步任务池，返回与输入顺序一致的结果数组。
 * 任一任务 reject 不会中断整体——该位置结果为 null。
 */
export async function runWithConcurrency<T, R>(
  items: T[],
  fn: (item: T) => Promise<R>,
  concurrency: number = 5,
): Promise<(R | null)[]> {
  const results: (R | null)[] = new Array(items.length)
  const total = items.length
  let cursor = 0

  async function worker() {
    while (cursor < total) {
      const i = cursor++
      try {
        results[i] = await fn(items[i])
      } catch {
        results[i] = null
      }
    }
  }

  // 启动 concurrency 个 worker（不超过任务总数）
  const workers = Array.from({ length: Math.min(concurrency, total) }, () => worker())
  await Promise.all(workers)

  return results
}
