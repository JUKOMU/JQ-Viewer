import type { FileRef } from '../FileReferences'
import type { ResourceResolver } from '../ResourceResolver'

function encode(value: string): string {
  return encodeURIComponent(value)
}

/** 构建同源资源 URL；native virtual host 仅由 Android runtime 提供。 */
export function createDesktopResourceResolver(): ResourceResolver {
  return {
    imageUrl: ({ photoId, sortOrder, type }) => `/${type}/${encode(photoId)}/${sortOrder}`,
    pdfDocumentUrl: (file: FileRef) => `/pdf/${encode(String(file))}`,
    renderPdfPage: {
      available: false,
      reason: 'Desktop PDF page rendering is unavailable',
    },
  }
}
