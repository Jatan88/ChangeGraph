import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type {
  AgentReport,
  AnalysisReport,
  AnalysisSummary,
  CommitDetail,
  CommitPoll,
  CommitSummary,
  GraphView,
  IngestionJob,
  Page,
  Project,
  PullRequest,
  RepositorySummary,
} from '../api/types'
import { BranchSelect } from '../components/BranchSelect'
import { ErrorText, PublicRepoNotice, StatusBadge } from '../components/ui'
import { normalizeGitHubRepoUrl } from '../githubUrl'
import { nextSentence, projectProgress } from '../progress'

export function useProject() {
  const { projectId = '' } = useParams()
  const [project, setProject] = useState<Project | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setProject(null)
    api<Project>(`/api/projects/${projectId}`)
      .then((value) => {
        if (!cancelled) setProject(value)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof ApiError ? err.message : 'Project not found.')
      })
    return () => {
      cancelled = true
    }
  }, [projectId])

  return { projectId, project, error, setProject }
}

export function ProjectOverviewPage() {
  const { projectId, project, error } = useProject()
  const navigate = useNavigate()
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [pending, setPending] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  async function remove() {
    setPending(true)
    setDeleteError(null)
    try {
      await api<void>(`/api/projects/${projectId}?confirm=true`, { method: 'DELETE' })
      window.dispatchEvent(new Event('changegraph:projects'))
      navigate('/')
    } catch (err) {
      setDeleteError(err instanceof ApiError ? err.message : 'Delete failed.')
      setPending(false)
    }
  }

  if (error) return <ErrorText error={error} />
  if (!project) return <p className="muted">Loading project…</p>

  const steps = projectProgress(project)
  const done = steps.filter((step) => step.done).length
  const current = steps.find((step) => !step.done)

  return (
    <section className="stack">
      <div className="row">
        <div>
          <p className="kicker">What could break</p>
          <h1>{project.name}</h1>
          <p>{project.description || 'No description'}</p>
          <p className="muted">{nextSentence(current ?? steps[steps.length - 1])}</p>
        </div>
      </div>
      <p className="muted">{done} of {steps.length} complete</p>
      <ol className="journey" aria-label="Path to an impact report">
        {steps.map((step, index) => (
          <li key={step.id} className={step.done ? 'is-done' : step === current ? 'is-current' : undefined}>
            <Link to={step.to} aria-current={step === current ? 'step' : undefined}>
              <span className="step-index">{index + 1}</span>
              <strong>{step.label}</strong>
              <span className="step-detail">{step.detail}</span>
            </Link>
          </li>
        ))}
      </ol>
      {project.repository && (
        <article className="card">
          <h2>Repository</h2>
          <dl className="repo-facts">
            <div>
              <dt>Repository</dt>
              <dd>{project.repository.ownerName}/{project.repository.repositoryName}</dd>
            </div>
            <div>
              <dt>Selected branch</dt>
              <dd>{project.repository.selectedBranch}</dd>
            </div>
            <div>
              <dt>Default branch</dt>
              <dd>{project.repository.defaultBranch}</dd>
            </div>
            <div>
              <dt>Visibility</dt>
              <dd>{project.repository.visibility}</dd>
            </div>
          </dl>
        </article>
      )}
      <section className="card danger-zone">
        <h2>Delete project</h2>
        <p>Deletion removes this project’s database rows and its own FalkorDB graph. It does not delete any other project’s graph.</p>
        {deleteError && <ErrorText error={deleteError} />}
        <label className="check">
          <input type="checkbox" checked={confirmDelete} onChange={(event) => setConfirmDelete(event.target.checked)} />
          I understand this permanently deletes the project graph.
        </label>
        <button className="button danger" type="button" disabled={!confirmDelete || pending} onClick={remove}>
          Delete project
        </button>
      </section>
    </section>
  )
}

