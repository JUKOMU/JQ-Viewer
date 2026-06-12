import {spawn, spawnSync} from 'node:child_process'
import {existsSync, mkdirSync, readFileSync, rmSync} from 'node:fs'
import {createServer} from 'node:net'
import {delimiter, join} from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import {fileURLToPath} from 'node:url'

const rootDir = fileURLToPath(new URL('..', import.meta.url))
const desktopDir = join(rootDir, 'desktop-server')
const targetDir = join(desktopDir, 'target')
const rawArgs = process.argv.slice(2)
const args = new Set(rawArgs)
const skipMaven = args.has('--skip-maven') || process.env.npm_config_skip_maven === 'true'
const keepData = args.has('--keep-data')
const token = optionValue(rawArgs, '--token') || 'desktop-smoke-token'
const port = Number(optionValue(rawArgs, '--port')) || await freePort()
const dataDir = optionValue(rawArgs, '--data-dir') || join(targetDir, 'smoke-desktop')
const baseUrl = `http://127.0.0.1:${port}`
let serverProcess = null

try {
  if (!skipMaven) {
    runMavenPackage()
  }

  const jar = join(targetDir, `jqviewer-desktop-server-${desktopVersion()}.jar`)
  const libClasspath = join(targetDir, 'lib', '*')
  if (!existsSync(jar)) {
    throw new Error(`Desktop server jar was not found: ${jar}. Run npm run smoke:desktop without --skip-maven first.`)
  }

  if (!keepData) {
    rmSync(dataDir, {recursive: true, force: true})
  }
  mkdirSync(dataDir, {recursive: true})

  serverProcess = spawn(javaCmd(), [
    '-cp',
    `${jar}${delimiter}${libClasspath}`,
    'io.github.jukomu.jqviewer.desktop.DesktopServerMain',
    '--port', String(port),
    '--token', token,
    '--data-dir', dataDir,
  ], {
    cwd: rootDir,
    env: {
      ...process.env,
      JQ_DESKTOP_DEV_ORIGINS: 'http://localhost:5173,http://127.0.0.1:5173',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })

  const stdout = []
  const stderr = []
  serverProcess.stdout.on('data', chunk => stdout.push(String(chunk)))
  serverProcess.stderr.on('data', chunk => stderr.push(String(chunk)))
  serverProcess.on('exit', code => {
    if (code !== null && code !== 0) {
      stderr.push(`desktop server exited with code ${code}`)
    }
  })

  await waitForServer(stdout, stderr)
  await runSmokeChecks()
  await stopServer()
  await expectServerStopped()

  if (!keepData) {
    rmSync(dataDir, {recursive: true, force: true})
  }

  console.log('Desktop smoke passed.')
} catch (error) {
  await stopServer()
  console.error(error instanceof Error ? error.message : error)
  process.exit(1)
}

function runMavenPackage() {
  console.log('Running desktop server Maven package...')
  const result = spawnSync(mvnCmd(), ['-f', join(desktopDir, 'pom.xml'), 'package'], {
    cwd: rootDir,
    stdio: 'inherit',
    shell: process.platform === 'win32',
  })
  if (result.status !== 0) {
    throw new Error(`Maven package failed with exit code ${result.status ?? 'unknown'}`)
  }
}

async function runSmokeChecks() {
  console.log(`Running desktop smoke against ${baseUrl}`)

  await expectStatus('/api/diagnostics/status', 401, 'API rejects missing token')

  const blockedOrigin = await request('/api/diagnostics/status', {
    token,
    origin: 'https://example.com',
  })
  expect(blockedOrigin.status === 403, `API rejects non-local Origin, got ${blockedOrigin.status}`)

  const diagnostics = await requestJson('/api/diagnostics/status', {
    token,
    origin: baseUrl,
  })
  expect(diagnostics.response.status === 200, `diagnostics status expected 200, got ${diagnostics.response.status}`)
  expect(diagnostics.response.headers.get('access-control-allow-origin') === baseUrl,
    'diagnostics response should allow same local server Origin')
  expect(diagnostics.body.bindAddress === '127.0.0.1',
    `diagnostics bindAddress expected 127.0.0.1, got ${diagnostics.body.bindAddress}`)
  expect(diagnostics.body.port === port, `diagnostics port expected ${port}, got ${diagnostics.body.port}`)
  expect(String(diagnostics.body.dataDir || '').includes('smoke-desktop') || dataDir !== join(targetDir, 'smoke-desktop'),
    'diagnostics should use the smoke data directory')
  expect(!containsSensitiveKey(diagnostics.body), 'diagnostics response must not expose token/cookie/password')

  const initStatus = await requestJson('/api/init-status', {token})
  expect(initStatus.response.status === 200, `init-status expected 200, got ${initStatus.response.status}`)

  const pdfScan = await requestJson('/api/pdf/scan', {
    method: 'POST',
    token,
    body: {},
  })
  expect(pdfScan.response.status === 200, `PDF scan expected 200, got ${pdfScan.response.status}`)
  expect(Array.isArray(pdfScan.body.files), 'PDF scan should return a files array')

  const outsidePath = join(rootDir, 'package.json')
  await expectStatus(`/api/pdf/file?path=${encodeURIComponent(outsidePath)}`, 401,
    'PDF file endpoint rejects missing token')
  await expectStatus(`/api/pdf/file?path=${encodeURIComponent(outsidePath)}&token=${encodeURIComponent(token)}`, 400,
    'PDF file endpoint rejects paths outside allowed roots')
  await expectStatus(`/api/pdf/page?path=${encodeURIComponent(outsidePath)}&page=1&token=${encodeURIComponent(token)}`, 400,
    'PDF page endpoint rejects paths outside allowed roots')

  const fileCheck = await requestJson('/api/files/check', {
    method: 'POST',
    token,
    body: {paths: [outsidePath]},
  })
  expect(fileCheck.response.status === 200, `file check expected 200, got ${fileCheck.response.status}`)
  expect(Array.isArray(fileCheck.body.existing), 'file check should return an existing array')
  expect(fileCheck.body.existing.length === 0, 'file check should not expose outside-root paths')

  await expectStatus('/image/smoke/1', 401, 'image endpoint rejects missing token')
  await expectStatus(`/image/smoke?token=${encodeURIComponent(token)}`, 400, 'image endpoint validates resource path')
  await expectStatus('/thumb/smoke/1', 401, 'thumb endpoint rejects missing token')
  await expectStatus(`/thumb/smoke?token=${encodeURIComponent(token)}`, 400, 'thumb endpoint validates resource path')

  await expectStatus('/events', 401, 'SSE endpoint rejects missing token')
  await expectSse()
}

async function expectSse() {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 5000)
  try {
    const response = await fetch(`${baseUrl}/events?token=${encodeURIComponent(token)}`, {
      headers: {Origin: baseUrl},
      signal: controller.signal,
    })
    expect(response.status === 200, `SSE expected 200, got ${response.status}`)
    expect(String(response.headers.get('content-type') || '').includes('text/event-stream'),
      'SSE should return text/event-stream')
    const reader = response.body?.getReader()
    expect(Boolean(reader), 'SSE response should expose a readable body')
    const {value} = await reader.read()
    const chunk = new TextDecoder().decode(value)
    expect(chunk.includes(': connected'), 'SSE should send the initial connected comment')
    await reader.cancel()
  } finally {
    clearTimeout(timeout)
    controller.abort()
  }
}

