import { describe, expect, it } from 'vitest'
import { getExportErrorMessage, getExportErrorMessageFrom } from './ExportErrorService'

describe('ExportErrorService', () => {
  it('uses the same message for format-prefixed error codes', () => {
    expect(getExportErrorMessage('PDF_TARGET_INVALID')).toBe(
      '导出路径无效。请重新选择导出目录或检查文件名后重试。',
    )
    expect(getExportErrorMessage('CBZ_TARGET_INVALID')).toBe(
      '导出路径无效。请重新选择导出目录或检查文件名后重试。',
    )
  })

  it('does not expose the original error message', () => {
    expect(
      getExportErrorMessageFrom(
        new Error('导出目标路径无效: /storage/emulated/0/Download/output.pdf'),
        'submit',
      ),
    ).toBe('导出启动失败。请检查导出目录和下载内容后重试。')
  })

  it('maps runtime errors to user-facing export guidance', () => {
    expect(getExportErrorMessageFrom({ code: 'permission-denied' }, 'submit')).toBe(
      '导出目录不可用。请检查存储权限后重试。',
    )
    expect(getExportErrorMessageFrom({ code: 'conflict' }, 'retry')).toBe(
      '导出任务状态已变化。请刷新后重试。',
    )
  })
})
