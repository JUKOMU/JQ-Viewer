import type {SearchResultItem} from './JmcomicTypes'

const STORAGE_KEY = 'jq-export-format'
const DEFAULT_FORMAT = 'JM{id}{title}'

export const ExportFormatService = {
  getExportFormat(): string {
    try {
      return localStorage.getItem(STORAGE_KEY) || DEFAULT_FORMAT
    } catch {
      return DEFAULT_FORMAT
    }
  },

  setExportFormat(template: string) {
    localStorage.setItem(STORAGE_KEY, template)
  },

  resetExportFormat() {
    localStorage.removeItem(STORAGE_KEY)
  },

  /** 用 SearchResultItem 数据渲染模板 */
  renderExportFormat(template: string, item: SearchResultItem): string {
    const author = item.authors?.[0] ?? ''
    const authors = item.authors?.join(', ') ?? ''
    const tags = item.tags?.join(', ') ?? ''

    return template
      .replace(/\{id\}/g, item.id)
      .replace(/\{title\}/g, item.title)
      .replace(/\{authors\}/g, authors)
      .replace(/\{author\}/g, author)
      .replace(/\{tags\}/g, tags)
  },

  /** 用示例数据生成预览 */
  previewExportFormat(template?: string): string {
    const fmt = template ?? ExportFormatService.getExportFormat()
    const sample: SearchResultItem = {
      id: '123456',
      title: 'サンプル漫画タイトル',
      coverUrl: '',
      authors: ['山田太郎', '佐藤花子'],
      tags: ['青年', 'コメディ', '日常'],
    }
    return ExportFormatService.renderExportFormat(fmt, sample)
  },
}
