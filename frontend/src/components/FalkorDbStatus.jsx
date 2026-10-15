import { useCallback, useEffect, useState } from 'react'
import './FalkorDbStatus.css'

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? ''
const POLL_MS = 15_000

async function fetchHealth() {
  const res = await fetch(`${API_BASE}/api/graph/health`)
  const data = await res.json().catch(() => ({}))
  const up = res.ok && data.status === 'UP'
  return { up, data }
}

export default function FalkorDbStatus() {
  const [state, setState] = useState({ kind: 'loading' })

  const applyResult = useCallback((result, err) => {
    if (err) {
      setState({
        kind: 'ready',
        up: false,
        error: 'Could not reach the backend. Is it running on port 1212?',
        refreshing: false,
      })
      return
    }

    const { up, data } = result
    setState({
      kind: 'ready',
      up,
      graph: data.graph,
      database: data.database,
      target: data.target,
      error: data.error,
      refreshing: false,
    })
  }, [])

  const refresh = useCallback(async () => {
    setState((prev) =>
      prev.kind === 'ready'
        ? { ...prev, refreshing: true }
        : { kind: 'loading' },
    )

    try {
      applyResult(await fetchHealth())
    } catch {
      applyResult(null, true)
    }
  }, [applyResult])

  useEffect(() => {
    let cancelled = false

    async function poll() {
      try {
        const result = await fetchHealth()
        if (!cancelled) {
          applyResult(result)
        }
      } catch {
        if (!cancelled) {
          applyResult(null, true)
        }
      }
    }

    poll()
    const id = setInterval(poll, POLL_MS)
    return () => {
      cancelled = true
      clearInterval(id)
    }
  }, [applyResult])

  const { kind, up, graph, database, target, error, refreshing } =
    state.kind === 'ready' ? state : { refreshing: false }

  const statusLabel =
    kind === 'loading' ? 'Checking…' : up ? 'Up' : 'Down'

  return (
    <section
      className={`falkor-status ${kind === 'loading' ? 'is-loading' : up ? 'is-up' : 'is-down'}`}
      aria-live="polite"
      aria-busy={kind === 'loading' || refreshing}
    >
      <div className="falkor-status-header">
        <span className="falkor-status-dot" aria-hidden="true" />
        <div>
          <p className="falkor-status-title">FalkorDB</p>
          <p className="falkor-status-label">{statusLabel}</p>
        </div>
        <button
          type="button"
          className="falkor-status-refresh"
          onClick={refresh}
          disabled={kind === 'loading' || refreshing}
        >
          {refreshing ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>

      {kind === 'ready' && up && (
        <dl className="falkor-status-meta">
          {database && (
            <>
              <dt>Database</dt>
              <dd>{database}</dd>
            </>
          )}
          {graph && (
            <>
              <dt>Graph</dt>
              <dd>{graph}</dd>
            </>
          )}
        </dl>
      )}

      {kind === 'ready' && !up && error && (
        <p className="falkor-status-error">{error}</p>
      )}

      {kind === 'ready' && !up && target && (
        <p className="falkor-status-target">{target}</p>
      )}
    </section>
  )
}
