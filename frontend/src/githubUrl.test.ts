import { describe, expect, it } from 'vitest'
import { normalizeGitHubRepoUrl } from './githubUrl'

describe('normalizeGitHubRepoUrl', () => {
  it('accepts the forms people paste and returns one https URL', () => {
    expect(normalizeGitHubRepoUrl('https://github.com/owner/repository')).toBe('https://github.com/owner/repository')
    expect(normalizeGitHubRepoUrl('https://github.com/owner/repository/')).toBe('https://github.com/owner/repository')
    expect(normalizeGitHubRepoUrl('https://github.com/owner/repository.git')).toBe('https://github.com/owner/repository')
    expect(normalizeGitHubRepoUrl('http://www.github.com/owner/repository')).toBe('https://github.com/owner/repository')
    expect(normalizeGitHubRepoUrl('github.com/owner/repository')).toBe('https://github.com/owner/repository')
    expect(normalizeGitHubRepoUrl('  www.github.com/owner/repository?tab=readme  ')).toBe('https://github.com/owner/repository')
  })

  it('rejects hosts and paths that are not a repository', () => {
    expect(normalizeGitHubRepoUrl('https://example.com/private/repo')).toBeNull()
    expect(normalizeGitHubRepoUrl('https://github.com/owner')).toBeNull()
    expect(normalizeGitHubRepoUrl('https://github.com/owner/repository/tree/main')).toBeNull()
    expect(normalizeGitHubRepoUrl('not a url')).toBeNull()
  })
})
