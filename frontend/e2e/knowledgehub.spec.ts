import { expect, test, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const markdownPath = fileURLToPath(new URL('./fixtures/学习笔记.md', import.meta.url))
const png = readFileSync(new URL('./fixtures/tiny.png', import.meta.url))
const password = 'KnowledgeHubE2E1234'

async function signIn(page: Page, username: string, secret: string) {
  await page.goto('/login')
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(secret)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).not.toHaveURL(/\/login/)
}

async function register(page: Page, username: string) {
  await page.goto('/register')
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '注册', exact: true }).click()
  await expect(page.getByText('账号已创建，请登录。')).toBeVisible()
  await signIn(page, username, password)
}

async function createNamed(page: Page, action: string, name: string) {
  await page.getByRole('button', { name: action, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: action })
  await dialog.getByRole('textbox').fill(name)
  await dialog.getByRole('button', { name: '确定', exact: true }).click()
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible()
}

async function confirm(page: Page, dialogTitle: string, action: string) {
  await page.getByRole('dialog', { name: dialogTitle })
    .getByRole('button', { name: action, exact: true }).click()
}

test('two owners, Markdown, image, two trash scopes and account suspension', async ({ browser, page, baseURL }) => {
  const suffix = randomUUID().slice(0, 8)
  const owner = 'owner_' + suffix
  const otherContext = await browser.newContext({ baseURL })
  const adminContext = await browser.newContext({ baseURL })
  const otherPage = await otherContext.newPage()
  const adminPage = await adminContext.newPage()
  const library = '验收笔记 ' + suffix
  const otherLibrary = '另一个知识库 ' + suffix
  const folder = '验收资料'

  try {
    await register(page, owner)
    await register(otherPage, 'other_' + suffix)
    await expect(page.getByRole('heading', { name: '我的知识库', exact: true })).toBeVisible()
    await createNamed(page, '新建知识库', library)
    const libraryPage = new URL(page.url()).pathname
    await createNamed(page, '新建文件夹', folder)

    await page.locator('input[aria-label="导入 Markdown 文件"]').setInputFiles(markdownPath)
    await expect(page.getByRole('heading', { name: '学习笔记', exact: true })).toBeVisible()
    await expect(page.getByText('图片不可用 · 截图')).toBeVisible()
    const notePage = new URL(page.url()).pathname
    const noteApi = notePage.replace('/kb/', '/api/libraries/')

    const source = page.getByLabel('Markdown 正文')
    await source.evaluate(node => {
      const editor = node as HTMLTextAreaElement
      const placeholder = '![截图](./images/demo.png)'
      const start = editor.value.indexOf(placeholder)
      if (start < 0) throw new Error('Missing source image reference')
      editor.focus()
      editor.setSelectionRange(start, start + placeholder.length)
    })
    await page.locator('input[aria-label="上传笔记图片"]').setInputFiles({
      name: '截图.png', mimeType: 'image/png', buffer: png,
    })
    const previewImage = page.getByRole('img', { name: '截图' })
    await expect(previewImage).toBeVisible()
    await expect.poll(() => previewImage.evaluate(img => (img as HTMLImageElement).naturalWidth)).toBeGreaterThan(0)
    const imageUrl = await previewImage.getAttribute('src')
    expect(imageUrl).toMatch(/^\/api\/libraries\//)
    const privateImageUrl = imageUrl!
    await page.getByRole('button', { name: '保存', exact: true }).click()
    await expect(page.getByText('已保存', { exact: true })).toBeVisible()
    const savedSource = await source.inputValue()

    await source.fill(savedSource + '\n这段尚未保存')
    page.once('dialog', dialog => dialog.dismiss())
    await page.getByRole('link', { name: '搜索笔记' }).click()
    await expect(page).toHaveURL(new RegExp(notePage + '$'))
    await expect(source).toHaveValue(/这段尚未保存/)
    let logoutPrompt = false
    page.once('dialog', async dialog => {
      logoutPrompt = true
      await dialog.dismiss()
    })
    await page.getByRole('button', { name: '退出登录' }).click()
    await expect.poll(() => logoutPrompt).toBe(true)
    expect((await page.request.get('/api/auth/me')).status()).toBe(200)
    await expect(source).toHaveValue(/这段尚未保存/)
    await source.fill(savedSource)
    await page.getByRole('link', { name: '搜索笔记' }).click()
    await page.getByLabel('搜索关键词').fill('事务隔离')
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    await expect(page.getByRole('link', { name: '学习笔记', exact: true })).toBeVisible()

    await createNamed(page, '新建知识库', otherLibrary)
    await page.getByRole('link', { name: '搜索笔记' }).click()
    await page.getByLabel('搜索关键词').fill('事务隔离')
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    await expect(page.getByText('当前知识库没有符合条件的笔记。')).toBeVisible()
    expect((await otherPage.request.get(noteApi)).status()).toBe(404)
    expect((await otherPage.request.get(privateImageUrl)).status()).toBe(404)

    await page.getByRole('button', { name: library, exact: true }).click()
    await page.getByRole('tree').getByText(folder, { exact: true }).click()
    await page.getByRole('button', { name: '回收文件夹' }).click()
    await confirm(page, '移入内容回收站', '移入回收站')
    await expect(page).toHaveURL(url => url.pathname === libraryPage)
    expect((await page.request.get(noteApi)).status()).toBe(404)
    await page.getByRole('link', { name: '内容回收站' }).click()
    const folderRow = page.getByRole('list', { name: '回收条目' }).getByRole('listitem').filter({ hasText: folder })
    await folderRow.getByRole('button', { name: '恢复', exact: true }).click()
    await expect(page.getByText('回收站为空。')).toBeVisible()
    expect((await page.request.get(privateImageUrl)).status()).toBe(200)

    await page.getByRole('button', { name: '根目录', exact: true }).click()
    await page.getByRole('button', { name: '回收知识库' }).click()
    await confirm(page, '移入知识库回收站', '移入回收站')
    await expect(page).not.toHaveURL(new RegExp(libraryPage + '$'))
    expect((await page.request.get(privateImageUrl)).status()).toBe(404)
    await page.getByRole('link', { name: '知识库回收站' }).click()
    await page.getByRole('list', { name: '回收条目' }).getByRole('listitem').filter({ hasText: library })
      .getByRole('button', { name: '恢复', exact: true }).click()
    await expect(page.getByText('回收站为空。')).toBeVisible()
    await page.goto(notePage)
    await expect(page.getByRole('img', { name: '截图' })).toBeVisible()

    const admin = process.env.KH_E2E_ADMIN_USERNAME
    const secret = process.env.KH_E2E_ADMIN_PASSWORD
    if (!admin || !secret) throw new Error('Set KH_E2E_ADMIN_USERNAME and KH_E2E_ADMIN_PASSWORD')
    await signIn(adminPage, admin, secret)
    await expect(adminPage.getByRole('heading', { name: '账号管理' })).toBeVisible()
    const row = adminPage.getByRole('row').filter({ has: adminPage.getByText(owner, { exact: true }) })
    expect((await adminPage.request.get(privateImageUrl)).status()).toBe(403)
    await row.getByRole('button', { name: '停用账号' }).click()
    await confirm(adminPage, '停用账号', '停用')
    await expect(row.getByText('已停用')).toBeVisible()
    expect((await page.request.get(privateImageUrl)).status()).toBe(401)
    await row.getByRole('button', { name: '恢复账号' }).click()
    await confirm(adminPage, '恢复账号', '恢复')
    await expect(row.getByText('已启用')).toBeVisible()
    expect((await page.request.get(privateImageUrl)).status()).toBe(401)
    await signIn(page, owner, password)
    expect((await page.request.get(privateImageUrl)).status()).toBe(200)
  } finally {
    await otherContext.close()
    await adminContext.close()
  }
})
