import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { GraphNode, GraphView, IncidentDetail, IncidentEvidence, IncidentSummary, SyntheticSeed } from '../api/types'
import { EmptyState, ErrorText, StatusBadge } from '../components/ui'
import { useProject } from './ProjectPages'

const EVIDENCE_TYPES = ['INCIDENT_REPORT', 'HUMAN_REVIEWED_FINDING', 'FIX_COMMIT', 'REGRESSION_TEST'] as const
const RELATIONSHIPS = ['REPORTS_ON', 'SUPPORTS_CAUSE', 'SUPPORTS_RESOLUTION', 'VALIDATES_FIX'] as const
const VERIFICATION = ['VERIFIED', 'UNREVIEWED', 'DISPUTED'] as const
const CONFIDENCE = ['LOW', 'MEDIUM', 'HIGH'] as const

function formatLabel(value: string) {
  return value.replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, (char) => char.toUpperCase())
}

function formatUpdatedAt(value: string) {
  const time = Date.parse(value)
  if (!Number.isFinite(time) || time <= 0) return null
  return new Date(time).toLocaleString()
}

export function IncidentsPage() {
  const { projectId, project, error } = useProject()
  const [items, setItems] = useState<IncidentSummary[] | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [detail, setDetail] = useState<IncidentDetail | null>(null)
  const [functions, setFunctions] = useState<GraphNode[]>([])
  const [localError, setLocalError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const [mode, setMode] = useState<'view' | 'create'>('view')
  const [showClassification, setShowClassification] = useState(false)

  const [title, setTitle] = useState('')
  const [summary, setSummary] = useState('')
  const [incidentId, setIncidentId] = useState('')

  const [evidenceType, setEvidenceType] = useState<(typeof EVIDENCE_TYPES)[number]>('INCIDENT_REPORT')
  const [relationship, setRelationship] = useState<(typeof RELATIONSHIPS)[number]>('REPORTS_ON')
  const [verificationStatus, setVerificationStatus] = useState<(typeof VERIFICATION)[number]>('VERIFIED')
  const [confidence, setConfidence] = useState<(typeof CONFIDENCE)[number]>('HIGH')
  const [evidenceSummary, setEvidenceSummary] = useState('')
  const [functionId, setFunctionId] = useState('')

  function loadList() {
    return api<IncidentSummary[]>(`/api/projects/${projectId}/incidents`).then((list) => {
      setItems(list)
      return list
    })
  }

  function loadDetail(id: string) {
    return api<IncidentDetail>(`/api/projects/${projectId}/incidents/${encodeURIComponent(id)}`).then((value) => {
      setDetail(value)
      setSelectedId(id)
      setMode('view')
    })
  }

  function openCreate() {
    setMode('create')
    setSelectedId(null)
    setDetail(null)
    setLocalError(null)
  }

  function selectIncident(id: string) {
    setMode('view')
    setSelectedId(id)
  }

  useEffect(() => {
    let cancelled = false
    Promise.all([
      loadList(),
      api<GraphView>(`/api/projects/${projectId}/graph?type=Function&limit=200`).catch(() => ({ nodes: [], edges: [], truncated: false })),
    ])
      .then(([list, graph]) => {
        if (cancelled) return
        setFunctions(graph.nodes)
        if (list.length > 0) {
          setSelectedId((current) => current ?? list[0].id)
          setMode((current) => (current === 'create' ? 'create' : 'view'))
        } else {
          setMode('create')
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) setLocalError(err instanceof ApiError ? err.message : 'Could not load incidents.')
      })
    return () => {
      cancelled = true
    }
  }, [projectId])

  useEffect(() => {
    if (mode !== 'view' || !selectedId) {
      if (mode !== 'view') setDetail(null)
      return
    }
    let cancelled = false
    loadDetail(selectedId).catch((err: unknown) => {
      if (!cancelled) setLocalError(err instanceof ApiError ? err.message : 'Could not load this incident.')
    })
    return () => {
      cancelled = true
    }
  }, [projectId, selectedId, mode])

  async function createIncident(event: FormEvent) {
    event.preventDefault()
    setPending(true)
    setLocalError(null)
    setMessage(null)
    try {
      const body: { title: string; summary: string; id?: string } = { title, summary }
      if (incidentId.trim()) body.id = incidentId.trim()
      const created = await api<IncidentDetail>(`/api/projects/${projectId}/incidents`, {
        method: 'POST',
        body: JSON.stringify(body),
      })
      await loadList()
      setTitle('')
      setSummary('')
      setIncidentId('')
      setSelectedId(created.id)
      setMode('view')
      setMessage('Incident saved. Link a function when you add evidence.')
    } catch (err) {
      setLocalError(err instanceof ApiError ? err.message : 'Could not create the incident.')
    } finally {
      setPending(false)
    }
  }

  async function addEvidence(event: FormEvent) {
    event.preventDefault()
    if (!selectedId) return
    setPending(true)
    setLocalError(null)
    setMessage(null)
    try {
      const body: Record<string, string> = {
        evidenceType,
        relationship,
        verificationStatus,
        confidence,
        summary: evidenceSummary,
      }
      if (functionId) body.functionId = functionId
      await api<IncidentDetail>(`/api/projects/${projectId}/incidents/${encodeURIComponent(selectedId)}/evidence`, {
        method: 'POST',
        body: JSON.stringify(body),
      })
      await Promise.all([loadList(), loadDetail(selectedId)])
      setEvidenceSummary('')
      setMessage('Evidence attached.')
    } catch (err) {
      setLocalError(err instanceof ApiError ? err.message : 'Could not add evidence.')
    } finally {
      setPending(false)
    }
  }

  async function seedSynthetic() {
    setPending(true)
    setLocalError(null)
    setMessage(null)
    try {
      const seed = await api<SyntheticSeed>(`/api/projects/${projectId}/synthetic-incidents`, { method: 'POST' })
      const list = await loadList()
      setMessage(seed.message)
      if (list[0]) {
        setSelectedId(list[0].id)
        setMode('view')
      }
    } catch (err) {
      setLocalError(err instanceof ApiError ? err.message : 'Could not load demo examples.')
    } finally {
      setPending(false)
    }
  }

  async function removeIncident() {
    if (!detail || !window.confirm(`Delete "${detail.title}" and its evidence from this project graph?`)) return
    setPending(true)
    setLocalError(null)
    try {
      await api<void>(`/api/projects/${projectId}/incidents/${encodeURIComponent(detail.id)}`, { method: 'DELETE' })
      const list = await loadList()
      setSelectedId(list[0]?.id ?? null)
      setMode(list[0] ? 'view' : 'create')
      setMessage('Incident deleted.')
    } catch (err) {
      setLocalError(err instanceof ApiError ? err.message : 'Could not delete the incident.')
    } finally {
      setPending(false)
    }
  }

  async function removeEvidence(evidenceId: string) {
    if (!detail || !window.confirm('Remove this evidence from the graph?')) return
    setPending(true)
    setLocalError(null)
    try {
      const updated = await api<IncidentDetail>(
        `/api/projects/${projectId}/incidents/${encodeURIComponent(detail.id)}/evidence/${encodeURIComponent(evidenceId)}`,
        { method: 'DELETE' },
      )
      setDetail(updated)
      await loadList()
      setMessage('Evidence removed.')
    } catch (err) {
      setLocalError(err instanceof ApiError ? err.message : 'Could not remove evidence.')
    } finally {
      setPending(false)
    }
  }

  if (error) return <ErrorText error={error} />

  const graphReady = project?.graphStatus === 'READY'
  const updatedLabel = detail ? formatUpdatedAt(detail.updatedAt) : null

  return (
    <section className="stack incidents-page">
      <div className="row">
        <div>
          <p className="kicker">Historical context</p>
          <h1>Incidents</h1>
          <p className="muted">Postmortems live on the project graph. Impact analysis picks them up when evidence points at a changed function.</p>
        </div>
      </div>
      {!graphReady && (
        <p className="banner">Initialize the project graph before adding incidents. Open Edit and retry graph setup if needed.</p>
      )}
      <ErrorText error={localError} />
      {message && <p className="banner banner-ok">{message}</p>}

      {items === null && !localError && <div className="skeleton" role="status">Loading incidents…</div>}

      {items !== null && graphReady && (
        <div className="activity-layout incident-layout">
          <aside className="table-wrap workflow-index incident-index">
            <div className="incident-index-head">
              <h2>Incidents</h2>
              <button className="button" type="button" disabled={pending} onClick={openCreate}>
                New
              </button>
            </div>
            {items.length === 0 && <p className="nav-note">None stored yet.</p>}
            <ul className="workflow-list">
              {items.map((item) => (
                <li key={item.id}>
                  <button
                    type="button"
                    className={item.id === selectedId && mode === 'view' ? 'incident-row is-selected' : 'incident-row'}
                    aria-current={item.id === selectedId && mode === 'view' ? 'true' : undefined}
                    onClick={() => selectIncident(item.id)}
                  >
                    <span className="incident-row-title">{item.title}</span>
                    <span className="incident-row-meta">
                      {item.evidenceCount === 1 ? '1 evidence item' : `${item.evidenceCount} evidence items`}
                      {item.synthetic && <> · <StatusBadge value="SYNTHETIC" /></>}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </aside>

          <div className="incident-panel stack">
            {mode === 'create' && (
              <form className="card form incident-create" onSubmit={createIncident}>
                <h2>New incident</h2>
                <p className="muted">Start with what happened. You can attach evidence and link a function in the next step.</p>
                <label>
                  Title
                  <input value={title} onChange={(event) => setTitle(event.target.value)} required maxLength={200} autoFocus />
                </label>
                <label>
                  Summary
                  <textarea value={summary} onChange={(event) => setSummary(event.target.value)} required maxLength={2000} />
                </label>
                <details className="quiet">
                  <summary>Stable id, optional</summary>
                  <label>
                    Id
                    <input value={incidentId} onChange={(event) => setIncidentId(event.target.value)} placeholder="Leave empty to generate one" maxLength={120} />
                  </label>
                </details>
                <div className="actions">
                  <button className="button" type="submit" disabled={pending}>{pending ? 'Saving…' : 'Save incident'}</button>
                  {items.length > 0 && (
                    <button className="button secondary" type="button" onClick={() => setMode('view')}>Cancel</button>
                  )}
                </div>
              </form>
            )}

            {mode === 'view' && detail && selectedId === detail.id && (
              <>
                <article className="card incident-head">
                  <div className="incident-head-top">
                    <div>
                      <div className="badge-row">
                        {detail.synthetic && <StatusBadge value="SYNTHETIC" />}
                        <span className="muted">{detail.sourceType}</span>
                      </div>
                      <h2>{detail.title}</h2>
                    </div>
                    <button className="button danger" type="button" disabled={pending} onClick={removeIncident}>Delete</button>
                  </div>
                  <p>{detail.summary}</p>
                  <ol className="incident-steps" aria-label="How analysis uses incidents">
                    <li className="is-done">Postmortem saved</li>
                    <li className={detail.evidence.some((item) => item.functionId) ? 'is-done' : 'is-current'}>
                      Link evidence to a function
                    </li>
                  </ol>
                  <p className="hint">
                    {updatedLabel && <>Last updated {updatedLabel}. </>}
                    Choose a function from the graph when adding evidence so analysis can relate this incident to a change.{' '}
                    <Link to={`/projects/${projectId}/graph`}>Open graph</Link>
                  </p>
                </article>

                <section className="stack">
                  <h3>Evidence</h3>
                  {detail.evidence.length === 0 && (
                    <p className="muted">No evidence yet. Add what you know below — at least one item should point at a parsed function.</p>
                  )}
                  {detail.evidence.map((item) => (
                    <EvidenceCard key={item.id} item={item} pending={pending} onRemove={() => removeEvidence(item.id)} />
                  ))}
                </section>

                <form className="card form incident-evidence-form" onSubmit={addEvidence}>
                  <h3>Add evidence</h3>
                  <label>
                    What do you know?
                    <textarea
                      value={evidenceSummary}
                      onChange={(event) => setEvidenceSummary(event.target.value)}
                      required
                      maxLength={2000}
                      placeholder="On-call note, hypothesis, fix commit, or test that covers the failure."
                    />
                  </label>
                  <label>
                    Function in this project
                    <select value={functionId} onChange={(event) => setFunctionId(event.target.value)}>
                      <option value="">No function linked yet</option>
                      {functions.map((node) => (
                        <option key={node.id} value={node.id}>{node.label}</option>
                      ))}
                    </select>
                  </label>
                  {functions.length === 0 && (
                    <p className="hint">
                      Ingest a commit first so functions appear here. You can save evidence now and link a function later by adding another item.{' '}
                      <Link to={`/projects/${projectId}/ingestions`}>Go to ingestion</Link>
                    </p>
                  )}
                  <details
                    className="quiet"
                    open={showClassification}
                    onToggle={(event) => setShowClassification((event.target as HTMLDetailsElement).open)}
                  >
                    <summary>Classification</summary>
                    <div className="incident-classification">
                      <label>
                        Type
                        <select value={evidenceType} onChange={(event) => setEvidenceType(event.target.value as typeof evidenceType)}>
                          {EVIDENCE_TYPES.map((value) => <option key={value} value={value}>{formatLabel(value)}</option>)}
                        </select>
                      </label>
                      <label>
                        Relationship
                        <select value={relationship} onChange={(event) => setRelationship(event.target.value as typeof relationship)}>
                          {RELATIONSHIPS.map((value) => <option key={value} value={value}>{formatLabel(value)}</option>)}
                        </select>
                      </label>
                      <label>
                        Verification
                        <select value={verificationStatus} onChange={(event) => setVerificationStatus(event.target.value as typeof verificationStatus)}>
                          {VERIFICATION.map((value) => <option key={value} value={value}>{formatLabel(value)}</option>)}
                        </select>
                      </label>
                      <label>
                        Confidence
                        <select value={confidence} onChange={(event) => setConfidence(event.target.value as typeof confidence)}>
                          {CONFIDENCE.map((value) => <option key={value} value={value}>{formatLabel(value)}</option>)}
                        </select>
                      </label>
                    </div>
                  </details>
                  <button className="button" type="submit" disabled={pending}>{pending ? 'Saving…' : 'Attach evidence'}</button>
                </form>
              </>
            )}

            {mode === 'view' && !detail && items.length === 0 && (
              <EmptyState
                title="Start with a postmortem"
                body="Create an incident for something that already happened, or load labeled payment demos to see how analysis uses them."
                action={{ to: `/projects/${projectId}/graph`, label: 'Browse graph' }}
              />
            )}

            {mode === 'view' && !detail && items.length > 0 && (
              <div className="skeleton" role="status">Loading incident…</div>
            )}

            {mode === 'create' && items.length === 0 && (
              <article className="card incident-demo">
                <h3>Prefer a demo?</h3>
                <p className="muted">Load two synthetic payment incidents. They are labeled clearly and only affect analysis when they match ingested code.</p>
                <button className="button secondary" type="button" disabled={pending} onClick={seedSynthetic}>
                  Load demo examples
                </button>
              </article>
            )}
          </div>
        </div>
      )}
    </section>
  )
}

function EvidenceCard({
  item,
  pending,
  onRemove,
}: {
  item: IncidentEvidence
  pending: boolean
  onRemove: () => void
}) {
  return (
    <article className="card evidence-card">
      <div className="evidence-card-head">
        <div>
          <p className="evidence-kind">{formatLabel(item.evidenceType)}</p>
          <p className="evidence-meta">
            {formatLabel(item.relationship)} · {formatLabel(item.verificationStatus)} · {item.confidence} confidence
          </p>
        </div>
        <button type="button" className="linkish" disabled={pending} onClick={onRemove}>Remove</button>
      </div>
      <p>{item.summary}</p>
      <p className="hint">
        {item.functionId
          ? <>Linked function <span className="mono">{item.functionId}</span></>
          : 'Not linked to a function — analysis will not treat this as related to a change.'}
      </p>
    </article>
  )
}
