import { execFileSync } from 'node:child_process'
import { copyFileSync, cpSync, existsSync, mkdirSync, readFileSync, rmSync } from 'node:fs'
import { delimiter, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const rootDir = fileURLToPath(new URL('..', import.meta.url))
const desktopDir = join(rootDir, 'desktop-server')
const desktopTargetDir = join(desktopDir, 'target')
const packageJson = JSON.parse(readFileSync(join(rootDir, 'package.json'), 'utf8'))
const rawArgs = process.argv.slice(2)
const args = new Set(rawArgs)
const skipFrontend = args.has('--skip-frontend') || process.env.npm_config_skip_frontend === 'true'
const skipMaven = args.has('--skip-maven') || process.env.npm_config_skip_maven === 'true'
const jmcomicVersion = optionValue(rawArgs, '--jmcomic-version') || process.env.npm_config_jmcomic_version || ''
const requestedPackageType = optionValue(rawArgs, '--package-type') || process.env.npm_config_package_type || ''

runStep('frontend build', !skipFrontend, () => {
  run(npmCmd(), ['run', 'build'], {
    cwd: rootDir,
    env: {
      ...process.env,
      VITE_JQ_PLATFORM: 'desktop',
    },
  })
})

runStep('desktop server package', !skipMaven, () => {
  const mavenArgs = ['-f', join(desktopDir, 'pom.xml')]
  if (jmcomicVersion) {
    mavenArgs.push(`-Djmcomic.version=${jmcomicVersion}`)
  }
  mavenArgs.push('package')
  try {
    run(mvnCmd(), mavenArgs, { cwd: rootDir })
  } catch (error) {
    const effectiveVersion = jmcomicVersion || desktopDependencyVersion()
    console.error(
      `Desktop Maven package failed with jmcomic.version=${effectiveVersion}. ` +
      'Check Maven network access, local cache, and published dependency metadata. ' +
      'If the jmcomic dependency metadata is incomplete, retry with a validated override, for example: ' +
      'npm run build:desktop -- --jmcomic-version=1.1.5'
    )
    throw error
  }
})

const jpackage = findJpackage()
if (!jpackage) {
  console.log('jpackage was not found. Skipping installer build after frontend and Maven package.')
  process.exit(0)
}

const inputDir = join(desktopTargetDir, 'jpackage-input')
const installerDir = join(desktopTargetDir, 'installer')
const mainJar = findMainJar()
const packageType = resolvePackageType(requestedPackageType)
const wixToolDir = process.platform === 'win32' && packageType !== 'app-image' ? findWixToolDir() : ''

rmSync(inputDir, { recursive: true, force: true })
rmSync(installerDir, { recursive: true, force: true })
mkdirSync(inputDir, { recursive: true })
copyFileSync(join(desktopTargetDir, mainJar), join(inputDir, mainJar))
cpSync(join(desktopTargetDir, 'lib'), join(inputDir, 'lib'), { recursive: true })
cpSync(join(rootDir, 'dist'), join(inputDir, 'dist'), { recursive: true })
mkdirSync(installerDir, { recursive: true })

const jpackageArgs = [
  '--name', 'JQViewer',
  '--dest', installerDir,
  '--input', inputDir,
  '--main-jar', mainJar,
  '--main-class', 'io.github.jukomu.jqviewer.desktop.host.DesktopHostMain',
  '--app-version', sanitizeVersion(packageJson.version),
  '--vendor', 'Jukomu',
  '--java-options', '-Dfile.encoding=UTF-8',
  '--type', packageType,
]

if (process.platform === 'win32' && packageType !== 'app-image') {
  jpackageArgs.push('--win-dir-chooser', '--win-menu', '--win-shortcut')
}

runStep('desktop installer', true, () => run(jpackage, jpackageArgs, {
  cwd: rootDir,
  env: wixToolDir
    ? {
      ...process.env,
      Path: `${wixToolDir}${delimiter}${process.env.Path || process.env.PATH || ''}`,
      PATH: `${wixToolDir}${delimiter}${process.env.PATH || process.env.Path || ''}`,
    }
    : process.env,
}))
console.log(`Desktop package output: ${installerDir}`)

function runStep(label, enabled, action) {
  if (!enabled) {
    console.log(`Skipping ${label}.`)
    return
  }
  console.log(`Running ${label}...`)
  action()
}

function run(command, commandArgs, options) {
  execFileSync(command, commandArgs, {
    stdio: 'inherit',
    shell: process.platform === 'win32' && command.endsWith('.cmd'),
    ...options,
  })
}

function npmCmd() {
  return process.platform === 'win32' ? 'npm.cmd' : 'npm'
}

function mvnCmd() {
  return process.platform === 'win32' ? 'mvn.cmd' : 'mvn'
}

function findMainJar() {
  const expected = `jqviewer-desktop-server-${desktopVersion()}.jar`
  if (existsSync(join(desktopTargetDir, expected))) return expected
  throw new Error(`Desktop server jar was not found: ${expected}`)
}

function desktopVersion() {
  const pom = readFileSync(join(desktopDir, 'pom.xml'), 'utf8')
  const match = pom.match(/<artifactId>jqviewer-desktop-server<\/artifactId>[\s\S]*?<version>([^<]+)<\/version>/)
  if (!match) throw new Error('Unable to read desktop-server version from pom.xml')
  return match[1]
}

function desktopDependencyVersion() {
  const pom = readFileSync(join(desktopDir, 'pom.xml'), 'utf8')
  const match = pom.match(/<jmcomic\.version>([^<]+)<\/jmcomic\.version>/)
  if (!match) throw new Error('Unable to read jmcomic.version from pom.xml')
  return match[1]
}

function findJpackage() {
  const javaHome = process.env.JAVA_HOME
  const candidates = []
  if (javaHome) {
    candidates.push(join(javaHome, 'bin', process.platform === 'win32' ? 'jpackage.exe' : 'jpackage'))
  }
  for (const dir of (process.env.PATH || '').split(delimiter)) {
    if (dir) candidates.push(join(dir, process.platform === 'win32' ? 'jpackage.exe' : 'jpackage'))
  }
  return candidates.find((candidate) => existsSync(candidate)) || ''
}

function resolvePackageType(requested) {
  if (requested) {
    const normalized = requested.trim()
    if (process.platform === 'win32' && normalized !== 'app-image' && !findWixToolDir()) {
      throw new Error(
        `Windows ${normalized} packaging requires WiX candle.exe and light.exe on PATH. ` +
        'Install WiX Toolset v3.14 or use --package-type=app-image.'
      )
    }
    return normalized
  }

  if (process.platform !== 'win32') return 'app-image'
  if (findWixToolDir()) return 'exe'

  console.log('WiX candle.exe/light.exe were not found. Building app-image instead of exe installer.')
  return 'app-image'
}

function findWixToolDir() {
  const candleName = process.platform === 'win32' ? 'candle.exe' : 'candle'
  const lightName = process.platform === 'win32' ? 'light.exe' : 'light'
  const candle = findOnPath(candleName) || findInCommonWixDirs(candleName)
  const light = findOnPath(lightName) || findInCommonWixDirs(lightName)
  if (!candle || !light) return ''
  const candleDir = candle.slice(0, -candleName.length).replace(/[\\/]$/, '')
  const lightDir = light.slice(0, -lightName.length).replace(/[\\/]$/, '')
  return candleDir === lightDir ? candleDir : ''
}

function findOnPath(executable) {
  for (const dir of (process.env.PATH || '').split(delimiter)) {
    if (!dir) continue
    const candidate = join(dir, executable)
    if (existsSync(candidate)) return candidate
  }
  return ''
}

function findInCommonWixDirs(executable) {
  if (process.platform !== 'win32') return ''
  const candidates = [
    'C:\\Program Files (x86)\\WiX Toolset v3.14\\bin',
    'C:\\Program Files\\WiX Toolset v3.14\\bin',
    'C:\\Program Files (x86)\\WiX Toolset v3.11\\bin',
    'C:\\Program Files\\WiX Toolset v3.11\\bin',
  ]
  for (const dir of candidates) {
    const candidate = join(dir, executable)
    if (existsSync(candidate)) return candidate
  }
  return ''
}

function sanitizeVersion(version) {
  const normalized = String(version || '1.0.0').replace(/[^0-9.]/g, '')
  return normalized || '1.0.0'
}

function optionValue(values, name) {
  for (let i = 0; i < values.length; i += 1) {
    const value = values[i]
    if (value === name && i + 1 < values.length) return values[i + 1]
    if (value.startsWith(`${name}=`)) return value.slice(name.length + 1)
  }
  return ''
}
