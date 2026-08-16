import { existsSync, readFileSync, copyFileSync, mkdirSync } from 'node:fs'
import { resolve, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = resolve(fileURLToPath(new URL('..', import.meta.url)))
const sharedFiles = [
  'QueueStatusPanel.vue',
  'PlayerAccountDialog.vue',
  'MobileRegistrationFlow.vue',
  'MobileProfileSettings.vue',
  'machineConfiguration.js'
]

function parseTarget() {
  const argumentIndex = process.argv.findIndex((value) => value === '--site-main')
  if (argumentIndex >= 0 && process.argv[argumentIndex + 1]) return resolve(process.argv[argumentIndex + 1])
  const positional = process.argv.slice(2).find((value) => !value.startsWith('-'))
  return positional ? resolve(positional) : process.env.SITE_MAIN_DIR ? resolve(process.env.SITE_MAIN_DIR) : null
}

function readComparableFile(path) {
  return readFileSync(path, 'utf8').replace(/\r\n?/g, '\n')
}

const targetRoot = parseTarget()
if (!targetRoot) {
  console.error('请提供 site-main 路径：pnpm run sync:site-main -- --site-main <路径>')
  process.exit(2)
}

const targetDocs = join(targetRoot, 'docs')
if (!existsSync(targetDocs)) {
  console.error(`不是有效的 site-main 工作区：${targetDocs}`)
  process.exit(2)
}

let different = false
for (const file of sharedFiles) {
  const source = join(projectRoot, 'src', 'queue', file)
  const target = join(targetDocs, file)
  if (!existsSync(source)) throw new Error(`缺少公开站点源文件：${source}`)
  const sourceContent = readFileSync(source)
  const targetContent = existsSync(target) ? readComparableFile(target) : null
  if (!targetContent || sourceContent.toString().replace(/\r\n?/g, '\n') !== targetContent) different = true
  if (!process.argv.includes('--check')) {
    mkdirSync(targetDocs, { recursive: true })
    copyFileSync(source, target)
  }
}

if (process.argv.includes('--check')) {
  if (different) {
    console.error('公开队列页与 site-main 的共享组件不一致，请先运行 sync:site-main。')
    process.exit(1)
  }
  console.log('公开队列页与 site-main 的共享组件一致。')
} else {
  console.log(`已同步 ${sharedFiles.length} 个共享文件到 ${targetDocs}`)
}
