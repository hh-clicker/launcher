import { readFileSync, mkdirSync, copyFileSync } from 'node:fs'
import { basename, dirname, extname, join } from 'node:path'

const policy = JSON.parse(readFileSync(new URL('./license-policy.json', import.meta.url), 'utf8'))
const legalFile = new RegExp(policy.legalFilePattern, 'i')
const legalDirectory = new RegExp(policy.legalDirectoryPattern, 'i')

export function excludeMarkdown(path) {
  const inLegalDirectory = path.split(/[\\/]/).slice(0, -1).some(part => legalDirectory.test(part))
  return extname(path).toLowerCase() === '.md' && !legalFile.test(basename(path)) && !inLegalDirectory
}

export function stageLauncherLicenses(launcher, destination) {
  for (const { source, target } of policy.launcherFiles) {
    const output = join(destination, target)
    mkdirSync(dirname(output), { recursive: true })
    // Missing required notices must fail packaging, not produce a partial distribution.
    copyFileSync(join(launcher, source), output)
  }
}
