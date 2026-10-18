import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { Page, Project, RepositorySummary } from '../api/types'
import { EmptyState, ErrorText, PublicRepoNotice, StatusBadge } from '../components/ui'
import { normalizeGitHubRepoUrl } from '../githubUrl'
import { nextStep } from '../progress'

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
          <p className="kicker">Before you merge</p>
          <h1>Projects</h1>
          <p className="muted">Each project answers what a public change could break, why, and which tests to run.</p>
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
          body="Start with a name and a public GitHub repository. ChangeGraph fetches it and opens ingestion."
          action={{ to: '/projects/new', label: 'New project' }}
        />
      )}
      <div className="ledger">
        {projects?.map((project, index) => {
          const step = nextStep(project)
          return (
            <Link key={project.id} className="ledger-row project-card" to={`/projects/${project.id}`}>
              <span className="index">{String(index + 1).padStart(2, '0')}</span>
              <div>
                <h2>{project.name}</h2>
                <p>{project.description || 'No description'}</p>
              </div>
              <div className="ledger-meta">
                <StatusBadge value={project.graphStatus} />
                <p className="signal-line">
                  <span>{step.done ? 'Impact' : 'Next'}</span>
                  {step.done ? 'Report ready' : step.label}
                </p>
              </div>
            </Link>
          )
        })}
      </div>
    </section>
  )
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
    const url = normalizeGitHubRepoUrl(repositoryUrl)
    if (!url) {
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
      <p className="muted">Name it, paste the repository, and ChangeGraph fetches the latest commit.</p>
      <PublicRepoNotice />
      <ErrorText error={error} />
      <label>
        Name
        <input value={name} onChange={(event) => setName(event.target.value)} required maxLength={80} />
      </label>
      <label>
        Description, optional
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
      <p className="hint">github.com/owner/repository is enough. No access token is requested. Private repositories are rejected by the server.</p>
      <button className="button" type="submit" disabled={pending}>
        {pending ? 'Fetching repository…' : 'Create and fetch'}
      </button>
    </form>
  )
}
