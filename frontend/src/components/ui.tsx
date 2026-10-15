import { useEffect, useState, type ReactNode } from 'react'
import { Link, NavLink, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { Page, Project } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { Avatar } from './Avatar'
import FalkorDbStatus from './FalkorDbStatus'

const projectSections = [
  { label: 'Repository', path: '/repository' },
  { label: 'Graph', path: '/graph' },
  { label: 'Ingestion', path: '/ingestions' },
  { label: 'Analysis', path: '/analysis' },
  { label: 'Edit', path: '/settings' },
]

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
            <span className="brand-mark" aria-hidden="true" />
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
                            <button
                              type="button"
                              className="nav-branch"
                              aria-expanded={expanded}
                              aria-label={`${expanded ? 'Hide' : 'Show'} ${project.name} sections`}
                              onClick={() => setExpandedProjectId(expanded ? null : project.id)}
                            >
                              <span className={expanded ? 'nav-caret open' : 'nav-caret'} aria-hidden="true" />
                            </button>
                            <NavLink
                              end
                              to={`/projects/${project.id}`}
                              className="nav-project"
                              onClick={() => setExpandedProjectId(project.id)}
                            >
                              {project.name}
                            </NavLink>
                          </div>
                          {expanded && (
                            <div className="nav-children">
                              {projectSections.map((section) => (
                                <NavLink
                                  key={section.label}
                                  to={`/projects/${project.id}${section.path}`}
                                >
                                  {section.label}
                                </NavLink>
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
              <NavLink to="/account">Account</NavLink>
            </nav>
          )}
        </div>
        {user && (
          <div className="spine-user">
            <Avatar email={user.email} />
            <div className="spine-identity">
              <p className="spine-email">{user.email}</p>
              <button
                type="button"
                className="spine-logout"
                onClick={() => {
                  logout().then(() => navigate('/login'))
                }}
              >
                Log out
              </button>
            </div>
          </div>
        )}
      </aside>
      <div className="sheet">
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
      <div className="auth-panel">
        <p className="auth-brand">ChangeGraph</p>
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

export function EmptyState({ title, body }: { title: string; body: string }) {
  return (
    <section className="card empty">
      <h2>{title}</h2>
      <p>{body}</p>
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

export function StatusBadge({ value }: { value: string }) {
  const tone = value.toLowerCase()
  return <span className={`badge tone-${tone}`}>{value}</span>
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
          <p className="muted">Your session is stored in an HttpOnly cookie and restored when you refresh this page.</p>
        </div>
      </article>
      <FalkorDbStatus />
    </section>
  )
}
