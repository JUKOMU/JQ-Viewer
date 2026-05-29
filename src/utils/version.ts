/**
 * 比较两个语义化版本号。
 * @returns >0 表示 a > b, <0 表示 a < b, 0 表示相等
 */
export function compareVersion(a: string, b: string): number {
  const pa = a.split('.').map(Number)
  const pb = b.split('.').map(Number)
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const va = pa[i] || 0
    const vb = pb[i] || 0
    if (va > vb) return 1
    if (va < vb) return -1
  }
  return 0
}

/** GitHub Releases API 地址 */
export const RELEASES_API = 'https://api.github.com/repos/jukomu/jq-viewer/releases/latest'

/**
 * 对 GitHub Release body 做简易清洗，去掉 Markdown 标记转为纯文本。
 * - 去掉 ###  前缀
 * - 去掉 ** 加粗标记
 * - 保留 - 列表和空行
 */
export function sanitizeReleaseBody(body: string): string {
  return body
    .replace(/^### /gm, '')
    .replace(/\*\*/g, '')
}
