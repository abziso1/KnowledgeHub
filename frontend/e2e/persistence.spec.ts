import { expect, test, type APIRequestContext } from '@playwright/test'
import { createHash, randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { CsrfToken, ImageView, LibraryView, NoteView } from '../src/api/contracts'

interface State {
  username: string
  password: string
  notePath: string
  imagePath: string
  markdown: string
  sha256: string
}

const mode = process.env.KH_PERSISTENCE_MODE
const statePath = process.env.KH_PERSISTENCE_STATE
  ? resolve(process.env.KH_PERSISTENCE_STATE)
  : fileURLToPath(new URL('./.state/persistence.json', import.meta.url))

function digest(buffer: Buffer) {
  return createHash('sha256').update(buffer).digest('hex')
}

async function csrf(request: APIRequestContext) {
  const response = await request.get('/api/auth/csrf')
  expect(response.status()).toBe(200)
  const { headerName, token } = await response.json() as CsrfToken
  return { [headerName]: token }
}

test('the same account, note and image survive restart or restore', async ({ request, page }) => {
  test.skip(mode !== 'seed' && mode !== 'verify', 'Set KH_PERSISTENCE_MODE to seed or verify')
  if (mode === 'seed') {
    const username = 'persist_' + randomUUID().replaceAll('-', '').slice(0, 12)
    const password = 'Stable' + randomUUID().replaceAll('-', '')
    const credentials = { username, password }
    expect((await request.post('/api/auth/register', { headers: await csrf(request), data: credentials })).status()).toBe(201)
    expect((await request.post('/api/auth/login', { headers: await csrf(request), data: credentials })).status()).toBe(200)
    const libraries = await request.get('/api/libraries')
    expect(libraries.status()).toBe(200)
    const [library] = await libraries.json() as LibraryView[]
    const base = '/api/libraries/' + library.id
    const png = await readFile(new URL('./fixtures/tiny.png', import.meta.url))
    const uploaded = await request.post(base + '/images', {
      headers: await csrf(request),
      multipart: { file: { name: '恢复截图.png', mimeType: 'image/png', buffer: png } },
    })
    expect(uploaded.status()).toBe(201)
    const image = await uploaded.json() as ImageView
    const created = await request.post(base + '/notes', {
      headers: await csrf(request), data: { title: '恢复验收笔记', folderId: null },
    })
    expect(created.status()).toBe(201)
    const note = await created.json() as NoteView
    const markdown = '备份恢复校验\n\n![恢复截图](' + image.url + ')'
    const noteApi = base + '/notes/' + note.id
    expect((await request.put(noteApi, {
      headers: await csrf(request), data: { title: note.title, version: note.version, markdown },
    })).status()).toBe(200)
    const readBack = await request.get(image.url)
    expect(readBack.status()).toBe(200)
    expect(digest(await readBack.body())).toBe(digest(png))
    const state: State = { username, password, notePath: noteApi.replace('/api/libraries/', '/kb/'),
      imagePath: image.url, markdown, sha256: digest(png) }
    await mkdir(dirname(statePath), { recursive: true })
    await writeFile(statePath, JSON.stringify(state, null, 2) + '\n', { mode: 0o600 })
    return
  }

  const state = JSON.parse(await readFile(statePath, 'utf8')) as State
  await page.goto('/login')
  await page.getByLabel('用户名').fill(state.username)
  await page.getByLabel('密码').fill(state.password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(state.notePath)
  await expect(page.getByRole('heading', { name: '恢复验收笔记' })).toBeVisible()
  await expect(page.getByLabel('Markdown 正文')).toHaveValue(state.markdown)
  await expect(page.getByRole('img', { name: '恢复截图' })).toBeVisible()
  const image = await page.request.get(state.imagePath)
  expect(image.status()).toBe(200)
  expect(digest(await image.body())).toBe(state.sha256)
})
