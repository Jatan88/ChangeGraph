/** Accepts the repository URLs people paste, and returns one canonical form. */
export function normalizeGitHubRepoUrl(value: string): string | null {
  let raw = value.trim()
  if (!raw) return null
  raw = raw.replace(/\.git$/i, '').replace(/\/+$/, '')
  if (!/^https?:\/\//i.test(raw)) {
    raw = `https://${raw.replace(/^\/+/, '')}`
  }
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return null
  }
  const host = url.hostname.toLowerCase()
  if (host !== 'github.com' && host !== 'www.github.com') return null
  const parts = url.pathname.split('/').filter(Boolean)
  if (parts.length !== 2) return null
  if (!/^[\w.-]+$/.test(parts[0]) || !/^[\w.-]+$/.test(parts[1])) return null
  return `https://github.com/${parts[0]}/${parts[1]}`
}
