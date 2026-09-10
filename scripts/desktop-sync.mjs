import { execFileSync } from 'node:child_process'
import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('..', import.meta.url))
const destination = join(root, 'desktop', 'src', 'main', 'resources', 'static')
const buildOutput = mkdtempSync(join(tmpdir(), 'jq-viewer-desktop-build-'))
const viteBinary = join(root, 'node_modules', '.bin', process.platform === 'win32' ? 'vite.cmd' : 'vite')

function run(command, args) {
  execFileSync(command, args, { cwd: root, stdio: 'inherit' })
}

function listFiles(directory) {
  const files = []
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) files.push(...listFiles(path))
    else files.push(path)
  }
  return files
}

function validateBuild(directory) {
  const indexPath = join(directory, 'index.html')
  if (!existsSync(indexPath)) throw new Error('Desktop build did not produce index.html')

  const files = listFiles(directory)
  const assetFiles = files.filter((path) => relative(directory, path) !== 'index.html')
  if (assetFiles.length === 0) throw new Error('Desktop build did not produce static assets')

  const html = readFileSync(indexPath, 'utf8')
  const references = [...html.matchAll(/(?:src|href)=["']([^"']+)["']/g)].map((match) => match[1])
  for (const reference of references) {
    if (reference === '/' || reference === '.' || reference === './') continue
    if (/^(?:[a-z][a-z\d+.-]*:|\/\/|#)/i.test(reference)) continue
    const pathPart = reference.split(/[?#]/, 1)[0]
    const relativePath = decodeURIComponent(pathPart).replace(/^\/+/, '')
    const resolvedPath = resolve(directory, relativePath)
    if (resolvedPath !== directory && !resolvedPath.startsWith(`${directory}${sep}`)) {
      throw new Error(`Desktop build references a path outside its output: ${reference}`)
    }
    if (!existsSync(resolvedPath) || !statSync(resolvedPath).isFile()) {
      throw new Error(`Desktop build references a missing asset: ${reference}`)
    }
  }
}

function replaceDestination() {
  const parent = dirname(destination)
  const staging = join(parent, `.static-stage-${process.pid}`)
  const backup = join(parent, `.static-backup-${process.pid}`)
  rmSync(staging, { recursive: true, force: true })
  rmSync(backup, { recursive: true, force: true })
  cpSync(buildOutput, staging, { recursive: true })
  writeFileSync(join(staging, '.gitkeep'), '')

  const hadDestination = existsSync(destination)
  try {
    if (hadDestination) renameSync(destination, backup)
    renameSync(staging, destination)
    rmSync(backup, { recursive: true, force: true })
  } catch (error) {
    rmSync(destination, { recursive: true, force: true })
    if (hadDestination && existsSync(backup)) renameSync(backup, destination)
    throw error
  } finally {
    rmSync(staging, { recursive: true, force: true })
    rmSync(backup, { recursive: true, force: true })
  }
}

try {
  run(process.platform === 'win32' ? 'npm.cmd' : 'npm', ['run', 'typecheck'])
  run(viteBinary, ['build', '--mode', 'desktop', '--outDir', buildOutput, '--emptyOutDir'])
  validateBuild(buildOutput)
  mkdirSync(dirname(destination), { recursive: true })
  replaceDestination()
} finally {
  rmSync(buildOutput, { recursive: true, force: true })
}
