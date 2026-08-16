import { describe, expect, test } from 'vitest'
import { renderReleaseNotesMarkdown } from '@/utils/releaseNotesMarkdown'

describe('renderReleaseNotesMarkdown', () => {
  test('渲染标题、列表和强调文本，并移除重复版本标题', () => {
    const html = renderReleaseNotesMarkdown(
      '# v1.4.0\n\n## 新增\n\n- 支持 **双源下载**\n- 支持 `SHA-256` 校验',
      '1.4.0',
    )

    expect(html).toContain('<h2>新增</h2>')
    expect(html).toContain('<li>支持 <strong>双源下载</strong></li>')
    expect(html).toContain('<code>SHA-256</code>')
    expect(html).not.toContain('<h1>v1.4.0</h1>')
    expect(html).not.toContain('# v1.4.0')
  })

  test('清理危险标签和链接属性', () => {
    const html = renderReleaseNotesMarkdown(
      '[危险链接](javascript:alert(1))\n\n<script>alert(1)</script><strong>安全文本</strong>',
    )

    expect(html).not.toContain('<script>')
    expect(html).not.toContain('javascript:')
    expect(html).toContain('<strong>安全文本</strong>')
  })

  test('为安全链接添加新窗口打开属性', () => {
    const html = renderReleaseNotesMarkdown('[项目主页](https://example.com)')

    expect(html).toContain('href="https://example.com"')
    expect(html).toContain('target="_blank"')
    expect(html).toContain('rel="noopener noreferrer"')
  })
})
