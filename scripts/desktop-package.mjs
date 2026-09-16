import { execFileSync } from 'node:child_process'
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  statSync,
} from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { commandInvocation } from './desktop-sync-command.mjs'

const root = fileURLToPath(new URL('..', import.meta.url))
const desktopDirectory = path.join(root, 'desktop')
const targetDirectory = path.join(desktopDirectory, 'target')
const packageJson = JSON.parse(
  readFileSync(new URL('../package.json', import.meta.url), 'utf8'),
)

export const applicationName = 'JQ-Viewer'
export const windowsUpgradeUuid = '12cd2298-f19e-46db-a283-5044b56012fb'

const targetDefinitions = new Map([
  [
    'windows-x64',
    {
      platform: 'windows',
      architecture: 'x64',
      hostPlatform: 'win32',
      hostArchitecture: 'x64',
      formats: ['installer.exe', 'portable.zip'],
    },
  ],
  [
    'linux-x64',
    {
      platform: 'linux',
      architecture: 'x64',
      hostPlatform: 'linux',
      hostArchitecture: 'x64',
      formats: ['deb', 'rpm', 'portable.tar.gz'],
    },
  ],
  [
    'linux-arm64',
    {
      platform: 'linux',
      architecture: 'arm64',
      hostPlatform: 'linux',
      hostArchitecture: 'arm64',
      formats: ['deb', 'rpm', 'portable.tar.gz'],
    },
  ],
])

const extraRuntimeModules = [
  'java.desktop',
  'java.logging',
  'java.management',
  'java.naming',
  'java.net.http',
  'java.sql',
  'jdk.crypto.ec',
  'jdk.unsupported',
]

function fail(message) {
  throw new Error(message)
}

function parseOptions(args) {
  const values = new Map()
  for (let index = 0; index < args.length; index += 2) {
    const name = args[index]
    const value = args[index + 1]
    if (!name?.startsWith('--') || !value) {
      fail('usage: node scripts/desktop-package.mjs --platform <platform> --arch <arch> --output <dir>')
    }
    if (values.has(name)) fail('duplicate option: ' + name)
    values.set(name, value)
  }

  const platform = values.get('--platform')
  const architecture = normalizeArchitecture(values.get('--arch'))
  const output = values.get('--output')
  if (!platform || !architecture || !output || values.size !== 3) {
    fail('usage: node scripts/desktop-package.mjs --platform <platform> --arch <arch> --output <dir>')
  }
  return { platform, architecture, output: path.resolve(output) }
}

export function normalizeArchitecture(value) {
  if (value === 'x64' || value === 'amd64') return 'x64'
  if (value === 'arm64' || value === 'aarch64') return 'arm64'
  return null
}

export function resolveTarget(platform, architecture) {
  const normalizedArchitecture = normalizeArchitecture(architecture)
  const target = targetDefinitions.get(platform + '-' + normalizedArchitecture)
  if (!target) {
    fail('unsupported Desktop package target: ' + platform + '-' + architecture)
  }
  return target
}

export function desktopAssetNames(version, platform, architecture) {
  const target = resolveTarget(platform, architecture)
  return target.formats.map((format) => {
    const separator = format === 'deb' || format === 'rpm' ? '.' : '-'
    return `JQ-Viewer-${version}-${target.platform}-${target.architecture}${separator}${format}`
  })
}

export function validateNativeHost(
  target,
  hostPlatform = process.platform,
  hostArchitecture = process.arch,
) {
  const normalizedHostArchitecture = normalizeArchitecture(hostArchitecture)
  if (
    target.hostPlatform !== hostPlatform ||
    target.hostArchitecture !== normalizedHostArchitecture
  ) {
    fail(
      `Desktop packages must be built natively: target=${target.platform}-${target.architecture}, ` +
        `host=${hostPlatform}-${hostArchitecture}`,
    )
  }
}

function executable(name) {
  if (process.platform !== 'win32') return name
  if (name === 'npm' || name === 'mvn') return name + '.cmd'
  return name + '.exe'
}

function run(command, args, options = {}) {
  const invocation = commandInvocation(command, args)
  return execFileSync(invocation.command, invocation.args, {
    cwd: options.cwd || root,
    env: options.env || process.env,
    encoding: options.encoding,
    stdio: options.stdio || (options.encoding ? ['ignore', 'pipe', 'inherit'] : 'inherit'),
  })
}

