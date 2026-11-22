import { computed, readonly, ref, shallowRef } from 'vue'
import type { NoteView, SaveNoteInput } from '../api/contracts'

export function useNoteDraft(note: NoteView) {
  const savedNote = shallowRef(note)
  const draftTitle = ref(note.title)
  const draftMarkdown = ref(note.markdown)
  const saving = ref(false)
  const dirty = computed(() => draftTitle.value !== savedNote.value.title || draftMarkdown.value !== savedNote.value.markdown)

  async function save(send: (input: SaveNoteInput) => Promise<NoteView>): Promise<void> {
    if (saving.value || !dirty.value) return
    const input = { title: draftTitle.value, markdown: draftMarkdown.value, version: savedNote.value.version }
    saving.value = true
    try {
      const saved = await send(input)
      if (draftTitle.value === input.title) draftTitle.value = saved.title
      if (draftMarkdown.value === input.markdown) draftMarkdown.value = saved.markdown
      savedNote.value = saved
    } finally {
      saving.value = false
    }
  }

  return { draftTitle, draftMarkdown, dirty, savedNote: readonly(savedNote), saving: readonly(saving), save }
}
