import { describe, expect, test } from 'vitest'
import { sanitizeError } from '@/services/JmcomicService'

describe('sanitizeError', () => {
  test('从 Error 对象提取 message', () => {
    const err = new Error('网络请求失败')
    expect(sanitizeError(err, '默认消息')).toBe('网络请求失败')
  })

  test('从字符串提取消息', () => {
    expect(sanitizeError('超时错误', '默认消息')).toBe('超时错误')
  })

  test('无法从普通对象提取（非 Error 实例）', () => {
    const err = { message: '权限不足' }
    expect(sanitizeError(err, '默认消息')).toBe('默认消息')
  })

  test('无法提取时返回默认消息', () => {
    expect(sanitizeError(null, '默认消息')).toBe('默认消息')
    expect(sanitizeError(undefined, '默认消息')).toBe('默认消息')
    expect(sanitizeError(42, '默认消息')).toBe('默认消息')
  })

  test('处理空字符串', () => {
    expect(sanitizeError('', '默认消息')).toBe('默认消息')
  })

  test('过滤敏感数据库错误信息', () => {
    expect(sanitizeError(new Error('mysql connection failed'), '默认消息')).toBe('服务器繁忙，请稍后重试')
    expect(sanitizeError(new Error('SQL syntax error'), '默认消息')).toBe('服务器繁忙，请稍后重试')
    expect(sanitizeError('database timeout', '默认消息')).toBe('服务器繁忙，请稍后重试')
  })

  test('保留安全的错误信息', () => {
    expect(sanitizeError(new Error('网络超时'), '默认消息')).toBe('网络超时')
    expect(sanitizeError('请求被拒绝', '默认消息')).toBe('请求被拒绝')
  })
})
