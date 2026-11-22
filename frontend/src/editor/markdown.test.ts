import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './renderMarkdown'

const library = '58147143-0075-4643-8c1e-be628f8efd13'
const other = '2e58baa9-ae75-4c87-b2c3-5248f3a93429'
const image = '695271e0-6929-499c-84c8-611bd04d350b'
const url = '/api/libraries/' + library + '/images/' + image

describe('Markdown preview', () => {
  it('renders managed images only from the current library', () => {
    const preview = renderMarkdown([
      '![截图](' + url + ')',
      '![他库](/api/libraries/' + other + '/images/' + image + ')',
      '![本地](./images/demo.png)',
      '![外链](https://example.test/x.png)',
      '![带参数](' + url + '?rev=1)',
    ].join('\n'), library)
    expect(preview.unresolvedImageCount).toBe(4)
    expect(preview.html).toContain('src="' + url + '"')
    expect(preview.html).toContain('alt="截图"')
    expect(preview.html).not.toContain('src="https://example.test')
    expect((preview.html.match(/<img\b/g) ?? []).length).toBe(1)
  })

  it('escapes raw HTML and filters dangerous link schemes', () => {
    const preview = renderMarkdown(
      '<img src=x onerror=alert(1)>\n\n<script>bad()</script>\n\n[危险](javascript:bad) [可用](https://example.test)',
      library,
    )
    expect(preview.html).not.toContain('<script>')
    expect(preview.html).not.toContain('<img')
    expect(preview.html).not.toContain('href="javascript:')
    expect(preview.html).toContain('href="https://example.test"')
  })
})
