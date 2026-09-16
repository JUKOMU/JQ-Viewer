// @vitest-environment node

import { describe, expect, it } from 'vitest'

import {
  desktopAssetNames,
  normalizeArchitecture,
  resolveTarget,
  validateNativeHost,
  windowsUpgradeUuid,
} from '../../scripts/desktop-package.mjs'

describe('Desktop package targets', () => {
  it('normalizes runner architecture names', () => {
    expect(normalizeArchitecture('amd64')).toBe('x64')
    expect(normalizeArchitecture('aarch64')).toBe('arm64')
    expect(normalizeArchitecture('riscv64')).toBeNull()
  })

  it('publishes Windows x64 for both native x64 and Windows on ARM emulation', () => {
    expect(desktopAssetNames('1.4.6', 'windows', 'x64')).toEqual([
      'JQ-Viewer-1.4.6-windows-x64-installer.exe',
      'JQ-Viewer-1.4.6-windows-x64-portable.zip',
    ])
    expect(() => resolveTarget('windows', 'arm64')).toThrow('unsupported Desktop package target')
  })

  it('publishes all Linux package forms for both native architectures', () => {
    expect(desktopAssetNames('1.4.6', 'linux', 'arm64')).toEqual([
      'JQ-Viewer-1.4.6-linux-arm64.deb',
      'JQ-Viewer-1.4.6-linux-arm64.rpm',
      'JQ-Viewer-1.4.6-linux-arm64-portable.tar.gz',
    ])
  })

  it('rejects cross-architecture and cross-platform packaging', () => {
    const target = resolveTarget('linux', 'arm64')
    expect(() => validateNativeHost(target, 'linux', 'x64')).toThrow('must be built natively')
    expect(() => validateNativeHost(target, 'linux', 'arm64')).not.toThrow()
  })

  it('keeps one fixed Windows upgrade identity', () => {
    expect(windowsUpgradeUuid).toBe('12cd2298-f19e-46db-a283-5044b56012fb')
  })
})
