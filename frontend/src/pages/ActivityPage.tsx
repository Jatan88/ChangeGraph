import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiError } from '../api/client'
import type {
  Page,
  WorkflowDetail,
  WorkflowProjectStreamEvent,
  WorkflowStatusStreamEvent,
  WorkflowStreamEvent,
  WorkflowSummary,
} from '../api/types'
import { activeRun, followActiveRun, isActiveStatus, isTerminalStatus, statusLabel, upsertRun } from '../components/activity'
import { ActivityPanel } from '../components/ActivityPanel'
import {
  applyWorkflowStatus,
  applyWorkflowStreamEvent,
  shouldRefreshDetail,
  summaryFromStatus,
} from '../components/workflowStream'
import { BranchText, EmptyState, ErrorText, StatusBadge } from '../components/ui'
import { useProject } from './ProjectPages'

const RECONNECT_MS = 2500
const POLL_MS = 4000

export function ActivityPage() {
  const { projectId, project, error } = useProject()
  const [runs, setRuns] = useState<WorkflowSummary[] | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [detail, setDetail] = useState<WorkflowDetail | null>(null)
  const [localError, setLocalError] = useState<string | null>(null)
  const [retrying, setRetrying] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  const selectedIdRef = useRef<string | null>(null)
  const lastSequenceRef = useRef(0)

  useEffect(() => {
    selectedIdRef.current = selectedId
  }, [selectedId])

  const loadRuns = useCallback(() => {
    return api<Page<WorkflowSummary>>(`/api/projects/${projectId}/workflows`)
      .then((page) => {
        setRuns(page.items)
        setSelectedId((current) => current ?? activeRun(page.items)?.id ?? null)
        return page.items
      })
  }, [projectId])

  const refreshDetail = useCallback((runId: string) => {
    return api<WorkflowDetail>(`/api/projects/${projectId}/workflows/${runId}`)
      .then((run) => {
        if (selectedIdRef.current === runId) {
          setDetail(run)
          lastSequenceRef.current = run.events.at(-1)?.sequence ?? lastSequenceRef.current
        }
        setRuns((current) => (current ? upsertRun(current, run) : current))
      })
  }, [projectId])

  useEffect(() => {
    let cancelled = false
    loadRuns()
      .catch((err: unknown) => {
        if (!cancelled) setLocalError(err instanceof ApiError ? err.message : 'Could not load activity.')
      })
    return () => {
      cancelled = true
    }
  }, [loadRuns])

  useEffect(() => {
    let cancelled = false
    let source: EventSource | null = null
    let reconnect = 0
    let poll = 0

    function connect() {
      source?.close()
      if (poll) {
        window.clearInterval(poll)
        poll = 0
      }
      source = new EventSource(`/api/projects/${projectId}/workflows/stream`)
      source.addEventListener('workflow-project', (event) => {
        const payload = JSON.parse((event as MessageEvent<string>).data) as WorkflowProjectStreamEvent
        setRuns((current) => {
          const next = upsertRun(current ?? [], payload.summary)
          setSelectedId((selected) => followActiveRun(selected, next, payload.summary))
          return next
        })
        if (payload.summary.id === selectedIdRef.current) {
          void refreshDetail(payload.summary.id)
        }
      })
      source.onerror = () => {
        source?.close()
        if (cancelled) return
        if (!poll) {
          void loadRuns().catch(() => {})
          poll = window.setInterval(() => {
            void loadRuns().catch(() => {})
          }, POLL_MS)
        }
        reconnect = window.setTimeout(connect, RECONNECT_MS)
      }
    }

    connect()
    return () => {
      cancelled = true
      source?.close()
      window.clearTimeout(reconnect)
      if (poll) window.clearInterval(poll)
    }
  }, [loadRuns, projectId, refreshDetail])

  useEffect(() => {
    if (!selectedId) {
      setDetail(null)
      lastSequenceRef.current = 0
      return
    }
    const runId = selectedId
    let cancelled = false
    let source: EventSource | null = null
    let reconnect = 0
    let poll = 0
    lastSequenceRef.current = 0

    function connect(after: number) {
      source?.close()
      if (poll) {
        window.clearInterval(poll)
        poll = 0
      }
      source = new EventSource(`/api/projects/${projectId}/workflows/${runId}/stream?after=${after}`)
      source.addEventListener('workflow', (event) => {
        const payload = JSON.parse((event as MessageEvent<string>).data) as WorkflowStreamEvent
        lastSequenceRef.current = Math.max(lastSequenceRef.current, payload.sequence)
        setDetail((current) => (current && current.id === payload.runId ? applyWorkflowStreamEvent(current, payload) : current))
        setRuns((current) => current?.map((run) => run.id === payload.runId
          ? { ...run, status: payload.runStatus, currentTask: payload.currentTask }
          : run) ?? current)
        if (shouldRefreshDetail(payload)) {
          void refreshDetail(payload.runId)
        }
      })
      source.addEventListener('workflow-status', (event) => {
        const payload = JSON.parse((event as MessageEvent<string>).data) as WorkflowStatusStreamEvent
        setDetail((current) => (current && current.id === payload.runId ? applyWorkflowStatus(current, payload) : current))
        setRuns((current) => current?.map((run) => (run.id === payload.runId ? summaryFromStatus(run, payload) : run)) ?? current)
        if (isTerminalStatus(payload.status)) {
          void refreshDetail(payload.runId)
        }
      })
      source.onerror = () => {
        source?.close()
        if (cancelled) return
        const after = lastSequenceRef.current
        if (!poll) {
          void refreshDetail(runId)
          poll = window.setInterval(() => {
            void refreshDetail(runId)
          }, POLL_MS)
        }
        reconnect = window.setTimeout(() => connect(after), RECONNECT_MS)
      }
    }

    refreshDetail(runId)
      .then(() => {
        if (!cancelled) connect(lastSequenceRef.current)
      })
      .catch((err: unknown) => {
        if (!cancelled) setLocalError(err instanceof ApiError ? err.message : 'Could not load this workflow.')
      })

    return () => {
      cancelled = true
      source?.close()
      window.clearTimeout(reconnect)
      if (poll) window.clearInterval(poll)
    }
  }, [projectId, refreshDetail, selectedId])

  useEffect(() => {
    const active = detail?.status
    if (!active || !isActiveStatus(active)) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [detail?.status])

  async function retry() {
    if (!detail) return
    setRetrying(true)
    setLocalError(null)
    try {
      const response = await api<{ id: string }>(`/api/projects/${projectId}/workflows/${detail.id}/retry`, { method: 'POST' })
      setSelectedId(response.id)
      setRuns((current) => current?.map((run) => run.id === response.id ? { ...run, status: 'QUEUED', errorMessage: null } : run) ?? current)
      await refreshDetail(response.id)
    } catch (err) {
      setLocalError(err instanceof ApiError ? err.message : 'Could not retry this workflow.')
    } finally {
      setRetrying(false)
    }
  }

  if (error) return <ErrorText error={error} />
  return (
    <section className="stack activity-page">
      <div>
        <p className="kicker">Reviews</p>
        <h1>Activity</h1>
        <p className="muted">Each commit or pull request becomes a review. Status and progress update here as the workflow runs.</p>
      </div>
      <ErrorText error={localError} />
      {runs === null && !localError && <div className="skeleton" role="status">Loading activity…</div>}
      {runs?.length === 0 && (
        <EmptyState
          title="No commits or pull requests yet"
          body="A later commit on the selected branch shows up here. Commits already on the branch when the repository was linked are left alone."
          action={project?.repositoryConnected
            ? { to: `/projects/${projectId}/ingestions`, label: 'Ingest a commit' }
            : { to: `/projects/${projectId}/repository`, label: 'Connect repository' }}
        />
      )}
      {runs && runs.length > 0 && (
        <div className="activity-layout">
          <div className="table-wrap workflow-index">
            <h2>Workflows</h2>
            <ul className="workflow-list">
              {runs.map((run) => (
                <li key={run.id}>
                  <button
                    type="button"
                    className={run.id === selectedId ? 'workflow-row is-selected' : 'workflow-row'}
                    aria-current={run.id === selectedId ? 'true' : undefined}
                    onClick={() => setSelectedId(run.id)}
                  >
                    <span className="workflow-title">{run.title || run.commitSha?.slice(0, 7) || 'Workflow'}</span>
                    <span className="workflow-branches">
                      <BranchText value={run.sourceBranch} /> → <BranchText value={run.targetBranch} />
                    </span>
                    <StatusBadge value={statusLabel(run.status)} />
                  </button>
                </li>
              ))}
            </ul>
          </div>
          {detail && detail.id === selectedId ? (
            <ActivityPanel run={detail} now={now} onRetry={retry} retrying={retrying} />
          ) : (
            <div className="skeleton" role="status">Loading workflow…</div>
          )}
        </div>
      )}
    </section>
  )
}
