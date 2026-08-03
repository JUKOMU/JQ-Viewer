import fs from 'node:fs'

const releaseTagPattern = /^v([0-9]+\.[0-9]+\.[0-9]+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/
const projectVersionPattern = /^[0-9]+\.[0-9]+\.[0-9]+$/

function fail(message) {
  console.error(`Release tag check failed: ${message}`)
  process.exitCode = 1
}

const packageJson = JSON.parse(fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8'))
const projectVersion = packageJson.version
const releaseTag = process.argv[2]?.trim()

if (!projectVersionPattern.test(projectVersion)) {
  fail(`project version ${projectVersion} must use X.Y.Z format`)
} else if (!releaseTag) {
  fail('release tag is required')
} else {
  const match = releaseTag.match(releaseTagPattern)

  if (!match) {
    fail('release tag must match vX.Y.Z or vX.Y.Z-prerelease')
  } else if (match[1] !== projectVersion) {
    fail(`release tag version ${match[1]} does not match project version ${projectVersion}`)
  } else {
    console.log(`Release tag check passed: ${releaseTag}`)
  }
}