export function ProjectSettingsPage() {
  const { projectId, project, error, setProject } = useProject()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [pending, setPending] = useState(false)

  useEffect(() => {
    if (project) {
      setName(project.name)
      setDescription(project.description ?? '')
    }
  }, [project])

  async function save(event: FormEvent) {
    event.preventDefault()
    setPending(true)
    setMessage(null)
    try {
      setProject(await api<Project>(`/api/projects/${projectId}`, {
        method: 'PATCH',
        body: JSON.stringify({ name, description }),
      }))
      window.dispatchEvent(new Event('changegraph:projects'))
      setMessage({ text: 'Project updated.', error: false })
    } catch (err) {
      setMessage({ text: err instanceof ApiError ? err.message : 'Update failed.', error: true })
    } finally {
      setPending(false)
    }
  }

  async function retryGraph() {
    setPending(true)
    try {
      const updated = await api<Project>(`/api/projects/${projectId}/graph`, { method: 'POST' })
      setProject(updated)
      setMessage({
        text: updated.graphStatus === 'READY' ? 'Graph is ready.' : 'Graph initialization did not succeed.',
        error: updated.graphStatus !== 'READY',
      })
    } catch (err) {
      setMessage({ text: err instanceof ApiError ? err.message : 'Graph retry failed.', error: true })
    } finally {
      setPending(false)
    }
  }

  async function remove() {
    setPending(true)
    try {
      await api<void>(`/api/projects/${projectId}?confirm=true`, { method: 'DELETE' })
      window.dispatchEvent(new Event('changegraph:projects'))
      navigate('/')
    } catch (err) {
      setMessage({ text: err instanceof ApiError ? err.message : 'Delete failed.', error: true })
      setPending(false)
    }
  }

  if (error) return <ErrorText error={error} />
  if (!project) return <p className="muted">Loading project…</p>

  return (
    <section className="stack">
      <div>
        <p className="kicker">Project</p>
        <h1>Edit project</h1>
      </div>
      <form className="card form" onSubmit={save}>
        {message?.error ? <ErrorText error={message.text} /> : message && <p className="banner">{message.text}</p>}
        <label>
          Name
          <input value={name} onChange={(event) => setName(event.target.value)} required maxLength={80} />
        </label>
        <label>
          Description, optional
          <textarea value={description} onChange={(event) => setDescription(event.target.value)} maxLength={500} />
        </label>
        <button className="button" type="submit" disabled={pending}>Save</button>
      </form>
      {project.repository && (
        <BranchSelect projectId={projectId} repository={project.repository} />
      )}
      {project.graphStatus !== 'READY' && (
        <button className="button secondary" type="button" onClick={retryGraph} disabled={pending}>
          Retry graph initialization
        </button>
      )}
      <section className="card danger-zone">
        <h2>Delete project</h2>
        <p>Deletion removes this project’s database rows and its own FalkorDB graph. It does not delete any other project’s graph.</p>
        <label className="check">
          <input type="checkbox" checked={confirmDelete} onChange={(event) => setConfirmDelete(event.target.checked)} />
          I understand this permanently deletes the project graph.
        </label>
        <button className="button danger" type="button" disabled={!confirmDelete || pending} onClick={remove}>
          Delete project
        </button>
      </section>
    </section>
  )
}

export function RepositoryPage() {
  const { projectId, project, error } = useProject()
  const location = useLocation()
  const initial = location.state as { linkError?: string; repositoryUrl?: string } | null
  const [url, setUrl] = useState(initial?.repositoryUrl ?? '')
  const [localError, setLocalError] = useState<string | null>(initial?.linkError ?? null)
  const [repository, setRepository] = useState<RepositorySummary | null>(null)
  const [pending, setPending] = useState(false)

  useEffect(() => {
    if (project?.repository) setRepository(project.repository)
  }, [project])

  async function connect(event: FormEvent) {
    event.preventDefault()
    setLocalError(null)
    const canonical = normalizeGitHubRepoUrl(url)
    if (!canonical) {
      setLocalError('Use an https://github.com/owner/repository URL. The server still verifies that the repository is public.')
      return
    }
    setPending(true)
    try {
      setRepository(await api<RepositorySummary>(`/api/projects/${projectId}/repository`, {
        method: 'POST',
        body: JSON.stringify({ repositoryUrl: canonical }),
      }))
    } catch (err) {
      setLocalError(err instanceof ApiError ? err.message : 'Could not connect the repository.')
    } finally {
      setPending(false)
    }
  }

  async function unlink() {
    setPending(true)
    try {
      await api<void>(`/api/projects/${projectId}/repository`, { method: 'DELETE' })
      setRepository(null)
    } catch (err) {
      setLocalError(err instanceof ApiError ? err.message : 'Could not remove the repository.')
    } finally {
      setPending(false)
    }
  }

  if (error) return <ErrorText error={error} />
  return (
    <section className="stack">
      <h1>Connect repository</h1>
      <p className="muted">Paste the repository. ChangeGraph checks that it is public before storing it.</p>
      <PublicRepoNotice />
      <ErrorText error={localError} />
      {repository ? (
        <article className="card">
          <h2>{repository.ownerName}/{repository.repositoryName}</h2>
          <dl className="repo-facts">
            <div>
              <dt>Selected branch</dt>
              <dd>{repository.selectedBranch}</dd>
            </div>
            <div>
              <dt>Default branch</dt>
              <dd>{repository.defaultBranch}</dd>
            </div>
            <div>
              <dt>Visibility</dt>
              <dd>{repository.visibility}</dd>
            </div>
            <div>
              <dt>GitHub id</dt>
              <dd>{repository.githubRepositoryId}</dd>
            </div>
          </dl>
          <p className="hint">Later commits on the selected branch are reviewed. History already on the branch is left alone.</p>
          <details className="quiet">
            <summary>Webhook, when GitHub can reach this server</summary>
            <p className="hint">Localhost cannot receive webhooks. On a public address, subscribe push and pull request events to POST /api/github/webhooks using GITHUB_WEBHOOK_SECRET.</p>
          </details>
          <button className="button secondary" type="button" onClick={unlink} disabled={pending}>Remove repository</button>
        </article>
      ) : null}
      {repository && (
        <BranchSelect
          projectId={projectId}
          repository={repository}
          onChange={setRepository}
        />
      )}
      {!repository && (
        <form className="card form" onSubmit={connect}>
          <label>
            GitHub repository URL
            <input value={url} onChange={(event) => setUrl(event.target.value)} placeholder="https://github.com/owner/repository" required />
          </label>
          <p className="hint">github.com/owner/repository is enough. No access token is requested.</p>
          <button className="button" type="submit" disabled={pending}>{pending ? 'Checking…' : 'Connect public repository'}</button>
        </form>
      )}
    </section>
  )
}

