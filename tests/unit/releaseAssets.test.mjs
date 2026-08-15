// @vitest-environment node

import { describe, expect, it } from 'vitest'

import {
  buildReleaseManifest,
  parseAndroidMetadata,
  validateReleaseNotes,
} from '../../scripts/release-assets.mjs'

describe('release assets', () => {
  it('requires release notes to start with the exact release tag', () => {
    expect(validateReleaseNotes('# v1.3.0\n\n## 修复\n\n- 修复问题', 'v1.3.0')).toContain(
      '## 修复',
    )
    expect(() => validateReleaseNotes('# v1.2.0\n\n内容', 'v1.3.0')).toThrow(
      'first heading must match',
    )
    expect(() => validateReleaseNotes('# v1.3.0', 'v1.3.0')).toThrow(
      'must contain content',
    )
  })

  it('parses the Android package metadata used by the release', () => {
    const buildGradle = [
      'applicationId "io.github.jukomu"',
      'versionCode 15',
      'versionName "1.3.0"',
    ].join('\n')

    expect(parseAndroidMetadata(buildGradle)).toEqual({
      applicationId: 'io.github.jukomu',
      versionCode: 15,
      versionName: '1.3.0',
    })
  })

  it('builds one manifest with both deterministic download URLs', () => {
    const manifest = buildReleaseManifest({
      releaseTag: 'v1.3.0',
      apkName: 'JQ-Viewer-1_3_0.apk',
      sizeBytes: 60086182,
      sha256: 'a'.repeat(64),
      signingCertificateSha256: 'b'.repeat(64),
      releaseNotes: '# v1.3.0\n\n## 修复\n\n- 修复问题',
      githubRepository: 'JUKOMU/JQ-Viewer',
      giteeRepository: 'jukomu/jq-viewer',
      packageName: 'io.github.jukomu',
      versionCode: 15,
      versionName: '1.3.0',
    })

    expect(manifest).toMatchObject({
      schemaVersion: 1,
      tag: 'v1.3.0',
      packageName: 'io.github.jukomu',
      versionCode: 15,
      sha256: 'a'.repeat(64),
      signingCertificateSha256: 'B'.repeat(64),
      sources: {
        github:
          'https://github.com/JUKOMU/JQ-Viewer/releases/download/v1.3.0/JQ-Viewer-1_3_0.apk',
        gitee:
          'https://gitee.com/jukomu/jq-viewer/releases/download/v1.3.0/JQ-Viewer-1_3_0.apk',
      },
    })
  })
})
