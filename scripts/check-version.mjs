import fs from 'node:fs';

const files = {
  packageJson: 'package.json',
  packageLock: 'package-lock.json',
  androidBuildGradle: 'android/app/build.gradle',
  readme: 'README.md',
};

function readText(path) {
  return fs.readFileSync(path, 'utf8');
}

function readJson(path) {
  return JSON.parse(readText(path));
}

function fail(message) {
  console.error(`Version check failed: ${message}`);
  process.exitCode = 1;
}

const packageJson = readJson(files.packageJson);
const packageLock = readJson(files.packageLock);
const buildGradle = readText(files.androidBuildGradle);
const readme = readText(files.readme);
const desktopPom = readText('desktop-server/pom.xml');

const expectedVersion = packageJson.version;
const gradleVersionName = buildGradle.match(/versionName\s+["']([^"']+)["']/)?.[1];
const readmeBadgeVersion = readme.match(/img\.shields\.io\/badge\/Version-([^-]+)-brightgreen\.svg/)?.[1];
const desktopServerVersion = desktopPom.match(/<artifactId>jqviewer-desktop-server<\/artifactId>[\s\S]*?<version>([^<]+)<\/version>/)?.[1];

const checks = [
  ['android/app/build.gradle versionName', gradleVersionName],
  ['package-lock.json top-level version', packageLock.version],
  ['package-lock.json root package version', packageLock.packages?.['']?.version],
  ['README Version badge', readmeBadgeVersion],
  ['desktop-server/pom.xml project version', desktopServerVersion],
];

for (const [label, actualVersion] of checks) {
  if (!actualVersion) {
    fail(`${label} was not found`);
  } else if (actualVersion !== expectedVersion) {
    fail(`${label} is ${actualVersion}, expected ${expectedVersion}`);
  }
}

if (!process.exitCode) {
  console.log(`Version check passed: ${expectedVersion}`);
}
