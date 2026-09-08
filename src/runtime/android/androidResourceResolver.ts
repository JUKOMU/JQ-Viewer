import type { JmcomicClient } from '@/services/jmcomic/JmcomicClient'
import type { FileRef } from '../FileReferences'
import type { ResourceResolver } from '../ResourceResolver'
import { withRuntimeError } from '../errors'

/** Android 虚拟资源域名，与原生侧 ImageRegistry/PdfServer 拦截规则一致。 */
const VIRTUAL_BASE = 'https://jqviewer.local'

/** 将文件引用编码为可安全放入虚拟 URL path 的 base64url 形式。 */
function encodeFileRef(file: FileRef): string {
  return btoa(unescape(encodeURIComponent(String(file))))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

/**
 * 构造 Android 资源 URL 生成器：图片、PDF 文档和原生 PDF 页面都走受控的
 * 虚拟 WebView 资源；页面渲染结果只透传原生返回的 resourceUrl。
 */
export function createAndroidResourceResolver(native: JmcomicClient): ResourceResolver {
  return {
    imageUrl: ({ photoId, sortOrder, type }) =>
      `${VIRTUAL_BASE}/${type}/${photoId}/${sortOrder}`,
    pdfDocumentUrl: (file) => `${VIRTUAL_BASE}/pdf/${encodeFileRef(file)}`,
    renderPdfPage: {
      available: true,
      api: {
        getUrl: ({ file, page, targetWidth }) =>
          withRuntimeError(async () => {
            const result = await native.renderPdfPage({
              fileRef: String(file),
              page,
              targetWidth,
            })
            return result.resourceUrl
          }),
      },
    },
  }
}
