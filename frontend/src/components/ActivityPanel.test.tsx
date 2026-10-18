import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { WorkflowDetail } from '../api/types'
import { ActivityPanel } from './ActivityPanel'

const run: WorkflowDetail = {
  id: 'run-1',
  eventType: 'PULL_REQUEST',
  status: 'RUNNING',
  commitSha: 'abc1234',
  pullRequestNumber: 14,
  sourceBranch: 'feature/pay',
  targetBranch: 'release',
  defaultBranch: 'main',
  selectedBranch: 'develop',
  title: 'Ship release',
  currentTask: 'Code Reviewer is investigating dependencies',
  developerNote: null,
  validationNote: null,
  fixBranch: null,
  fixPullRequestUrl: null,
  fixPullRequestNumber: null,
  errorMessage: null,
  attempt: 1,
  createdAt: '2026-10-17T09:00:00Z',
  startedAt: '2026-10-17T09:00:00Z',
  completedAt: null,
  review: {
    summary: 'One finding',
    findings: [{
      id: 'f1',
      severity: 'HIGH',
      category: 'BREAKING',
      title: 'Removed function still has callers',
      detail: 'charge was removed.',
      evidenceIds: [],
      affectedComponents: ['checkout()'],
      recommendation: 'Update the callers.',
      actionable: true,
    }],
    uncertainties: [],
    investigationSteps: ['trace_function_dependencies: Traced 1 potentially impacted functions.'],
  },
  events: [
    {
      id: 'e1',
      sequence: 1,
      agent: 'CODE_REVIEWER',
      phase: 'REVIEW',
      status: 'RUNNING',
      summary: 'Code Reviewer is investigating dependencies',
      detail: null,
      createdAt: '2026-10-17T09:00:02Z',
    },
  ],
}

describe('ActivityPanel', () => {
  it('shows the reviewer at work and the recorded finding', () => {
    render(<ActivityPanel run={run} now={Date.parse('2026-10-17T09:00:10Z')} />)
    expect(screen.getByRole('heading', { name: 'Code Reviewer' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Developer' })).toBeInTheDocument()
    expect(screen.getAllByText('Code Reviewer is investigating dependencies').length).toBeGreaterThan(0)
    expect(screen.getByText('Removed function still has callers')).toBeInTheDocument()
    expect(screen.getByText('0 of 6 stages recorded')).toBeInTheDocument()
    expect(screen.getByText((_content, node) => node?.tagName === 'DD' && node.textContent === 'feature/pay')).toBeInTheDocument()
    expect(screen.getByText((_content, node) => node?.tagName === 'DD' && node.textContent === 'release')).toBeInTheDocument()
    expect(screen.queryByText(/%/)).not.toBeInTheDocument()
  })
})
