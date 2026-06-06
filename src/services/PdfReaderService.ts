import type {DocumentInitParameters} from 'pdfjs-dist/types/src/display/api'
import {isDesktopRuntime} from '@/platform/runtime'
import {desktopResourceUrl} from '@/services/jmcomic/http'

const VIRTUAL_BASE = 'https://jqviewer.local'

export type PdfLoadErrorCode =
  | 'file-missing'
  | 'permission-denied'
  | 'invalid-path'
  | 'invalid-content'
  | 'open-failed'

export class PdfLoadError extends Error {
  code: PdfLoadErrorCode

  constructor(code: PdfLoadErrorCode, message: string) {
    super(message)
    this.name = 'PdfLoadError'
    this.code = code
  }
}

export function getPdfVirtualUrl(filePath: string): string {
  if (isDesktopRuntime) {
    return desktopResourceUrl(`/api/pdf/file?path=${encodeURIComponent(filePath)}`)
  }

  const encoded = btoa(unescape(encodeURIComponent(filePath)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
  return `${VIRTUAL_BASE}/pdf/${encoded}`
}

export async function fetchPdfArrayBuffer(filePath: string): Promise<ArrayBuffer> {
  const response = await fetch(getPdfVirtualUrl(filePath))
  if (!response.ok) {
    const errorCode = response.headers.get('X-JQViewer-Pdf-Error')
    if (errorCode === 'permission-denied') {
      throw new PdfLoadError('permission-denied', 'PDF 文件读取权限已失效，请重新导入')
    }
    if (errorCode === 'invalid-path') {
      throw new PdfLoadError('invalid-path', 'PDF 文件路径无效，请重新导入')
    }
    if (errorCode === 'file-missing' || response.status === 404) {
      throw new PdfLoadError('file-missing', 'PDF 文件不存在或已移动')
    }
    throw new PdfLoadError('open-failed', 'PDF 文件打开失败')
  }

  const arrayBuffer = await response.arrayBuffer()
  if (arrayBuffer.byteLength === 0) {
    throw new PdfLoadError('file-missing', 'PDF 文件不存在或已移动')
  }

  const header = new TextDecoder('ascii').decode(arrayBuffer.slice(0, Math.min(1024, arrayBuffer.byteLength)))
  if (!header.includes('%PDF-')) {
    throw new PdfLoadError('invalid-content', 'PDF 文件内容无效')
  }

  return arrayBuffer
}

export function buildPdfDocumentParams(arrayBuffer: ArrayBuffer): DocumentInitParameters {
  return {
    data: new Uint8Array(arrayBuffer),
    disableAutoFetch: true,
    disableRange: true,
    disableStream: true,
    isEvalSupported: false,
    isImageDecoderSupported: false,
    isOffscreenCanvasSupported: false,
    useWasm: false,
    useWorkerFetch: false,
  }
}
