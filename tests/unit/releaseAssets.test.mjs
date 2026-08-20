// @vitest-environment node

import { describe, expect, it } from 'vitest'

import {
  buildReleaseManifest,
  parseAndroidMetadata,
  validateReleaseNotes,
} from '../../scripts/release-assets.mjs'

describe('release assets', () => {
  it('requires release notes to start with the exact release tag', () => {
    expect(validateReleaseNotes('# v1.3.0\n\n## 修复\n\n- 修复问题', 'v1.3.0')).toContain('## 修复')
    expect(() => validateReleaseNotes('# v1.2.0\n\n内容', 'v1.3.0')).toThrow(
      'first heading must match',
    )
    expect(() => validateReleaseNotes('# v1.3.0', 'v1.3.0')).toThrow('must contain content')
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
        github: 'https://github.com/JUKOMU/JQ-Viewer/releases/download/v1.3.0/JQ-Viewer-1_3_0.apk',
        gitee: 'https://gitee.com/jukomu/jq-viewer/releases/download/v1.3.0/JQ-Viewer-1_3_0.apk',
      },
    })
  })

  it('keeps the universal artifact at the top level and adds all ABI variants', () => {
    const abis = ['arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86']
    const manifest = buildReleaseManifest({
      releaseTag: 'v1.3.0',
      apkName: 'JQ-Viewer-1_3_0-universal.apk',
      sizeBytes: 60086182,
      sha256: 'a'.repeat(64),
      signingCertificateSha256: 'b'.repeat(64),
      releaseNotes: '# v1.3.0\n\n## 修复\n\n- 修复问题',
      githubRepository: 'JUKOMU/JQ-Viewer',
      giteeRepository: 'jukomu/jq-viewer',
      packageName: 'io.github.jukomu',
      versionCode: 15,
      versionName: '1.3.0',
      variants: abis.map((abi, index) => ({
        abi,
        apkName: `JQ-Viewer-1_3_0-${abi}.apk`,
        sizeBytes: 20000000 + index,
        sha256: String(index + 1).repeat(64),
      })),
    })

    expect(manifest.schemaVersion).toBe(1)
    expect(manifest.apkName).toBe('JQ-Viewer-1_3_0-universal.apk')
    expect(manifest.variants.map((variant) => variant.abi)).toEqual(abis)
    expect(manifest.variants[0].sources.github).toContain('/JQ-Viewer-1_3_0-arm64-v8a.apk')
  })

  it('accepts an empty ABI variant set', () => {
    expect(() =>
      buildReleaseManifest({
        releaseTag: 'v1.3.0',
        apkName: 'JQ-Viewer-1_3_0-universal.apk',
        sizeBytes: 1,
        sha256: 'a'.repeat(64),
        signingCertificateSha256: 'b'.repeat(64),
        releaseNotes: '# v1.3.0\n\n内容',
        githubRepository: 'JUKOMU/JQ-Viewer',
        giteeRepository: 'jukomu/jq-viewer',
        packageName: 'io.github.jukomu',
        versionCode: 15,
        versionName: '1.3.0',
        variants: [],
      }),
    ).not.toThrow()
  })

  it('rejects an incomplete ABI variant set', () => {
    expect(() =>
      buildReleaseManifest({
        releaseTag: 'v1.3.0',
        apkName: 'JQ-Viewer-1_3_0-universal.apk',
        sizeBytes: 1,
        sha256: 'a'.repeat(64),
        signingCertificateSha256: 'b'.repeat(64),
        releaseNotes: '# v1.3.0\n\n内容',
        githubRepository: 'JUKOMU/JQ-Viewer',
        giteeRepository: 'jukomu/jq-viewer',
        packageName: 'io.github.jukomu',
        versionCode: 15,
        versionName: '1.3.0',
        variants: [
          {
            abi: 'arm64-v8a',
            apkName: 'JQ-Viewer-1_3_0-arm64-v8a.apk',
            sizeBytes: 1,
            sha256: 'c'.repeat(64),
          },
        ],
      }),
    ).toThrow('each supported ABI exactly once')
  })

  it('rejects duplicated ABI variants', () => {
    const abis = ['arm64-v8a', 'arm64-v8a', 'x86_64', 'x86']
    expect(() =>
      buildReleaseManifest({
        releaseTag: 'v1.3.0',
        apkName: 'JQ-Viewer-1_3_0-universal.apk',
        sizeBytes: 1,
        sha256: 'a'.repeat(64),
        signingCertificateSha256: 'b'.repeat(64),
        releaseNotes: '# v1.3.0\n\n内容',
        githubRepository: 'JUKOMU/JQ-Viewer',
        giteeRepository: 'jukomu/jq-viewer',
        packageName: 'io.github.jukomu',
        versionCode: 15,
        versionName: '1.3.0',
        variants: abis.map((abi, index) => ({
          abi,
          apkName: `JQ-Viewer-1_3_0-${abi}.apk`,
          sizeBytes: index + 1,
          sha256: String(index + 1).repeat(64),
        })),
      }),
    ).toThrow('each supported ABI exactly once')
  })
})
