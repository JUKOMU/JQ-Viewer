// @vitest-environment node

import { spawnSync } from 'node:child_process'
import { generateKeyPairSync } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

import {
  desktopAssetNames,
  normalizeArchitecture,
  resolveTarget,
  resolveUpdateTrustRoot,
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

  it('accepts only an Ed25519 Desktop updater trust root', () => {
    const { publicKey } = generateKeyPairSync('ed25519')
    const publicKeySpkiBase64 = publicKey.export({ type: 'spki', format: 'der' }).toString('base64')

    expect(
      resolveUpdateTrustRoot({
        RELEASE_ED25519_KEY_ID: 'jq-viewer-release-1',
        RELEASE_ED25519_PUBLIC_KEY_SPKI_BASE64: publicKeySpkiBase64,
      }),
    ).toEqual({ keyId: 'jq-viewer-release-1', publicKeySpkiBase64 })
    expect(() =>
      resolveUpdateTrustRoot({
        RELEASE_ED25519_KEY_ID: 'jq-viewer-release-1',
        RELEASE_ED25519_PUBLIC_KEY_SPKI_BASE64: publicKeySpkiBase64 + '!',
      }),
    ).toThrow('not canonical Base64')
    expect(() => resolveUpdateTrustRoot({})).toThrow('RELEASE_ED25519_KEY_ID')
  })

  it('rejects another option where an option value is required', () => {
    const script = fileURLToPath(new URL('../../scripts/desktop-package.mjs', import.meta.url))
    const result = spawnSync(
      process.execPath,
      [script, '--platform', 'invalid', '--arch', 'x64', '--output', '--unexpected'],
      { encoding: 'utf8' },
    )

    expect(result.status).toBe(1)
    expect(result.stderr).toContain('usage: node scripts/desktop-package.mjs')
    expect(result.stderr).not.toContain('unsupported Desktop package target')
  })
})