function listJars(directory) {
  return readdirSync(directory)
    .filter((name) => name.endsWith('.jar'))
    .map((name) => path.join(directory, name))
    .sort()
}

function findMainJar() {
  const matches = readdirSync(targetDirectory)
    .filter(
      (name) =>
        /^jq-viewer-desktop-.+\.jar$/.test(name) &&
        !name.endsWith('-sources.jar') &&
        !name.endsWith('-javadoc.jar'),
    )
    .sort()
  if (matches.length !== 1) fail('expected exactly one Desktop main JAR, got ' + matches.length)
  return path.join(targetDirectory, matches[0])
}

function jdepsModules(mainJar, dependencyJars) {
  const output = run(
    executable('jdeps'),
    [
      '--ignore-missing-deps',
      '--multi-release',
      '21',
      '--print-module-deps',
      '--class-path',
      dependencyJars.join(path.delimiter),
      mainJar,
    ],
    { encoding: 'utf8' },
  )
  const detected = output
    .trim()
    .split(',')
    .map((moduleName) => moduleName.trim())
    .filter(Boolean)
  if (detected.length === 0) fail('jdeps did not report any runtime modules')
  return [...new Set([...detected, ...extraRuntimeModules])].sort()
}

function validateFile(filePath, label) {
  if (!existsSync(filePath) || !statSync(filePath).isFile() || statSync(filePath).size === 0) {
    fail(label + ' was not created: ' + filePath)
  }
}

function renameSinglePackage(directory, extension, destination) {
  const matches = readdirSync(directory)
    .filter((name) => name.toLowerCase().endsWith(extension))
    .map((name) => path.join(directory, name))
  if (matches.length !== 1) {
    fail(`expected one ${extension} package, got ${matches.length}`)
  }
  rmSync(destination, { force: true })
  renameSync(matches[0], destination)
  validateFile(destination, extension + ' package')
}

function buildAppImage({ version, target, workDirectory, inputDirectory, runtimeDirectory }) {
  const appImagesDirectory = path.join(workDirectory, 'app-images')
  mkdirSync(appImagesDirectory, { recursive: true })
  run(executable('jpackage'), [
    '--type',
    'app-image',
    '--input',
    inputDirectory,
    '--dest',
    appImagesDirectory,
    '--name',
    applicationName,
    '--main-jar',
    path.basename(findMainJar()),
    '--main-class',
    'io.github.jukomu.desktop.Main',
    '--runtime-image',
    runtimeDirectory,
    '--app-version',
    version,
    '--vendor',
    'JUKOMU',
    '--description',
    'JQ Viewer Desktop',
    '--copyright',
    'Copyright (c) JUKOMU',
    '--java-options',
    '-Dfile.encoding=UTF-8',
  ])

  const appImage = path.join(appImagesDirectory, applicationName)
  const launcher =
    target.platform === 'windows'
      ? path.join(appImage, applicationName + '.exe')
      : path.join(appImage, 'bin', applicationName)
  validateFile(launcher, 'Desktop launcher')
  return appImage
}

function packageWindows({ version, appImage, outputDirectory, workDirectory }) {
  const packageDirectory = path.join(workDirectory, 'native-package')
  mkdirSync(packageDirectory, { recursive: true })
  run(executable('jpackage'), [
    '--type',
    'exe',
    '--name',
    applicationName,
    '--app-image',
    appImage,
    '--dest',
    packageDirectory,
    '--app-version',
    version,
    '--vendor',
    'JUKOMU',
    '--description',
    'JQ Viewer Desktop',
    '--license-file',
    path.join(root, 'LICENSE'),
    '--win-per-user-install',
    '--win-dir-chooser',
    '--win-menu',
    '--win-menu-group',
    'JQ Viewer',
    '--win-shortcut',
    '--win-upgrade-uuid',
    windowsUpgradeUuid,
  ])

  const [installerName, portableName] = desktopAssetNames(version, 'windows', 'x64')
  renameSinglePackage(packageDirectory, '.exe', path.join(outputDirectory, installerName))

  const archivePath = path.join(outputDirectory, portableName)
  rmSync(archivePath, { force: true })
  run('powershell.exe', [
    '-NoLogo',
    '-NoProfile',
    '-NonInteractive',
    '-Command',
    'Compress-Archive',
    '-LiteralPath',
    appImage,
    '-DestinationPath',
    archivePath,
    '-CompressionLevel',
    'Optimal',
    '-Force',
  ])
  validateFile(archivePath, 'Windows portable archive')
}

