<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from './renderMarkdown'

const props = defineProps<{ markdown: string; libraryId: string }>()
const preview = computed(() => renderMarkdown(props.markdown, props.libraryId))

function onImageError(event: Event) {
  const image = event.target
  if (!(image instanceof HTMLImageElement)) return
  const placeholder = document.createElement('span')
  placeholder.className = 'image-placeholder'
  placeholder.textContent = image.alt ? '图片不可用 · ' + image.alt : '图片不可用'
  image.replaceWith(placeholder)
}
</script>

<template>
  <section class="preview-pane" aria-label="Markdown 预览">
    <div class="editor-pane-label">预览</div>
    <div v-if="!markdown" class="muted preview-content">暂无正文。</div>
    <article v-else :key="preview.html" class="markdown-preview preview-content"
             v-html="preview.html" @error.capture="onImageError" />
  </section>
</template>
