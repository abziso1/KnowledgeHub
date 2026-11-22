import { describe, expect, it } from 'vitest'
import { destinationForRole, matchesLibrary, resolveHome, switchLibrary } from '../router'

describe('workspace routing', () => {
  it('routes admins, ordinary users, and users without a library', () => {
    expect(resolveHome({ role: 'ADMIN' }, [{ id: 'unused' }])).toBe('/admin/users')
    expect(resolveHome({ role: 'USER' }, [{ id: 'learning' }])).toBe('/kb/learning')
    expect(resolveHome({ role: 'USER' }, [])).toBe('/app')
    expect(destinationForRole(null, 'USER')).toBe('/login')
    expect(destinationForRole({ role: 'ADMIN' }, 'USER')).toBe('/admin/users')
    expect(destinationForRole({ role: 'USER' }, 'ADMIN')).toBe('/app')
  })

  it('clears the old folder and ignores the old library response when switching', () => {
    expect(switchLibrary('old', 'folder', 'new')).toBe('/kb/new')
    expect(switchLibrary('old', 'folder', 'old')).toBe('/kb/old/folders/folder')
    expect(matchesLibrary('old', 'new')).toBe(false)
    expect(matchesLibrary('new', 'new')).toBe(true)
  })
})
