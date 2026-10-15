import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { Page, Project, RepositorySummary } from '../api/types'
import { EmptyState, ErrorText, PublicRepoNotice, StatusBadge } from '../components/ui'

export function DashboardPage() {
  const [projects, setProjects] = useState<Project[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    api<Page<Project>>('/api/projects')
      .then((page) => {
        if (!cancelled) setProjects(page.items)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof ApiError ? err.message : 'Could not load projects.')
      })
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <section className="stack">
      <div className="row">
        <div>
          <p className="kicker">Owned graphs</p>
          <h1>Projects</h1>
          <p className="muted">Each project has its own graph. You only see projects you own.</p>
        </div>
        <Link className="button" to="/projects/new">
          New project
        </Link>
      </div>
      <ErrorText error={error} />
      {projects === null && !error && <p className="muted">Loading projects…</p>}
      {projects?.length === 0 && (
        <EmptyState
          title="No projects yet"
          body="Create a project, connect a public GitHub repository, ingest a commit, and review the impact report."
        />
      )}
      <div className="ledger">
        {projects?.map((project, index) => (
          <Link key={project.id} className="ledger-row project-card" to={`/projects/${project.id}`}>
            <span className="index">{String(index + 1).padStart(2, '0')}</span>
            <div>
              <h2>{project.name}</h2>
              <p>{project.description || 'No description'}</p>
            </div>
            <div className="ledger-meta">
              <StatusBadge value={project.graphStatus} />
              <p className="muted">
                {project.repositoryConnected
                  ? `${project.repository?.ownerName}/${project.repository?.repositoryName}`
                  : 'No repository connected'}
              </p>
              <p className="muted">Ingestion: {project.latestIngestion?.status ?? 'None'}</p>
              <p className="muted">Latest analysis: {project.latestAnalysis?.status ?? 'None'}</p>
            </div>
          </Link>
        ))}
      </div>
    </section>
  )
}

function isPublicGitHubRepoUrl(value: string) {
  return /^https:\/\/github\.com\/[^/]+\/[^/]+\/?$/.test(value.trim().replace(/\.git$/, ''))
}

export function NewProjectPage() {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [repositoryUrl, setRepositoryUrl] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    const url = repositoryUrl.trim()
    if (!isPublicGitHubRepoUrl(url)) {
      setError('Use an https://github.com/owner/repository URL. Only public repositories can be fetched.')
      return
    }
    setPending(true)
    let created: Project | null = null
    try {
      created = await api<Project>('/api/projects', {
        method: 'POST',
        body: JSON.stringify({ name, description }),
      })
      await api<RepositorySummary>(`/api/projects/${created.id}/repository`, {
        method: 'POST',
        body: JSON.stringify({ repositoryUrl: url }),
      })
      await api(`/api/projects/${created.id}/ingestions`, {
        method: 'POST',
        body: JSON.stringify({}),
      })
      navigate(`/projects/${created.id}/ingestions`)
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Could not create the project.'
      if (created) {
        navigate(`/projects/${created.id}/repository`, { state: { linkError: message, repositoryUrl: url } })
        return
      }
      setError(message)
    } finally {
      setPending(false)
    }
  }

  return (
    <form className="card form" onSubmit={onSubmit}>
      <h1>New project</h1>
      <p className="muted">Add a public GitHub repository. ChangeGraph fetches it from GitHub and checks that it is public before anything is stored.</p>
      <PublicRepoNotice />
      <ErrorText error={error} />
      <label>
        Name
        <input value={name} onChange={(event) => setName(event.target.value)} required maxLength={80} />
      </label>
      <label>
        Description
        <textarea value={description} onChange={(event) => setDescription(event.target.value)} maxLength={500} />
      </label>
      <label>
        Public GitHub repository
        <input
          value={repositoryUrl}
          onChange={(event) => setRepositoryUrl(event.target.value)}
          placeholder="https://github.com/owner/repository"
          inputMode="url"
          autoComplete="off"
          required
        />
      </label>
      <p className="hint">No access token is requested. Private, missing, and rate-limited repositories are rejected by the server.</p>
      <button className="button" type="submit" disabled={pending}>
        {pending ? 'Fetching repository…' : 'Create and fetch'}
      </button>
    </form>
  )
}