function packageLinux({ version, target, appImage, outputDirectory, workDirectory }) {
  const packageDirectory = path.join(workDirectory, 'native-package')
  mkdirSync(packageDirectory, { recursive: true })
  const commonArguments = [
    '--name',
    applicationName,
    '--app-image',
    appImage,
    '--dest',
    packageDirectory,
    '--app-version',
    version,
    '--vendor',
    'JUKOMU',
    '--description',
    'JQ Viewer Desktop',
    '--license-file',
    path.join(root, 'LICENSE'),
    '--linux-package-name',
    'jq-viewer',
    '--linux-menu-group',
    'Graphics',
    '--linux-app-category',
    'Graphics',
    '--linux-app-release',
    '1',
    '--linux-shortcut',
  ]

  for (const type of ['deb', 'rpm']) {
    run(executable('jpackage'), ['--type', type, ...commonArguments])
    const assetName = desktopAssetNames(version, 'linux', target.architecture).find((name) =>
      name.endsWith('.' + type),
    )
    renameSinglePackage(packageDirectory, '.' + type, path.join(outputDirectory, assetName))
  }

  const archiveName = desktopAssetNames(version, 'linux', target.architecture).find((name) =>
    name.endsWith('.tar.gz'),
  )
  const archivePath = path.join(outputDirectory, archiveName)
  rmSync(archivePath, { force: true })
  run('tar', [
    '-czf',
    archivePath,
    '-C',
    path.dirname(appImage),
    path.basename(appImage),
  ])
  validateFile(archivePath, 'Linux portable archive')
}

export function packageDesktop({ platform, architecture, output }) {
  const target = resolveTarget(platform, architecture)
  validateNativeHost(target)
  const version = packageJson.version
  const workDirectory = path.join(targetDirectory, 'desktop-package-' + platform + '-' + target.architecture)
  const inputDirectory = path.join(workDirectory, 'input')
  const runtimeDirectory = path.join(workDirectory, 'runtime')
  rmSync(workDirectory, { recursive: true, force: true })
  mkdirSync(inputDirectory, { recursive: true })
  mkdirSync(output, { recursive: true })
  for (const assetName of desktopAssetNames(version, platform, target.architecture)) {
    rmSync(path.join(output, assetName), { force: true })
  }

  run(executable('npm'), ['run', 'desktop:sync', '--', '--platform', platform])
  run(executable('mvn'), [
    '--batch-mode',
    '--file',
    path.join(desktopDirectory, 'pom.xml'),
    'clean',
    'package',
    'dependency:copy-dependencies',
    '-DincludeScope=runtime',
    '-DoutputDirectory=' + inputDirectory,
  ])

  const mainJar = findMainJar()
  const packagedMainJar = path.join(inputDirectory, path.basename(mainJar))
  copyFileSync(mainJar, packagedMainJar)
  const dependencyJars = listJars(inputDirectory).filter((jar) => jar !== packagedMainJar)
  if (dependencyJars.length === 0) fail('Desktop runtime dependencies were not copied')

  const modules = jdepsModules(packagedMainJar, dependencyJars)
  run(executable('jlink'), [
    '--add-modules',
    modules.join(','),
    '--bind-services',
    '--strip-debug',
    '--no-header-files',
    '--no-man-pages',
    '--compress',
    'zip-6',
    '--output',
    runtimeDirectory,
  ])
  validateFile(
    path.join(runtimeDirectory, 'bin', target.platform === 'windows' ? 'java.exe' : 'java'),
    'linked Java runtime',
  )

  const appImage = buildAppImage({
    version,
    target,
    workDirectory,
    inputDirectory,
    runtimeDirectory,
  })
  if (target.platform === 'windows') {
    packageWindows({ version, appImage, outputDirectory: output, workDirectory })
  } else {
    packageLinux({ version, target, appImage, outputDirectory: output, workDirectory })
  }

  const assets = desktopAssetNames(version, platform, target.architecture).map((name) => {
    const assetPath = path.join(output, name)
    validateFile(assetPath, 'Desktop release asset')
    return { name, size: statSync(assetPath).size }
  })
  console.log(JSON.stringify({ target: platform + '-' + target.architecture, modules, assets }, null, 2))
}

if (path.resolve(process.argv[1] || '') === fileURLToPath(import.meta.url)) {
  try {
    packageDesktop(parseOptions(process.argv.slice(2)))
  } catch (error) {
    console.error('Desktop packaging failed: ' + error.message)
    process.exitCode = 1
  }
}
