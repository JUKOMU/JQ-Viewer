import type { FileRef } from '../FileReferences'
import type { ResourceResolver } from '../ResourceResolver'

function encode(value: string): string {
  return encodeURIComponent(value)
}

/** Builds same-origin resource URLs; native virtual hosts are Android-only. */
export function createDesktopResourceResolver(): ResourceResolver {
  return {
    imageUrl: ({ photoId, sortOrder, type }) => `/${type}/${encode(photoId)}/${sortOrder}`,
    pdfDocumentUrl: (file: FileRef) => `/pdf/${encode(String(file))}`,
    renderPdfPage: {
      available: false,
      reason: 'Desktop PDF page rendering is not available in phase 1',
    },
  }
}
