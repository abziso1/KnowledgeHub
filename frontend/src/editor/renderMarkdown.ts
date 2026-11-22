import MarkdownIt from 'markdown-it'

const markdown = new MarkdownIt({ html: false, linkify: false, typographer: false })
const uuid = '[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}'
const managedImage = new RegExp('^/api/libraries/(' + uuid + ')/images/' + uuid + '$')
const defaultImage = markdown.renderer.rules.image!
const defaultLinkCheck = markdown.validateLink.bind(markdown)

markdown.validateLink = destination => {
  if (!defaultLinkCheck(destination) || destination.startsWith('//')) return false
  const scheme = /^([a-z][a-z0-9+.-]*):/i.exec(destination)
  return !scheme || ['http', 'https', 'mailto'].includes(scheme[1].toLowerCase())
}

interface PreviewEnvironment {
  libraryId: string
  unresolvedImageCount: number
}

markdown.renderer.rules.image = (tokens, index, options, env, renderer) => {
  const token = tokens[index]
  const preview = env?.preview as PreviewEnvironment | undefined
  const target = String(token.attrGet('src') ?? '')
  const match = managedImage.exec(target)
  if (preview && match?.[1].toLowerCase() === preview.libraryId.toLowerCase()) {
    return defaultImage(tokens, index, options, env, renderer)
  }
  if (preview) preview.unresolvedImageCount++
  const alt = markdown.utils.escapeHtml(renderer.renderInlineAsText(token.children ?? [], options, env))
  return '<span class="image-placeholder">图片不可用' + (alt ? ' · ' + alt : '') + '</span>'
}

export function renderMarkdown(source: string, libraryId: string) {
  const preview: PreviewEnvironment = { libraryId, unresolvedImageCount: 0 }
  const html = markdown.render(source, { preview })
  return { html, unresolvedImageCount: preview.unresolvedImageCount }
}
