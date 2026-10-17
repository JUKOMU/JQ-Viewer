import { createPrivateKey, createPublicKey, sign, verify } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const base64Pattern = /^[A-Za-z0-9+/]+={0,2}$/

function fail(message) {
  throw new Error(message)
}

export function signReleaseManifest({ manifestBytes, privateKeyPem, keyId }) {
  if (!/^[A-Za-z0-9._-]{1,64}$/.test(keyId)) fail('invalid release signing key ID')
  const privateKey = createPrivateKey(privateKeyPem)
  if (privateKey.asymmetricKeyType !== 'ed25519') fail('release signing key must be Ed25519')
  const publicKey = createPublicKey(privateKey)
  const publicKeySpki = publicKey.export({ type: 'spki', format: 'der' })
  return {
    signatureDocument: {
      schemaVersion: 1,
      algorithm: 'Ed25519',
      keyId,
      manifest: 'latest.json',
      signature: sign(null, manifestBytes, privateKey).toString('base64'),
    },
    publicKeySpkiBase64: publicKeySpki.toString('base64'),
  }
}

export function verifyReleaseManifestSignature({
  manifestBytes,
  signatureDocument,
  publicKeySpkiBase64,
  expectedKeyId,
}) {
  if (
    signatureDocument?.schemaVersion !== 1 ||
    signatureDocument?.algorithm !== 'Ed25519' ||
    signatureDocument?.manifest !== 'latest.json' ||
    signatureDocument?.keyId !== expectedKeyId
  ) {
    fail('release signature metadata is invalid')
  }
  if (!base64Pattern.test(signatureDocument.signature || '')) {
    fail('release signature is invalid')
  }
  if (!base64Pattern.test(publicKeySpkiBase64 || '')) {
    fail('release public key is invalid')
  }
  const publicKey = createPublicKey({
    key: Buffer.from(publicKeySpkiBase64, 'base64'),
    type: 'spki',
    format: 'der',
  })
  if (publicKey.asymmetricKeyType !== 'ed25519') fail('release public key must be Ed25519')
  if (!verify(null, manifestBytes, publicKey, Buffer.from(signatureDocument.signature, 'base64'))) {
    fail('release manifest signature verification failed')
  }
}

function readJson(filePath, label) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'))
  } catch (error) {
    throw new Error(label + ' is invalid JSON: ' + error.message, { cause: error })
  }
}

function parseCli(args) {
  const command = args[0]
  const options = new Map()
  for (let index = 1; index < args.length; index += 2) {
    const name = args[index]
    const value = args[index + 1]
    if (!name?.startsWith('--') || !value || options.has(name)) {
      fail('invalid release signature arguments')
    }
    options.set(name, value)
  }
  return { command, options }
}

function requireOption(options, name) {
  const value = options.get(name)
  if (!value) fail('missing required option: ' + name)
  return value
}

function signingEnvironment() {
  const keyId = process.env.RELEASE_ED25519_KEY_ID
  const publicKeySpkiBase64 = process.env.RELEASE_ED25519_PUBLIC_KEY_SPKI_BASE64?.trim()
  if (!keyId) fail('RELEASE_ED25519_KEY_ID is required')
  if (!publicKeySpkiBase64) fail('RELEASE_ED25519_PUBLIC_KEY_SPKI_BASE64 is required')
  return { keyId, publicKeySpkiBase64 }
}

async function main() {
  const { command, options } = parseCli(process.argv.slice(2))
  const manifestPath = requireOption(options, '--manifest')
  const signaturePath = requireOption(options, '--signature')
  const manifestBytes = fs.readFileSync(manifestPath)
  const { keyId, publicKeySpkiBase64 } = signingEnvironment()

  if (command === 'sign') {
    const encodedPrivateKey = process.env.RELEASE_ED25519_PRIVATE_KEY_BASE64
    if (!encodedPrivateKey) fail('RELEASE_ED25519_PRIVATE_KEY_BASE64 is required')
    const signed = signReleaseManifest({
      manifestBytes,
      privateKeyPem: Buffer.from(encodedPrivateKey, 'base64').toString('utf8'),
      keyId,
    })
    if (signed.publicKeySpkiBase64 !== publicKeySpkiBase64) {
      fail('release private key does not match the configured public key')
    }
    fs.writeFileSync(signaturePath, JSON.stringify(signed.signatureDocument, null, 2) + '\n')
  } else if (command !== 'verify') {
    fail('usage: release-signature.mjs <sign|verify> --manifest <latest.json> --signature <path>')
  }

  verifyReleaseManifestSignature({
    manifestBytes,
    signatureDocument: readJson(signaturePath, 'release signature'),
    publicKeySpkiBase64,
    expectedKeyId: keyId,
  })
  console.log('Release manifest signature ' + command + ' completed')
}

if (path.resolve(process.argv[1] || '') === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error('Release signature failed: ' + error.message)
    process.exitCode = 1
  })
}
