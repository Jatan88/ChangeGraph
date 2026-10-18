export const WORKFLOW_PHASES = ['DETECTED', 'FETCH', 'GRAPH', 'REVIEW', 'FIX', 'PULL_REQUEST'] as const

const PHASE_LABELS: Record<string, string> = {
  DETECTED: 'Detected',
  FETCH: 'Fetch',
  GRAPH: 'Graph',
  REVIEW: 'Review',
  FIX: 'Fix',
  PULL_REQUEST: 'Pull request',
}

export function phaseLabel(phase: string) {
  return PHASE_LABELS[phase] ?? phase
}

export function statusLabel(status: string) {
  return status.replaceAll('_', ' ')
}

export function stageProgress(events: { phase: string; status: string }[]) {
  const completed = WORKFLOW_PHASES.filter((phase) =>
    events.some((event) => event.phase === phase && event.status === 'COMPLETED'),
  ).length
  return { completed, total: WORKFLOW_PHASES.length }
}

export function upsertEvent<T extends { sequence: number }>(events: T[], incoming: T) {
  if (events.some((event) => event.sequence === incoming.sequence)) {
    return events
  }
  return [...events, incoming].sort((left, right) => left.sequence - right.sequence)
}

export function latestAgentStatus(events: { agent: string; status: string }[], agent: string) {
  const matches = events.filter((event) => event.agent === agent)
  if (matches.length === 0) return 'IDLE'
  return matches[matches.length - 1].status
}

export function elapsedLabel(startedAt: string | null, completedAt: string | null, now: number) {
  if (!startedAt) return null
  const start = Date.parse(startedAt)
  const end = completedAt ? Date.parse(completedAt) : now
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return null
  const seconds = Math.floor((end - start) / 1000)
  const minutes = Math.floor(seconds / 60)
  const remain = seconds % 60
  if (minutes > 0) return `${minutes}m ${remain}s`
  return `${remain}s`
}

export function activeRun(runs: { id: string; status: string }[]) {
  return runs.find((run) => run.status === 'QUEUED' || run.status === 'RUNNING') ?? runs[0] ?? null
}

export function isActiveStatus(status: string) {
  return status === 'QUEUED' || status === 'RUNNING'
}

export function isTerminalStatus(status: string) {
  return status === 'COMPLETED'
    || status === 'FAILED'
    || status === 'CANCELLED'
    || status === 'AWAITING_HUMAN_REVIEW'
}

export function upsertRun<T extends { id: string; createdAt: string }>(runs: T[], incoming: T): T[] {
  const index = runs.findIndex((run) => run.id === incoming.id)
  if (index === -1) {
    return [...runs, incoming].sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt))
  }
  const next = [...runs]
  next[index] = { ...next[index], ...incoming }
  return next
}

export function followActiveRun(
  currentId: string | null,
  runs: { id: string; status: string }[],
  incoming: { id: string; status: string },
) {
  if (!isActiveStatus(incoming.status)) return currentId
  if (!currentId) return incoming.id
  const current = runs.find((run) => run.id === currentId)
  if (!current || isTerminalStatus(current.status)) return incoming.id
  return currentId
}
