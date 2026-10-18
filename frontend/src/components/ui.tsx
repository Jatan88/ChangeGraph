import { useEffect, useState, type ReactNode } from 'react'
import { Link, NavLink, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { Page, Project } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { Avatar } from './Avatar'
import FalkorDbStatus from './FalkorDbStatus'

const THEME_KEY = 'changegraph-theme'

function currentTheme(): 'light' | 'dark' {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light'
}

export function ThemeToggle() {
  const [theme, setTheme] = useState<'light' | 'dark'>(currentTheme)

  function toggle() {
    const next = theme === 'dark' ? 'light' : 'dark'
    document.documentElement.dataset.theme = next
    localStorage.setItem(THEME_KEY, next)
    setTheme(next)
  }

  return (
    <button type="button" className="theme-toggle" aria-pressed={theme === 'dark'} onClick={toggle}>
      {theme === 'dark' ? 'Light mode' : 'Dark mode'}
    </button>
  )
}

const projectSections = [
  { label: 'Activity', path: '/activity', group: 'Review' },
  { label: 'Analysis', path: '/analysis', group: 'Review' },
  { label: 'Incidents', path: '/incidents', group: 'Review' },
  { label: 'Repository', path: '/repository', group: 'Source' },
  { label: 'Ingestion', path: '/ingestions', group: 'Source' },
  { label: 'Graph', path: '/graph', group: 'Source' },
  { label: 'Edit', path: '/settings', group: 'Project' },
]

function sectionGroups() {
  const groups: { name: string; items: typeof projectSections }[] = []
  for (const section of projectSections) {
    const current = groups[groups.length - 1]
    if (current?.name === section.group) current.items.push(section)
    else groups.push({ name: section.group, items: [section] })
  }
  return groups
}

export function RequireAuth() {
  const { user, ready } = useAuth()
  if (!ready) {
    return <p className="muted">Restoring your session…</p>
  }
  if (!user) {
    return <Navigate to="/login" replace />
  }
  return <Outlet />
}

export function Shell() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const projectMatch = location.pathname.match(/^\/projects\/([^/]+)/)
  const activeProjectId = projectMatch && projectMatch[1] !== 'new' ? projectMatch[1] : null
  const [projectsOpen, setProjectsOpen] = useState(true)
  const [expandedProjectId, setExpandedProjectId] = useState<string | null>(null)
  const [projects, setProjects] = useState<Project[] | null>(null)
  const [navError, setNavError] = useState<string | null>(null)

  useEffect(() => {
    if (activeProjectId) setProjectsOpen(true)
  }, [activeProjectId])

  useEffect(() => {
    if (!user) return
    let cancelled = false
    const load = () => {
      api<Page<Project>>('/api/projects')
        .then((page) => {
          if (!cancelled) {
            setProjects(page.items)
            setNavError(null)
          }
        })
        .catch((err: unknown) => {
          if (!cancelled) setNavError(err instanceof ApiError ? err.message : 'Could not load projects.')
        })
    }
    load()
    window.addEventListener('changegraph:projects', load)
    return () => {
      cancelled = true
      window.removeEventListener('changegraph:projects', load)
    }
  }, [user, location.pathname])

  return (
    <div className="shell">
      <aside className="spine">
        <div className="spine-top">
          <Link className="brand" to={user ? '/' : '/login'}>
            <Logo />
            ChangeGraph
          </Link>
          {user && (
            <nav className="nav" aria-label="Project">
              <div className="nav-group">
                <button
                  type="button"
                  className="nav-disclosure"
                  aria-expanded={projectsOpen}
                  onClick={() => {
                    setProjectsOpen((open) => !open)
                    setExpandedProjectId(null)
                  }}
                >
                  Projects
                  <span className={projectsOpen ? 'nav-caret open' : 'nav-caret'} aria-hidden="true" />
                </button>
                {projectsOpen && (
                  <div className="nav-tree">
                    {projects === null && !navError && <p className="nav-note">Loading projects…</p>}
                    {navError && <p className="nav-note">{navError}</p>}
                    {projects?.map((project) => {
                      const expanded = expandedProjectId === project.id
                      return (
                        <div key={project.id}>
                          <div className="nav-project-row">
                            <NavLink
                              end
                              to={`/projects/${project.id}`}
                              className="nav-project"
                              onClick={() => setExpandedProjectId(project.id)}
                            >
                              {project.name}
                            </NavLink>
                            <button
                              type="button"
                              className="nav-branch"
                              aria-expanded={expanded}
                              aria-label={`${expanded ? 'Hide' : 'Show'} ${project.name} sections`}
                              onClick={() => setExpandedProjectId(expanded ? null : project.id)}
                            >
                              <span className={expanded ? 'nav-caret open' : 'nav-caret'} aria-hidden="true" />
                            </button>
                          </div>
                          {expanded && (
                            <div className="nav-children">
                              {sectionGroups().map((group) => (
                                <div key={group.name} className="nav-chunk">
                                  <p className="nav-chunk-label">{group.name}</p>
                                  {group.items.map((section) => (
                                    <NavLink
                                      key={section.label}
                                      to={`/projects/${project.id}${section.path}`}
                                    >
                                      {section.label}
                                    </NavLink>
                                  ))}
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      )
                    })}
                    <NavLink className="nav-new" to="/projects/new">New project</NavLink>
                  </div>
                )}
              </div>
            </nav>
          )}
        </div>
        {user && (
          <div className="spine-foot">
            <NavLink className="spine-user" to="/account" title="Account">
              <Avatar email={user.email} />
              <div className="spine-identity">
                <p className="spine-email">{user.email}</p>
                <button
                  type="button"
                  className="spine-logout"
                  onClick={(event) => {
                    event.preventDefault()
                    event.stopPropagation()
                    logout().then(() => navigate('/login'))
                  }}
                >
                  Log out
                </button>
              </div>
            </NavLink>
          </div>
        )}
      </aside>
      <div className="sheet">
        <div className="sheet-tools">
          <ThemeToggle />
        </div>
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

export function AuthStage({ children }: { children: ReactNode }) {
  return (
    <main className="auth-screen">
      <div className="auth-tools"><ThemeToggle /></div>
      <div className="auth-panel">
        <p className="auth-brand"><Logo />ChangeGraph</p>
        {children}
      </div>
    </main>
  )
}

export function ErrorText({ error }: { error: string | null }) {
  if (!error) return null
  return (
    <p className="banner error" role="alert">
      {error}
    </p>
  )
}

export function EmptyState({ title, body, action }: { title: string; body: string; action?: { to: string; label: string } }) {
  return (
    <section className="card empty">
      <h2>{title}</h2>
      <p>{body}</p>
      {action && <Link className="button" to={action.to}>{action.label}</Link>}
    </section>
  )
}

export function PublicRepoNotice() {
  return (
    <p className="banner">
      This version supports public GitHub repositories only. Private repositories are not requested, stored, or ingested.
    </p>
  )
}

export function Logo() {
  return (
    <svg className="brand-mark" viewBox="0 0 32 32" aria-hidden="true">
      <rect width="32" height="32" rx="8" fill="var(--accent)" />
      <path d="M9.2 21.8 15.1 11.4" fill="none" stroke="#f7f8fb" strokeWidth="1.7" strokeLinecap="round" />
      <path d="M16.9 11.3 23.6 19.2" fill="none" stroke="#f7f8fb" strokeWidth="1.7" strokeLinecap="round" />
      <path d="M16.3 12.2 21.3 25.2" fill="none" stroke="#f7f8fb" strokeWidth="1.45" strokeLinecap="round" opacity="0.55" />
      <circle cx="16" cy="9.4" r="3.05" fill="#ffffff" />
      <circle cx="8" cy="23.6" r="2.35" fill="#f7f8fb" />
      <circle cx="24.6" cy="20" r="2.35" fill="#f7f8fb" />
      <circle cx="21.6" cy="26.6" r="1.95" fill="#f7f8fb" opacity="0.78" />
    </svg>
  )
}

export function BranchText({ value }: { value: string | null | undefined }) {
  const text = value && value.trim() ? value : 'Unknown'
  const pieces = text.split('/')
  return (
    <>
      {pieces.map((piece, index) => (
        <span key={`${piece}-${index}`}>
          {index > 0 && <><span aria-hidden="true">/</span><wbr /></>}
          {piece}
        </span>
      ))}
    </>
  )
}

export function StatusBadge({ value }: { value: string }) {
  const tone = value.toLowerCase().replaceAll(' ', '_')
  const label = value.replaceAll('_', ' ')
  return <span className={`badge tone-${tone}`}>{label}</span>
}

export function AccountPanel() {
  const { user } = useAuth()
  return (
    <section className="stack">
      <h1>Account</h1>
      <article className="card account-card">
        {user && <Avatar email={user.email} size={72} />}
        <div>
          <h2>Signed in</h2>
          <p>{user?.email}</p>
          <p className="muted">You stay signed in on this browser until you log out. Refreshing this page keeps the session.</p>
        </div>
      </article>
      <FalkorDbStatus />
    </section>
  )
}
