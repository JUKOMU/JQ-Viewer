import type { FileRef } from './FileReferences'
import type { Capability } from './PlatformServices'

export interface ResourceResolver {
  imageUrl(input: {
    photoId: string
    sortOrder: number
    type: 'image' | 'thumb'
  }): string

  pdfDocumentUrl(file: FileRef): string

  renderPdfPage: Capability<{
    getUrl(input: { file: FileRef; page: number; targetWidth: number }): Promise<string>
  }>
}
