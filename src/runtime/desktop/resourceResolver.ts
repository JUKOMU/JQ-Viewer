import type { FileRef } from '../FileReferences'
import type { ResourceResolver } from '../ResourceResolver'
import { requestBackend, type BackendFetch } from './backendClient'

function encodeFileRef(file: FileRef): string {
  return btoa(unescape(encodeURIComponent(String(file))))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

/** 构建同源资源 URL；native virtual host 仅由 Android runtime 提供。 */
export function createResourceResolver(fetcher: BackendFetch): ResourceResolver {
  return {
    imageUrl: ({ photoId, sortOrder, type }) =>
      `/${type}/${encodeURIComponent(photoId)}/${sortOrder}`,
    pdfDocumentUrl: (file: FileRef) => `/pdf/${encodeFileRef(file)}`,
    renderPdfPage: {
      available: true,
      api: {
        getUrl: ({ file, page, targetWidth }) =>
          requestBackend<{ resourceUrl: string }>(fetcher, 'renderPdfPage', {
            fileRef: String(file),
            page,
            targetWidth,
          }).then((result) => result.resourceUrl),
      },
    },
  }
}
