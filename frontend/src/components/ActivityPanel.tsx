import type { WorkflowDetail, WorkflowEvent } from '../api/types'
import { BranchText, StatusBadge } from './ui'
import { elapsedLabel, latestAgentStatus, phaseLabel, stageProgress, statusLabel, WORKFLOW_PHASES } from './activity'

const AGENTS = [
  { id: 'CODE_REVIEWER', name: 'Code Reviewer', role: 'Impact, tests, and historical risk' },
  { id: 'DEVELOPER', name: 'Developer', role: 'Fix proposals and pull requests' },
]

export function ActivityPanel({
  run,
  now,
  onRetry,
  retrying,
}: {
  run: WorkflowDetail
  now: number
  onRetry?: () => void
  retrying?: boolean
}) {
  const progress = stageProgress(run.events)
  const elapsed = elapsedLabel(run.startedAt, run.completedAt, now)
  const findings = [...(run.review?.findings ?? [])].sort((left, right) => severityRank(left.severity) - severityRank(right.severity))
  return (
    <div className="stack">
      <header className="activity-head">
        <div>
          <p className="kicker">{run.eventType.replaceAll('_', ' ')}</p>
          <h2>{run.title || 'Workflow'}</h2>
          <p className="muted">{run.currentTask || 'Waiting for the next recorded event.'}</p>
        </div>
        <div className="activity-head-meta">
          <StatusBadge value={statusLabel(run.status)} />
          {elapsed && <p className="mono">{run.completedAt ? 'Elapsed' : 'Running'} {elapsed}</p>}
        </div>
      </header>
      <BranchFacts run={run} />
      <section className="stage-track" aria-label="Workflow stages">
        {WORKFLOW_PHASES.map((phase) => {
          const state = phaseState(run.events, phase)
          return (
            <div key={phase} className={`stage-step is-${state}`}>
              <span />
              {phaseLabel(phase)}
            </div>
          )
        })}
      </section>
      <p className="muted">{`${progress.completed} of ${progress.total} stages recorded`}</p>
      <div className="stage-meter" aria-hidden="true">
        <span style={{ width: `${(progress.completed / progress.total) * 100}%` }} />
      </div>
      {run.errorMessage && (
        <div className="banner error" role="alert">
          <p>{run.errorMessage}</p>
          {run.status === 'FAILED' && onRetry && (
            <button className="button secondary" type="button" onClick={onRetry} disabled={retrying}>
              {retrying ? 'Retrying…' : 'Retry'}
            </button>
          )}
        </div>
      )}
      {run.status === 'COMPLETED' && !run.fixPullRequestUrl && run.developerNote?.includes('No pull request was opened') && onRetry && (
        <div className="banner">
          <p>{run.developerNote}</p>
          <button className="button secondary" type="button" onClick={onRetry} disabled={retrying}>
            {retrying ? 'Running…' : 'Run the fix again'}
          </button>
        </div>
      )}
      {findings.length > 0 && (
        <section className="stack">
          <h3>Findings</h3>
          {findings.map((finding) => (
            <article key={finding.id} className={`finding severity-${finding.severity.toLowerCase()}`}>
              <header>
                <StatusBadge value={finding.severity} />
                <strong>{finding.title}</strong>
              </header>
              <p>{finding.detail}</p>
              {finding.affectedComponents.length > 0 && (
                <p className="muted">Affected: {finding.affectedComponents.join(', ')}</p>
              )}
              <p>{finding.recommendation}</p>
            </article>
          ))}
        </section>
      )}
      {run.fixPullRequestUrl && (
        <article className="card pull-request-card">
          <h3>Fix pull request</h3>
          <p>Branch {run.fixBranch}. This pull request is waiting for a person. It was not merged.</p>
          <a href={run.fixPullRequestUrl}>Pull request #{run.fixPullRequestNumber}</a>
        </article>
      )}
      <div className="agent-board">
        {AGENTS.map((agent) => {
          const status = latestAgentStatus(run.events, agent.id)
          const latest = [...run.events].reverse().find((event) => event.agent === agent.id)
          return (
            <article key={agent.id} className={`agent-card agent-${agent.id.toLowerCase()} is-${status.toLowerCase()}`}>
              <header>
                <h3>{agent.name}</h3>
                <StatusBadge value={statusLabel(status)} />
              </header>
              <p className="muted">{agent.role}</p>
              <p>{latest?.summary ?? 'No recorded action yet.'}</p>
            </article>
          )
        })}
      </div>
      {(run.developerNote || run.validationNote) && (
        <article className="card">
          <h3>Developer result</h3>
          {run.developerNote && <p>{run.developerNote}</p>}
          {run.validationNote && <p className="muted">{run.validationNote}</p>}
        </article>
      )}
      {run.review && run.review.investigationSteps.length > 0 && (
        <article className="card">
          <h3>Investigation</h3>
          <ul>{run.review.investigationSteps.map((step) => <li key={step}>{step}</li>)}</ul>
        </article>
      )}
      <section className="card">
        <h3>Activity</h3>
        {run.events.length === 0 && <p className="muted">No events recorded yet.</p>}
        <ol className="timeline">
          {run.events.map((event) => (
            <li key={event.id}>
              <div>
                <StatusBadge value={statusLabel(event.status)} />
                <strong>{event.summary}</strong>
              </div>
              <p className="muted">{agentLabel(event.agent)} · {phaseLabel(event.phase)} · {new Date(event.createdAt).toLocaleTimeString()}</p>
              {event.detail && <p>{event.detail}</p>}
            </li>
          ))}
        </ol>
      </section>
    </div>
  )
}

function BranchFacts({ run }: { run: WorkflowDetail }) {
  return (
    <dl className="branch-facts">
      <div><dt>Selected</dt><dd><BranchText value={run.selectedBranch || 'Not set'} /></dd></div>
      <div><dt>Default</dt><dd><BranchText value={run.defaultBranch} /></dd></div>
      <div><dt>Source</dt><dd><BranchText value={run.sourceBranch} /></dd></div>
      <div><dt>Target</dt><dd><BranchText value={run.targetBranch} /></dd></div>
    </dl>
  )
}

function phaseState(events: WorkflowEvent[], phase: string) {
  const matches = events.filter((event) => event.phase === phase)
  if (matches.some((event) => event.status === 'FAILED')) return 'failed'
  if (matches.some((event) => event.status === 'COMPLETED')) return 'complete'
  if (matches.some((event) => event.status === 'RUNNING' || event.status === 'RETRYING')) return 'current'
  return 'waiting'
}

function severityRank(severity: string) {
  return ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'].indexOf(severity)
}

function agentLabel(agent: string) {
  if (agent === 'CODE_REVIEWER') return 'Code Reviewer'
  if (agent === 'DEVELOPER') return 'Developer'
  if (agent === 'INGESTION') return 'Ingestion'
  if (agent === 'GRAPH') return 'Graph'
  return 'System'
}
