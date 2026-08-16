import DOMPurify from 'dompurify'
import { marked } from 'marked'

const ALLOWED_TAGS = [
  'a',
  'blockquote',
  'br',
  'code',
  'em',
  'h1',
  'h2',
  'h3',
  'h4',
  'hr',
  'li',
  'ol',
  'p',
  'pre',
  'strong',
  'ul',
]

const ALLOWED_ATTR = ['href', 'title']

function removeReleaseTitle(markdown: string, versionName?: string): string {
  if (!versionName) return markdown
  const escapedVersion = versionName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const titlePattern = new RegExp(`^#\\s+v?${escapedVersion}\\s*(?:\\r?\\n){1,2}`)
  return markdown.replace(titlePattern, '')
}

/** 将远程发布文案转换为受限 HTML，供更新 UI 安全渲染。 */
export function renderReleaseNotesMarkdown(markdown: string, versionName?: string): string {
  const source = removeReleaseTitle(markdown, versionName)
  const rendered = marked.parse(source, { async: false, breaks: false, gfm: true })
  const sanitized = DOMPurify.sanitize(rendered, {
    ALLOWED_ATTR,
    ALLOWED_TAGS,
  })
  const container = document.createElement('div')
  container.innerHTML = sanitized

  container.querySelectorAll<HTMLAnchorElement>('a[href]').forEach((link) => {
    link.target = '_blank'
    link.rel = 'noopener noreferrer'
  })

  return container.innerHTML
}
