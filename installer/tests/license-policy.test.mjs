import assert from 'node:assert/strict'
import { test } from 'node:test'
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, unlinkSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { excludeMarkdown, stageLauncherLicenses } from '../license-policy.mjs'

const preserved = ['LICENSE', 'LICENSE.md', 'license.MD', 'LICENCE.md', 'LICENSE-MIT.md',
  'NOTICE.md', 'COPYING.md', 'COPYRIGHT.md', 'PATENTS.md', 'AUTHORS.md',
  'THIRD_PARTY_NOTICES.md', 'third-party-licenses.md', 'ThirdPartyNotices.md', 'LICENSES.md']
const excluded = ['README.md', 'CHANGELOG.md', 'AGENTS.md', 'PROJECT_HANDOFF.md', 'licensed-example.md']
const policy = JSON.parse(readFileSync(new URL('../license-policy.json', import.meta.url), 'utf8'))

test('service sources and nested production packages retain legal Markdown only', () => {
  for (const directory of ['', 'backend/', 'browser-agent/node_modules/example/']) {
    for (const name of preserved) assert.equal(excludeMarkdown(directory + name), false, name)
    for (const name of excluded) assert.equal(excludeMarkdown(directory + name), true, name)
  }
  for (const path of ['LICENSES/Apache-2.0.md', 'node_modules/example/legal/attribution.md',
    'node_modules\\example\\licenses\\custom.md', 'UNLICENSE.md']) {
    assert.equal(excludeMarkdown(path), false, path)
  }
})

test('launcher and dependency legal files are staged byte for byte', () => {
  const temporary = mkdtempSync(join(tmpdir(), 'hh-license-policy-'))
  try {
    const source = join(temporary, 'source')
    const destination = join(temporary, 'output')
    for (const { source: name } of policy.launcherFiles) {
      const file = join(source, name)
      mkdirSync(dirname(file), { recursive: true })
      writeFileSync(file, `Synthetic license: ${name}\r\nCopyright Example\n`)
    }
    stageLauncherLicenses(source, destination)
    for (const { source: name, target } of policy.launcherFiles) {
      assert.deepEqual(readFileSync(join(destination, target)), readFileSync(join(source, name)))
    }
    unlinkSync(join(source, 'licenses/skiko-0.144.6/NOTICE'))
    assert.throws(() => stageLauncherLicenses(source, destination), /ENOENT/)
    assert.throws(() => stageLauncherLicenses(join(temporary, 'missing'), destination), /ENOENT/)
  } finally {
    rmSync(temporary, { recursive: true, force: true })
  }
})

test('Skiko, libffi and Skia notices are required and retain their exact versioned text', () => {
  const launcher = fileURLToPath(new URL('../../', import.meta.url))
  const required = [
    ['licenses/skiko-0.144.6/NOTICE', '35dd077c24768b53625101cfcc0ad853ae7e2abc08bd2a111a0267760d1406d8'],
    ['licenses/jna-5.18.1/libffi-LICENSE', '2c9c2acb9743e6b007b91350475308aee44691d96aa20eacef8e199988c8c388'],
    ['licenses/skia-m144-22f58c9fd4/LICENSE', '5f787c1dee3c56547f09ccc2906ab5f5293c4d8dd6c8654e573216c38e908dbd'],
  ]
  for (const [name, expected] of required) {
    assert.ok(policy.launcherFiles.some(entry => entry.source === name && entry.target === name), name)
    // Text normalization tolerates checkout line endings; the packaging test above checks byte preservation.
    const text = readFileSync(join(launcher, name), 'utf8').replaceAll('\r\n', '\n')
    assert.equal(createHash('sha256').update(text).digest('hex'), expected, name)
  }
})

test('Windows PowerShell uses the same legal-file policy and staging rules', { skip: process.platform !== 'win32' }, () => {
  const installer = fileURLToPath(new URL('../', import.meta.url))
  // Node preserves PS7's module search path, unlike a direct PS7 -> powershell.exe launch.
  // Let Windows PowerShell initialize its own default module paths in this child only.
  const environment = Object.fromEntries(Object.entries(process.env)
    .filter(([name]) => name.toLowerCase() !== 'psmodulepath'))
  execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
    '-File', resolve(installer, 'tests/license-policy.test.ps1')], { stdio: 'pipe', env: environment })
})
