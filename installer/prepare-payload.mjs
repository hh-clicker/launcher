import { chmodSync, cpSync, existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, extname, join, resolve, sep } from 'node:path'
import { execFileSync } from 'node:child_process'

// Linux distribution staging. The Windows installer keeps its existing PowerShell staging path.
const [workspaceArgument, destinationArgument, version, releaseArgument, notesArgument, channel] = process.argv.slice(2)
if (!workspaceArgument || !destinationArgument || !/^\d+\.\d+\.\d+$/.test(version ?? '') || !releaseArgument || !notesArgument || !['stable', 'nightly'].includes(channel)) {
  throw new Error('Expected workspace, destination, numeric version, release definition, notes, and stable|nightly')
}

const workspace = resolve(workspaceArgument)
const destination = resolve(destinationArgument)
const releaseDefinition = resolve(releaseArgument)
const releaseNotes = resolve(notesArgument)
const launcher = join(workspace, 'launcher')
const buildRoot = join(launcher, 'desktopApp', 'build')
if (!destination.startsWith(buildRoot + sep)) throw new Error('Staging directory must be inside desktopApp/build')
if (!existsSync(releaseDefinition) || !existsSync(releaseNotes)) throw new Error('Release definition or notes are missing')

const release = JSON.parse(readFileSync(releaseDefinition, 'utf8'))
if (release.schemaVersion !== 1 || !Number.isInteger(release.configVersion) || !Number.isInteger(release.postgresMajor)) {
  throw new Error('Invalid release definition')
}
if (channel === 'stable' && release.version !== version) throw new Error('Release version does not match distribution version')
const versionParts = value => value.split('.').map(Number)
const compareVersions = (a, b) => {
  const left = versionParts(a)
  const right = versionParts(b)
  for (let index = 0; index < 3; index++) if (left[index] !== right[index]) return left[index] - right[index]
  return 0
}
if (channel === 'nightly') {
  if (compareVersions(version, release.version) < 0) throw new Error('Nightly version is older than the base release')
  release.build = { channel: 'nightly', baseVersion: release.version, createdAt: new Date().toISOString() }
  release.version = version
  release.databaseMigration = true
}

const repositories = ['backend', 'browser-agent', 'frontend', 'generation-agent', 'infra']
const inspected = channel === 'nightly' ? [...repositories, 'launcher'] : repositories
const git = (directory, ...args) => execFileSync('git', ['-c', `safe.directory=${directory}`, '-C', directory, ...args], { encoding: 'utf8' }).trim()
for (const name of inspected) {
  const repository = join(workspace, name)
  if (!existsSync(join(repository, '.git'))) throw new Error(`Repository missing: ${name}`)
  const head = git(repository, 'rev-parse', 'HEAD')
  const dirty = git(repository, 'status', '--porcelain', '--untracked-files=all') !== ''
  if (channel === 'stable') {
    if (release.repositories?.[name]?.commit !== head || dirty) throw new Error(`${name} is not at its clean pinned revision`)
  } else {
    release.repositories[name] = { commit: head, dirty }
  }
}

rmSync(destination, { recursive: true, force: true })
mkdirSync(destination, { recursive: true })
for (const name of repositories) {
  const repository = join(workspace, name)
  const tracked = execFileSync('git', ['-c', 'core.quotepath=false', '-c', `safe.directory=${repository}`, '-C', repository, 'ls-files', '--cached', '-z'])
  const untracked = channel === 'nightly'
    ? execFileSync('git', ['-c', 'core.quotepath=false', '-c', `safe.directory=${repository}`, '-C', repository, 'ls-files', '--others', '--exclude-standard', '-z'])
    : Buffer.alloc(0)
  const files = new Set(Buffer.concat([tracked, untracked]).toString('utf8').split('\0').filter(Boolean))
  for (const nameInRepository of files) {
    if (nameInRepository.startsWith('tools/') || extname(nameInRepository).toLowerCase() === '.md') continue
    const source = resolve(repository, nameInRepository)
    const target = resolve(destination, name, nameInRepository)
    if (!source.startsWith(repository + sep) || !target.startsWith(join(destination, name) + sep)) {
      throw new Error(`Git returned a path outside ${name}`)
    }
    if (!existsSync(source)) continue
    mkdirSync(dirname(target), { recursive: true })
    const info = lstatSync(source)
    if (info.isSymbolicLink()) throw new Error(`Symlink in release payload needs explicit review: ${name}/${nameInRepository}`)
    if (info.isFile()) {
      cpSync(source, target, { preserveTimestamps: true })
      chmodSync(target, info.mode)
    }
  }
}

const browserAgent = join(destination, 'browser-agent')
const npm = (...args) => execFileSync('npm', args, { cwd: browserAgent, stdio: 'inherit' })
npm('ci')
npm('run', 'build')
npm('prune', '--omit=dev')
const removeMatching = (directory, predicate) => {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) removeMatching(path, predicate)
    else if (predicate(path)) rmSync(path)
  }
}
removeMatching(join(browserAgent, 'dist'), path => path.endsWith('.map') || path.endsWith('.d.ts'))
removeMatching(destination, path => extname(path).toLowerCase() === '.md')

writeFileSync(join(destination, '.hh-clicker-bundle'), `version=${version}\nlayout=installer-v2\n${channel === 'nightly' ? 'channel=nightly\n' : ''}`)
writeFileSync(join(destination, 'release-manifest.json'), JSON.stringify(release, null, 2) + '\n')
const notes = channel === 'nightly'
  ? `# HH Clicker ${version} - nightly\n\nLocal test build from current repository working trees, including uncommitted changes. This is not an official release.\n`
  : readFileSync(releaseNotes, 'utf8')
writeFileSync(join(destination, 'release-notes.ru.md'), notes)
const catalog = dirname(dirname(releaseDefinition))
const notesDirectory = join(destination, 'release-notes')
mkdirSync(notesDirectory, { recursive: true })
const noteVersions = readdirSync(catalog, { withFileTypes: true })
  .filter(entry => entry.isDirectory() && /^\d+\.\d+\.\d+$/.test(entry.name) && compareVersions(entry.name, version) <= 0)
  .map(entry => entry.name)
  .sort(compareVersions)
  .filter(entry => channel !== 'nightly' || entry !== version)
for (const catalogVersion of noteVersions) {
  const catalogNotes = join(catalog, catalogVersion, 'notes.ru.md')
  const catalogDefinition = join(catalog, catalogVersion, 'release.json')
  if (!existsSync(catalogNotes) || !existsSync(catalogDefinition)) throw new Error(`Incomplete release catalog: ${catalogVersion}`)
  if (JSON.parse(readFileSync(catalogDefinition, 'utf8')).version !== catalogVersion) throw new Error(`Invalid release catalog: ${catalogVersion}`)
  cpSync(catalogNotes, join(notesDirectory, `${catalogVersion}.ru.md`))
}
if (channel === 'nightly') {
  writeFileSync(join(notesDirectory, `${version}.ru.md`), notes)
  noteVersions.push(version)
}
writeFileSync(join(notesDirectory, 'release-notes.index'), noteVersions.join('\n') + '\n')
console.log(`Linux distribution payload ready: ${destination}`)
