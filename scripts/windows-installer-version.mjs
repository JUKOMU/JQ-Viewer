const projectVersionPattern = /^(\d+)\.(\d+)\.(\d+)$/
const releaseTagPattern = /^v(\d+\.\d+\.\d+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/

const maxMsiMajorMinor = 255
const maxMsiBuild = 65535
const releaseSlotsPerPatch = 100
const stableReleaseSlot = releaseSlotsPerPatch - 1

function fail(message) {
  throw new Error(message)
}

export function resolveWindowsInstallerVersion(projectVersion, releaseTag = '') {
  const versionMatch = projectVersion.match(projectVersionPattern)
  if (!versionMatch) fail(`project version ${projectVersion} must use X.Y.Z format`)

  const [, majorText, minorText, patchText] = versionMatch
  const major = Number(majorText)
  const minor = Number(minorText)
  const patch = Number(patchText)
  if (major > maxMsiMajorMinor || minor > maxMsiMajorMinor) {
    fail('Windows installer major and minor versions must not exceed 255')
  }

  if (!releaseTag) return projectVersion

  const tagMatch = releaseTag.match(releaseTagPattern)
  if (!tagMatch || tagMatch[1] !== projectVersion) {
    fail(`release tag ${releaseTag} must match project version ${projectVersion}`)
  }

  const prerelease = tagMatch[2]
  let releaseSlot = stableReleaseSlot
  if (prerelease) {
    const sequenceMatch = prerelease.match(/(?:^|\.)(\d+)$/)
    if (!sequenceMatch) {
      fail('Desktop prerelease tag must end with a numeric sequence')
    }
    releaseSlot = Number(sequenceMatch[1])
    if (releaseSlot < 1 || releaseSlot >= stableReleaseSlot) {
      fail(`Desktop prerelease sequence must be between 1 and ${stableReleaseSlot - 1}`)
    }
  }

  const build = patch * releaseSlotsPerPatch + releaseSlot
  if (build > maxMsiBuild) {
    fail(`Windows installer build version ${build} exceeds ${maxMsiBuild}`)
  }
  return `${major}.${minor}.${build}`
}