async function expectStatus(path, status, label) {
  const response = await request(path)
  expect(response.status === status, `${label}: expected ${status}, got ${response.status}`)
}

async function requestJson(path, options = {}) {
  const response = await request(path, options)
  const text = await response.text()
  let body = {}
  try {
    body = text ? JSON.parse(text) : {}
  } catch (error) {
    throw new Error(`Expected JSON from ${path}, got: ${text}`)
  }
  return {response, body}
}

async function request(path, options = {}) {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), options.timeoutMs ?? 5000)
  try {
    const headers = new Headers(options.headers || {})
    if (options.token) headers.set('X-JQ-Desktop-Token', options.token)
    if (options.origin) headers.set('Origin', options.origin)
    let body
    if (options.body !== undefined) {
      headers.set('Content-Type', 'application/json')
      body = JSON.stringify(options.body)
    }
    return await fetch(`${baseUrl}${path}`, {
      method: options.method || 'GET',
      headers,
      body,
      signal: controller.signal,
    })
  } finally {
    clearTimeout(timeout)
  }
}

async function waitForServer(stdout, stderr) {
  const deadline = Date.now() + 20000
  while (Date.now() < deadline) {
    if (serverProcess.exitCode !== null) {
      throw new Error([
        'Desktop server exited before smoke checks.',
        stdout.join(''),
        stderr.join(''),
      ].filter(Boolean).join('\n'))
    }
    try {
      const response = await request('/api/diagnostics/status', {token, timeoutMs: 1000})
      if (response.status === 200) return
    } catch (error) {
      await delay(250)
    }
  }
  throw new Error([
    `Timed out waiting for desktop server on ${baseUrl}.`,
    stdout.join(''),
    stderr.join(''),
  ].filter(Boolean).join('\n'))
}

async function stopServer() {
  if (!serverProcess || serverProcess.exitCode !== null) return
  serverProcess.kill()
  const deadline = Date.now() + 5000
  while (serverProcess.exitCode === null && Date.now() < deadline) {
    await delay(100)
  }
  if (serverProcess.exitCode === null) {
    serverProcess.kill('SIGKILL')
  }
}

async function expectServerStopped() {
  for (let i = 0; i < 10; i += 1) {
    try {
      await request('/api/diagnostics/status', {token, timeoutMs: 500})
      await delay(200)
    } catch (error) {
      return
    }
  }
  throw new Error('Desktop server still responded after smoke cleanup')
}

function desktopVersion() {
  const pom = readFileSync(join(desktopDir, 'pom.xml'), 'utf8')
  const match = pom.match(/<artifactId>jqviewer-desktop-server<\/artifactId>[\s\S]*?<version>([^<]+)<\/version>/)
  if (!match) throw new Error('Unable to read desktop-server version from pom.xml')
  return match[1]
}

async function freePort() {
  return await new Promise((resolve, reject) => {
    const server = createServer()
    server.on('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      const selected = typeof address === 'object' && address ? address.port : 0
      server.close(() => resolve(selected))
    })
  })
}

function containsSensitiveKey(value) {
  const serialized = JSON.stringify(value).toLowerCase()
  return serialized.includes('token') || serialized.includes('cookie') || serialized.includes('password')
}

function expect(condition, message) {
  if (!condition) throw new Error(message)
}

function javaCmd() {
  const javaHome = process.env.JAVA_HOME
  if (javaHome) {
    return join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  }
  return process.platform === 'win32' ? 'java.exe' : 'java'
}

function mvnCmd() {
  return process.platform === 'win32' ? 'mvn.cmd' : 'mvn'
}

function optionValue(values, name) {
  for (let i = 0; i < values.length; i += 1) {
    const value = values[i]
    if (value === name && i + 1 < values.length) return values[i + 1]
    if (value.startsWith(`${name}=`)) return value.slice(name.length + 1)
  }
  return ''
}
