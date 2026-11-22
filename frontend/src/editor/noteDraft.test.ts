import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import type { NoteView } from '../api/contracts'
import { useNoteDraft } from './useNoteDraft'

const note: NoteView = {
  id: 'note', folderId: null, title: '目录', markdown: '已保存', version: 7,
  updatedAt: '2026-11-22T00:00:00Z',
}

describe('manual note draft', () => {
  it('clears dirty state and adopts the returned version on save', async () => {
    const draft = useNoteDraft(note)
    draft.draftMarkdown.value = '新版正文'
    expect(draft.dirty.value).toBe(true)
    const send = vi.fn().mockResolvedValue({ ...note, markdown: '新版正文', version: 8 })
    await draft.save(send)
    expect(send).toHaveBeenCalledWith({ title: '目录', markdown: '新版正文', version: 7 })
    expect(draft.dirty.value).toBe(false)
    draft.draftMarkdown.value = '第三版'
    await draft.save(send)
    expect(send).toHaveBeenLastCalledWith({ title: '目录', markdown: '第三版', version: 8 })
  })

  it.each(['VERSION_CONFLICT', 'IMAGE_UNAVAILABLE'])('keeps typed content on %s', async code => {
    const draft = useNoteDraft(note)
    draft.draftTitle.value = '未保存标题'
    draft.draftMarkdown.value = '未保存的 Markdown'
    const send = vi.fn().mockRejectedValue(new ApiError(code === 'VERSION_CONFLICT' ? 409 : 422, code, '保存失败'))
    await expect(draft.save(send)).rejects.toMatchObject({ code })
    expect(draft.draftTitle.value).toBe('未保存标题')
    expect(draft.draftMarkdown.value).toBe('未保存的 Markdown')
    expect(draft.dirty.value).toBe(true)
  })

  it('preserves further typing while a save is in flight', async () => {
    const draft = useNoteDraft(note)
    draft.draftMarkdown.value = '正在保存'
    let finish!: (saved: NoteView) => void
    const request = draft.save(() => new Promise(resolve => { finish = resolve }))
    draft.draftMarkdown.value = '保存期间继续输入'
    finish({ ...note, markdown: '正在保存', version: 8 })
    await request
    expect(draft.draftMarkdown.value).toBe('保存期间继续输入')
    expect(draft.dirty.value).toBe(true)
  })
})
