import type { WorkflowDetail, WorkflowStatusStreamEvent, WorkflowStreamEvent, WorkflowSummary } from '../api/types'
import { isTerminalStatus, upsertEvent } from './activity'

export function applyWorkflowStreamEvent(detail: WorkflowDetail, payload: WorkflowStreamEvent): WorkflowDetail {
  if (detail.id !== payload.runId) return detail
  return {
    ...detail,
    status: payload.runStatus,
    currentTask: payload.currentTask,
    events: upsertEvent(detail.events, {
      id: `${payload.runId}-${payload.sequence}`,
      sequence: payload.sequence,
      agent: payload.agent,
      phase: payload.phase,
      status: payload.status,
      summary: payload.summary,
      detail: payload.detail,
      createdAt: payload.createdAt,
    }),
  }
}

export function applyWorkflowStatus(detail: WorkflowDetail, payload: WorkflowStatusStreamEvent): WorkflowDetail {
  if (detail.id !== payload.runId) return detail
  return {
    ...detail,
    status: payload.status,
    currentTask: payload.currentTask,
    developerNote: payload.developerNote,
    validationNote: payload.validationNote,
    fixBranch: payload.fixBranch,
    fixPullRequestUrl: payload.fixPullRequestUrl,
    fixPullRequestNumber: payload.fixPullRequestNumber,
    errorMessage: payload.errorMessage,
    startedAt: payload.startedAt,
    completedAt: payload.completedAt,
  }
}

export function summaryFromStatus(detail: WorkflowSummary, payload: WorkflowStatusStreamEvent): WorkflowSummary {
  if (detail.id !== payload.runId) return detail
  return {
    ...detail,
    status: payload.status,
    currentTask: payload.currentTask,
    fixBranch: payload.fixBranch,
    fixPullRequestUrl: payload.fixPullRequestUrl,
    fixPullRequestNumber: payload.fixPullRequestNumber,
    errorMessage: payload.errorMessage,
    startedAt: payload.startedAt,
    completedAt: payload.completedAt,
  }
}

export function shouldRefreshDetail(payload: WorkflowStreamEvent) {
  return isTerminalStatus(payload.runStatus)
    || payload.phase === 'REVIEW'
    || payload.phase === 'FIX'
    || payload.phase === 'PULL_REQUEST'
}
