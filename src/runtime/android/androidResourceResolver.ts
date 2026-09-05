import type { JmcomicClient } from '@/services/jmcomic/JmcomicClient'
import type { FileRef } from '../FileReferences'
import type { ResourceResolver } from '../ResourceResolver'
import { withRuntimeError } from '../errors'

const VIRTUAL_BASE = 'https://jqviewer.local'

function encodeFileRef(file: FileRef): string {
  return btoa(unescape(encodeURIComponent(String(file))))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

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
              filePath: String(file),
              page,
              targetWidth,
            })
            return result.imageUrl
          }),
      },
    },
  }
}