export function GraphPage() {
  const { projectId, error } = useProject()
  const [type, setType] = useState('')
  const [view, setView] = useState<GraphView | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [localError, setLocalError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    const query = type ? `?type=${encodeURIComponent(type)}` : ''
    api<GraphView>(`/api/projects/${projectId}/graph${query}`)
      .then((value) => {
        if (!cancelled) setView(value)
      })
      .catch((err: unknown) => {
        if (!cancelled) setLocalError(err instanceof ApiError ? err.message : 'Could not load the graph.')
      })
    return () => {
      cancelled = true
    }
  }, [projectId, type])

  const selectedNode = view?.nodes.find((node) => node.id === selected)
  const connections = view?.edges.filter((edge) => edge.source === selected || edge.target === selected) ?? []
  if (error) return <ErrorText error={error} />

  return (
    <section className="stack">
      <h1>Code graph</h1>
      <p className="muted">These nodes are parsed from source. The analysis page infers impact from them.</p>
      <label className="filter-select">
        Node type
        <select value={type} onChange={(event) => setType(event.target.value)}>
          <option value="">All</option>
          {['File', 'Function', 'Test', 'Service', 'Commit', 'Incident', 'Evidence'].map((item) => (
            <option key={item} value={item}>{item}</option>
          ))}
        </select>
      </label>
      <ErrorText error={localError} />
      {!view && !localError && <p className="muted">Loading graph…</p>}
      {view?.truncated && <p className="banner">The graph view is limited. It may not include every node.</p>}
      {view && view.nodes.length === 0 && <p>No graph nodes yet. Ingest a public commit first.</p>}
      <div className="graph-layout">
        <div className="graph-canvas">
          {view?.nodes.slice(0, 40).map((node) => (
            <button
              key={node.id}
              type="button"
              className={node.id === selected ? 'graph-node is-selected' : 'graph-node'}
              title={node.label}
              aria-pressed={node.id === selected}
              onClick={() => setSelected(node.id)}
            >
              <span>{node.type}</span>
              <strong>{node.label}</strong>
            </button>
          ))}
        </div>
        <aside className="card graph-inspector">
          <h2>Selected node</h2>
          {selectedNode ? (
            <>
              <p className="graph-inspector-label" title={selectedNode.label}>{selectedNode.label}</p>
              <p className="muted">{selectedNode.type}</p>
              <dl className="graph-props">
                {Object.entries(selectedNode.properties).map(([key, value]) => (
                  <div key={key}>
                    <dt>{key}</dt>
                    <dd>{String(value)}</dd>
                  </div>
                ))}
              </dl>
              <h3>Connections</h3>
              {connections.length === 0 && <p className="muted">No connections in this view.</p>}
              <ul className="graph-links">
                {connections.map((edge, index) => (
                  <li key={`${edge.type}-${index}`} title={`${edge.source} ${edge.type} ${edge.target}`}>
                    <span>{edge.type}</span>
                    {edge.source === selected ? edge.target : edge.source}
                  </li>
                ))}
              </ul>
            </>
          ) : (
            <p>Select a node to inspect its stored properties.</p>
          )}
        </aside>
      </div>
      <div className="table-wrap">
        <table>
          <caption>Graph nodes</caption>
          <thead>
            <tr><th>Type</th><th>Label</th><th>Id</th></tr>
          </thead>
          <tbody>
            {view && view.nodes.length === 0 && (
              <tr><td className="muted" colSpan={3}>No records available.</td></tr>
            )}
            {view?.nodes.map((node) => (
              <tr key={node.id}>
                <td>{node.type}</td>
                <td className="clip" title={node.label}><button type="button" className="linkish" onClick={() => setSelected(node.id)}>{node.label}</button></td>
                <td className="mono clip" title={node.id}>{node.id}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

export function IngestionPage() {
  const { projectId, error } = useProject()
  const [commits, setCommits] = useState<CommitSummary[]>([])
  const [jobs, setJobs] = useState<IngestionJob[]>([])
  const [sha, setSha] = useState('')
  const [files, setFiles] = useState<CommitDetail | null>(null)
  const [localError, setLocalError] = useState<string | null>(null)
  const [activeJob, setActiveJob] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const [polling, setPolling] = useState(false)
  const [lastPolledAt, setLastPolledAt] = useState<string | null>(null)
  const [pollIntervalSeconds, setPollIntervalSeconds] = useState(30 * 60)

  function applyPoll(poll: CommitPoll) {
    setCommits(poll.commits)
    setLastPolledAt(poll.lastPolledAt)
    setPollIntervalSeconds(poll.pollIntervalSeconds)
  }

  async function load() {
    const [poll, jobPage] = await Promise.all([
      api<CommitPoll>(`/api/projects/${projectId}/github-poll`),
      api<Page<IngestionJob>>(`/api/projects/${projectId}/ingestions`),
    ])
    applyPoll(poll)
    setJobs(jobPage.items)
  }

  useEffect(() => {
    let cancelled = false
    load().catch((err: unknown) => {
      if (!cancelled) setLocalError(err instanceof ApiError ? err.message : 'Could not load ingestion history.')
    })
    return () => {
      cancelled = true
    }
  }, [projectId])

  useEffect(() => {
    const timer = window.setInterval(() => {
      api<CommitPoll>(`/api/projects/${projectId}/github-poll`)
        .then(applyPoll)
        .catch(() => {})
    }, pollIntervalSeconds * 1000)
    return () => window.clearInterval(timer)
  }, [projectId, pollIntervalSeconds])

  useEffect(() => {
    if (!activeJob) return
    let attempts = 0
    const timer = window.setInterval(() => {
      attempts += 1
      api<IngestionJob>(`/api/projects/${projectId}/ingestions/${activeJob}`)
        .then((job) => {
          setJobs((current) => [job, ...current.filter((item) => item.id !== job.id)])
          if (job.status === 'SUCCEEDED' || job.status === 'FAILED' || job.status === 'CANCELLED' || attempts > 30) {
            window.clearInterval(timer)
            setActiveJob(null)
          }
        })
        .catch(() => {
          window.clearInterval(timer)
          setActiveJob(null)
        })
    }, 2000)
    return () => window.clearInterval(timer)
  }, [activeJob, projectId])

  async function start(event: FormEvent) {
    event.preventDefault()
    setPending(true)
    setLocalError(null)
    try {
      const job = await api<IngestionJob>(`/api/projects/${projectId}/ingestions`, {
        method: 'POST',
        body: JSON.stringify(sha ? { commitSha: sha } : {}),
      })
      setActiveJob(job.id)
      setJobs((current) => [job, ...current])
    } catch (err) {
      setLocalError(err instanceof ApiError ? err.message : 'Could not start ingestion.')
    } finally {
      setPending(false)
    }
  }

  async function pollNow() {
    setPolling(true)
    setLocalError(null)
    try {
      applyPoll(await api<CommitPoll>(`/api/projects/${projectId}/github-poll`, { method: 'POST' }))
    } catch (err) {
      setLocalError(err instanceof ApiError ? err.message : 'Could not poll GitHub.')
    } finally {
      setPolling(false)
    }
  }

  async function showFiles(commitSha: string) {
    setSha(commitSha)
    try {
      setFiles(await api<CommitDetail>(`/api/projects/${projectId}/commits/${commitSha}`))
    } catch (err) {
      setLocalError(err instanceof ApiError ? err.message : 'Could not load the commit.')
    }
  }

  if (error) return <ErrorText error={error} />
  return (
    <section className="stack">
      <div className="row">
        <div>
          <h1>Ingestion</h1>
          <p className="muted">
            {lastPolledAt
              ? `Last GitHub check ${new Date(lastPolledAt).toLocaleString()}. `
              : 'GitHub has not been checked yet. '}
            Automatic check every {Math.max(1, Math.round(pollIntervalSeconds / 60))} minutes. Leave the commit empty to use the latest on the selected branch.
          </p>
        </div>
        <button className="button secondary" type="button" onClick={pollNow} disabled={polling}>
          {polling ? 'Checking…' : 'Check GitHub'}
        </button>
      </div>
      <ErrorText error={localError} />
      <form className="card form" onSubmit={start}>
        <label>
          Commit SHA
          <input value={sha} onChange={(event) => setSha(event.target.value)} placeholder="Leave empty to use the latest commit on the selected branch" />
        </label>
        <button className="button" type="submit" disabled={pending || activeJob !== null}>
          {activeJob ? 'Ingestion running…' : 'Start ingestion'}
        </button>
      </form>
      <div className="table-wrap">
        <table className="commits">
          <caption>Recent public commits</caption>
          <thead><tr><th>SHA</th><th>Message</th><th></th></tr></thead>
          <tbody>
            {commits.length === 0 && (
              <tr><td colSpan={3}>No commits polled yet.</td></tr>
            )}
            {commits.map((commit) => (
              <tr key={commit.sha}>
                <td className="mono">{commit.sha.slice(0, 7)}</td>
                <td>{commit.message}</td>
                <td><button type="button" className="button secondary" onClick={() => showFiles(commit.sha)}>Use</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {files && (
        <article className="card">
          <h2>Changed files</h2>
          <ul>{files.files.map((file) => <li key={file.filename}>{file.status}: {file.filename}</li>)}</ul>
        </article>
      )}
      <div className="table-wrap">
        <table>
          <caption>Ingestion jobs</caption>
          <thead><tr><th>Status</th><th>Commit</th><th>Progress</th><th>Error</th></tr></thead>
          <tbody>
            {jobs.map((job) => (
              <tr key={job.id}>
                <td><StatusBadge value={job.status} /></td>
                <td className="mono">{job.requestedCommitSha?.slice(0, 7) ?? 'latest'}</td>
                <td>{job.progressMessage}</td>
                <td>{job.errorCode ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

export function AnalysisPage() {
  const { projectId, error } = useProject()
  const [mode, setMode] = useState<'commit' | 'pull'>('commit')
  const [sha, setSha] = useState('')
  const [pull, setPull] = useState('')
  const [commits, setCommits] = useState<CommitSummary[]>([])
  const [pulls, setPulls] = useState<PullRequest[]>([])
  const [report, setReport] = useState<AnalysisReport | null>(null)
  const [agentReport, setAgentReport] = useState<AgentReport | null>(null)
  const [history, setHistory] = useState<AnalysisSummary[] | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const detailRef = useRef<HTMLDivElement>(null)
  const [localError, setLocalError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  function loadHistory() {
    return api<Page<AnalysisSummary>>(`/api/projects/${projectId}/analysis?page=0&size=20`)
      .then((page) => setHistory(page.items))
  }

  useEffect(() => {
    let cancelled = false
    Promise.all([
      api<CommitSummary[]>(`/api/projects/${projectId}/commits`).catch(() => []),
      api<PullRequest[]>(`/api/projects/${projectId}/pulls`).catch(() => []),
      api<Page<AnalysisSummary>>(`/api/projects/${projectId}/analysis?page=0&size=20`).catch(() => null),
    ]).then(([commitList, pullList, saved]) => {
      if (cancelled) return
      const items = saved ? saved.items : []
      setCommits(commitList)
      setPulls(pullList)
      setHistory(items)
      const latest = items[0]
      if (latest?.kind !== 'AGENT') return
      api<AgentReport>(`/api/projects/${projectId}/agent/investigations/${latest.id}`)
        .then((stored) => {
          if (!cancelled) {
            setSelectedId(latest.id)
            setReport(null)
            setAgentReport(stored)
          }
        })
        .catch(() => undefined)
    })
    return () => {
      cancelled = true
    }
  }, [projectId])

  function targetBody() {
    return mode === 'commit' ? { commitSha: sha } : { pullRequestNumber: Number(pull) }
  }

  async function run(event: FormEvent) {
    event.preventDefault()
    setPending(true)
    setLocalError(null)
    try {
      setAgentReport(null)
      setReport(await api<AnalysisReport>(`/api/projects/${projectId}/analysis`, {
        method: 'POST',
        body: JSON.stringify(targetBody()),
      }))
      await loadHistory()
    } catch (err) {
      setLocalError(err instanceof ApiError ? err.message : 'Analysis failed.')
    } finally {
      setPending(false)
    }
  }

  async function investigate() {
    setPending(true)
    setLocalError(null)
    try {
      setReport(null)
      setAgentReport(await api<AgentReport>(`/api/projects/${projectId}/agent/investigations`, {
        method: 'POST',
        body: JSON.stringify(targetBody()),
      }))
      await loadHistory()
    } catch (err) {
      setLocalError(err instanceof ApiError ? err.message : 'The agent investigation failed.')
    } finally {
      setPending(false)
    }
  }

  async function openSaved(item: AnalysisSummary) {
    setPending(true)
    setLocalError(null)
    setSelectedId(item.id)
    try {
      if (item.kind === 'AGENT') {
        setReport(null)
        setAgentReport(await api<AgentReport>(`/api/projects/${projectId}/agent/investigations/${item.id}`))
      } else {
        setAgentReport(null)
        setReport(await api<AnalysisReport>(`/api/projects/${projectId}/analysis/${item.id}`))
      }
    } catch (err) {
      setLocalError(err instanceof ApiError ? err.message : 'Could not open this analysis.')
    } finally {
      setPending(false)
    }
  }

  useEffect(() => {
    if (report || agentReport) {
      detailRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
    }
  }, [report, agentReport])

  function openSavedFromKey(event: KeyboardEvent<HTMLTableRowElement>, item: AnalysisSummary) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      void openSaved(item)
    }
  }

  if (error) return <ErrorText error={error} />
  const reportView = (
    <div ref={detailRef}>
      {report && <AnalysisView report={report} />}
      {agentReport && <AgentReportView report={agentReport} />}
    </div>
  )
  return (
    <section className="stack">
      <h1>Impact analysis</h1>
      <p className="muted">Read the score, why it landed there, and which tests to run. The score says how far a change can reach. It does not claim a function is broken.</p>
      <ErrorText error={localError} />
      {(report || agentReport) && reportView}
      <form className="card form" onSubmit={run}>
        <fieldset>
          <legend>Choose one target</legend>
          <label className="check"><input type="radio" checked={mode === 'commit'} onChange={() => setMode('commit')} /> Commit</label>
          <label className="check"><input type="radio" checked={mode === 'pull'} onChange={() => setMode('pull')} /> Pull request</label>
        </fieldset>
        {mode === 'commit' ? (
          <>
            <label>
              Commit SHA
              <input value={sha} onChange={(event) => setSha(event.target.value)} required />
            </label>
            <select value={sha} onChange={(event) => setSha(event.target.value)} aria-label="Recent commits">
              <option value="">Recent public commits</option>
              {commits.map((commit) => <option key={commit.sha} value={commit.sha}>{commit.sha.slice(0, 7)} {commit.message}</option>)}
            </select>
          </>
        ) : (
          <>
            <label>
              Pull request number
              <input value={pull} onChange={(event) => setPull(event.target.value)} required />
            </label>
            <select value={pull} onChange={(event) => setPull(event.target.value)} aria-label="Open pull requests">
              <option value="">Open public pull requests</option>
              {pulls.map((item) => (
                <option key={item.number} value={item.number}>
                  #{item.number} {item.title}{item.headRef && item.baseRef ? ` (${item.headRef} → ${item.baseRef})` : ''}
                </option>
              ))}
            </select>
          </>
        )}
        <p className="hint">Run analysis reads the graph. Investigate asks the agent to plan the same question, and falls back to the graph if the model cannot.</p>
        <div className="actions">
          <button className="button" type="submit" disabled={pending}>{pending ? 'Analyzing…' : 'Run analysis'}</button>
          <button className="button secondary" type="button" onClick={investigate} disabled={pending}>Investigate with agent</button>
        </div>
      </form>
      <div className="table-wrap">
        <table>
          <caption>Saved analyses</caption>
          <thead>
            <tr><th>When</th><th>Kind</th><th>Commit</th><th>Risk</th><th>Status</th></tr>
          </thead>
          <tbody>
            {history && history.length === 0 && (
              <tr><td className="muted" colSpan={5}>No records available.</td></tr>
            )}
            {history?.map((item) => (
              <tr
                key={item.id}
                className={item.id === selectedId ? 'is-selected' : undefined}
                role="button"
                tabIndex={0}
                aria-label={`Open ${item.kind === 'AGENT' ? 'agent' : 'graph'} analysis ${item.commitSha?.slice(0, 7) ?? ''} ${item.status}`}
                onClick={() => openSaved(item)}
                onKeyDown={(event) => openSavedFromKey(event, item)}
              >
                <td>{new Date(item.createdAt).toLocaleString()}</td>
                <td>{item.kind === 'AGENT' ? 'Agent' : 'Graph'}</td>
                <td className="mono">{item.commitSha ? item.commitSha.slice(0, 7) : item.pullRequestNumber ? `#${item.pullRequestNumber}` : ''}</td>
                <td>{item.riskLevel ?? ''}{item.riskScore != null ? ` ${item.riskScore}` : ''}</td>
                <td><StatusBadge value={item.status} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {!(report || agentReport) && reportView}
    </section>
  )
}

function agentStatusMeaning(status: string) {
  if (status === 'COMPLETED') return 'The language model chose the investigation steps and finished. The score still comes from the graph rules.'
  if (status === 'DETERMINISTIC_FALLBACK') return 'The language model did not produce a plan. The same graph tools ran in a fixed order, and the score comes from those rules.'
  if (status === 'BUDGET_EXHAUSTED') return 'The language model was running, then stopped because the investigation reached its step limit. The report uses only the graph evidence collected before that stop.'
  return 'This status describes how the investigation was produced.'
}

function scoreWidth(score: number, max: number) {
  if (!Number.isFinite(score) || !Number.isFinite(max) || max <= 0) return 0
  return Math.max(0, Math.min(100, (score / max) * 100))
}

function mergeClose(count: number) {
  if (count === 0) return 'No test was linked to this change. Use the impact above as a place to look.'
  if (count === 1) return 'Run the test above before you merge.'
  return `Run the ${count} tests above before you merge.`
}

function TestCallout({ tests }: { tests: { name: string; reason: string }[] }) {
  return (
    <article className="card test-callout">
      <h2>Tests to run</h2>
      {tests.length === 0 && <p>No test was linked to this change.</p>}
      {tests.length > 0 && (
        <ul className="test-list">
          {tests.map((test) => (
            <li key={`${test.name}-${test.reason}`}>
              <strong>{test.name}</strong>
              <span>{test.reason}</span>
            </li>
          ))}
        </ul>
      )}
    </article>
  )
}

export function AnalysisView({ report }: { report: AnalysisReport }) {
  const tests = report.recommendedTests.map((test) => ({
    name: `${test.direct ? 'Direct' : 'Additional'}: ${test.name}`,
    reason: test.reason,
  }))
  return (
    <section className="stack" aria-live="polite">
      <article className="card risk-plate">
        <p className="kicker">Risk</p>
        <p className="risk-score">{report.riskScore}<span> / {report.riskScoreMax}</span></p>
        <h2><StatusBadge value={report.riskLevel} /></h2>
        <div className="score-meter" role="img" aria-label={`Risk score ${report.riskScore} of ${report.riskScoreMax}`}>
          <span className={`tone-${report.riskLevel.toLowerCase()}`} style={{ width: `${scoreWidth(report.riskScore, report.riskScoreMax)}%` }} />
        </div>
        <p className="muted">{report.riskScoreInterpretation}</p>
        <p className="mono">Commit {report.commitSha}</p>
        <ul>{report.riskReasons.map((reason) => <li key={reason}>{reason}</li>)}</ul>
      </article>
      <TestCallout tests={tests} />
      <section className="stack">
        <h2>What this change reaches</h2>
        <DataTable title="Changed files" headers={['Path', 'Change']} rows={report.changedFiles.map((file) => [file.path, file.changeType])} />
        <DataTable title="Changed functions" headers={['Signature', 'Change']} rows={report.changedFunctions.map((fn) => [fn.signature, fn.changeType])} />
        <DataTable title="Potentially impacted functions" headers={['Signature', 'Direction', 'Depth']} rows={report.impactedFunctions.map((fn) => [fn.signature, fn.direction, String(fn.depth)])} />
        <DataTable title="Potentially impacted services" headers={['Service']} rows={report.impactedServices.map((service) => [service])} />
        <DataTable title="Graph paths" headers={['Explanation']} rows={report.paths.map((path) => [path.explanation])} />
      </section>
      <article className="card">
        <h2>Related incidents</h2>
        {report.relatedIncidents.length === 0 && <p>No related historical incidents are linked to this change.</p>}
        {report.relatedIncidents.map((incident) => (
          <section key={incident.id}>
            <h3>
              {incident.title} {incident.synthetic && <StatusBadge value="SYNTHETIC" />}
            </h3>
            <p>{incident.summary}</p>
            <ul>
              {incident.evidence.map((item) => (
                <li key={item.id}>
                  {item.evidenceType} · {item.relationship} · {item.verificationStatus} · {item.confidence}
                  {item.synthetic ? ' · synthetic' : ''}: {item.summary}
                </li>
              ))}
            </ul>
          </section>
        ))}
      </article>
      <article className="card">
        <h2>Uncertainties</h2>
        <ul>{report.uncertainties.map((item) => <li key={item}>{item}</li>)}</ul>
      </article>
      <article className="card merge-close">
        <h2>Before you merge</h2>
        <p>{mergeClose(tests.length)}</p>
      </article>
    </section>
  )
}

export function AgentReportView({ report }: { report: AgentReport }) {
  const tests = report.recommendedTests.map((test) => ({
    name: `${test.direct ? 'Direct' : 'Inferred'}: ${test.name}`,
    reason: test.reason,
  }))
  return (
    <section className="stack" aria-live="polite">
      <article className="card risk-plate">
        <p className="kicker">Agent</p>
        <p className="risk-score">{report.riskScore}<span> / {report.riskScoreMax}</span></p>
        <div className="badge-row">
          <StatusBadge value={report.riskLevel} />
          <StatusBadge value={report.agentStatus} />
        </div>
        <p className="muted">{agentStatusMeaning(report.agentStatus)}</p>
        <div className="score-meter" role="img" aria-label={`Risk score ${report.riskScore} of ${report.riskScoreMax}`}>
          <span className={`tone-${report.riskLevel.toLowerCase()}`} style={{ width: `${scoreWidth(report.riskScore, report.riskScoreMax)}%` }} />
        </div>
        <p className="muted">{report.riskScoreInterpretation}</p>
        <p>{report.summary}</p>
      </article>
      <TestCallout tests={tests} />
      <article className="card">
        <h2>Risk factors</h2>
        {report.riskFactors.length === 0 && <p className="muted">No risk factors were recorded.</p>}
        {report.riskFactors.length > 0 && (
          <ul className="factor-list">
            {report.riskFactors.map((factor) => (
              <li key={`${factor.kind}-${factor.statement}`}>
                <StatusBadge value={factor.kind} />
                <span>{factor.statement}</span>
              </li>
            ))}
          </ul>
        )}
      </article>
      <section className="stack">
        <h2>What this change reaches</h2>
        <DataTable title="Changed components" headers={['Name', 'Kind', 'Detail']} rows={report.changedComponents.map((item) => [item.name, item.kind, item.detail])} />
        <DataTable title="Impacted components" headers={['Name', 'Direction', 'Detail']} rows={report.impactedComponents.map((item) => [item.name, item.kind, item.detail])} />
        <DataTable title="Dependency paths" headers={['Path']} rows={report.impactPaths.map((path) => [path.explanation])} />
      </section>
      <article className="card">
        <h2>Related incidents</h2>
        {report.relatedIncidents.length === 0 && <p>No related historical incidents are linked to this change.</p>}
        {report.relatedIncidents.map((incident) => (
          <section key={incident.id}>
            <h3>{incident.title} {incident.synthetic && <StatusBadge value="SYNTHETIC" />}</h3>
            <p>{incident.summary}</p>
          </section>
        ))}
      </article>
      <article className="card">
        <h2>Evidence</h2>
        {report.evidence.length === 0 && <p className="muted">No incident evidence was retrieved.</p>}
        {report.evidence.length > 0 && (
          <ul>
            {report.evidence.map((item) => (
              <li key={item.id}>
                {item.evidenceType} · {item.relationship} · {item.verificationStatus}
                {item.synthetic ? ' · synthetic' : ''}: {item.summary}
              </li>
            ))}
          </ul>
        )}
      </article>
      <article className="card">
        <h2>Investigation</h2>
        <ol className="timeline">
          {report.investigationSummary.map((step, index) => (
            <li key={`${step.tool}-${index}`}>
              <strong>{step.tool}</strong> {step.summary}
              {step.query
                ? <pre className="query">{step.query}</pre>
                : <p className="muted">No FalkorDB query was stored for this step.</p>}
            </li>
          ))}
        </ol>
      </article>
      <article className="card">
        <h2>Uncertainties</h2>
        {report.uncertainties.length === 0 && <p className="muted">No uncertainties were recorded.</p>}
        {report.uncertainties.length > 0 && (
          <ul>{report.uncertainties.map((item) => <li key={item}>{item}</li>)}</ul>
        )}
      </article>
      <article className="card merge-close">
        <h2>Before you merge</h2>
        <p>{mergeClose(tests.length)}</p>
      </article>
    </section>
  )
}

function DataTable({ title, headers, rows }: { title: string; headers: string[]; rows: string[][] }) {
  return (
    <div className="table-wrap">
      <table>
        <caption>{title}</caption>
        <thead><tr>{headers.map((header) => <th key={header}>{header}</th>)}</tr></thead>
        <tbody>
          {rows.length === 0 && <tr><td className="muted" colSpan={headers.length}>No records available.</td></tr>}
          {rows.map((row, index) => <tr key={`${title}-${index}`}>{row.map((cell, cellIndex) => <td key={cellIndex} title={cell}>{cell}</td>)}</tr>)}
        </tbody>
      </table>
    </div>
  )
}
