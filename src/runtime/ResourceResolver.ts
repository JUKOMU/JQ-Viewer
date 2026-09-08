import type { FileRef } from './FileReferences'
import type { Capability } from './PlatformServices'

/**
 * 受控的资源 URL 生成器。页面只拿到可展示/可 fetch 的 URL，
 * 不接触底层的虚拟域名、token 或本地路径拼接。
 */
export interface ResourceResolver {
  imageUrl(input: {
    photoId: string
    sortOrder: number
    type: 'image' | 'thumb'
  }): string

  pdfDocumentUrl(file: FileRef): string

  /** PDF 逐页渲染。返回受控资源 URL，页面不接触平台传输或二进制编码细节。 */
  renderPdfPage: Capability<{
    getUrl(input: { file: FileRef; page: number; targetWidth: number }): Promise<string>
  }>
}
