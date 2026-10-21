export const DOWNLOAD_NEXT_PAGE_THRESHOLD = 240
export const DOWNLOAD_PREVIOUS_PAGE_THRESHOLD = 120

export function shouldLoadDownloadNextPage(
  sentinelTop: number,
  sentinelBottom: number,
  viewportHeight: number,
): boolean {
  // 以当前组 sentinel 判断，避免同一页面的其他状态组阻塞分页。
  return (
    sentinelTop < viewportHeight + DOWNLOAD_NEXT_PAGE_THRESHOLD &&
    sentinelBottom > -DOWNLOAD_NEXT_PAGE_THRESHOLD
  )
}

export function shouldLoadDownloadPreviousPage(scrollTop: number): boolean {
  return scrollTop <= DOWNLOAD_PREVIOUS_PAGE_THRESHOLD
}

export type DownloadRefreshGroup = 'completed' | 'failed'

export function downloadRefreshGroupForStatus(status: string): DownloadRefreshGroup {
  return status === 'failed' ? 'failed' : 'completed'
}
