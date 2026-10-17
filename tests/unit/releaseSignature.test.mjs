// @vitest-environment node

import { generateKeyPairSync } from 'node:crypto'
import { describe, expect, it } from 'vitest'

import {
  signReleaseManifest,
  verifyReleaseManifestSignature,
} from '../../scripts/release-signature.mjs'

describe('release manifest signature', () => {
  it('signs exact latest.json bytes and rejects tampering', () => {
    const manifestBytes = Buffer.from('{"schemaVersion":1,"tag":"v1.4.6"}\n')
    const { privateKey } = generateKeyPairSync('ed25519')
    const signed = signReleaseManifest({
      manifestBytes,
      privateKeyPem: privateKey.export({ type: 'pkcs8', format: 'pem' }),
      keyId: 'jq-viewer-release-test',
    })
    const verifyInput = {
      manifestBytes,
      signatureDocument: signed.signatureDocument,
      publicKeySpkiBase64: signed.publicKeySpkiBase64,
      expectedKeyId: 'jq-viewer-release-test',
    }

    expect(() => verifyReleaseManifestSignature(verifyInput)).not.toThrow()
    expect(() =>
      verifyReleaseManifestSignature({
        ...verifyInput,
        manifestBytes: Buffer.concat([manifestBytes, Buffer.from(' ')]),
      }),
    ).toThrow('signature verification failed')
  })

  it('rejects a non-Ed25519 signing key', () => {
    const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
    expect(() =>
      signReleaseManifest({
        manifestBytes: Buffer.from('{}\n'),
        privateKeyPem: privateKey.export({ type: 'pkcs8', format: 'pem' }),
        keyId: 'test',
      }),
    ).toThrow('must be Ed25519')
  })
})
