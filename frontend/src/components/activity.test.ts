import { describe, expect, it } from 'vitest'
import { elapsedLabel, followActiveRun, stageProgress, upsertEvent, upsertRun } from './activity'

describe('workflow activity', () => {
  it('counts only stages that have a completed event', () => {
    const progress = stageProgress([
      { phase: 'DETECTED', status: 'COMPLETED' },
      { phase: 'FETCH', status: 'RUNNING' },
      { phase: 'REVIEW', status: 'COMPLETED' },
    ])
    expect(progress).toEqual({ completed: 2, total: 6 })
  })

  it('ignores a repeated event sequence', () => {
    const first = { sequence: 2, summary: 'Graph analysis completed' }
    const events = upsertEvent([{ sequence: 1, summary: 'Commit detected' }], first)
    expect(upsertEvent(events, { sequence: 2, summary: 'duplicate' })).toHaveLength(2)
  })

  it('does not invent elapsed time without a start', () => {
    expect(elapsedLabel(null, null, Date.now())).toBeNull()
  })

  it('merges workflow summaries without duplicating ids', () => {
    const first = { id: 'run-1', createdAt: '2026-10-18T10:00:00Z', status: 'RUNNING' }
    const updated = { id: 'run-1', createdAt: '2026-10-18T10:00:00Z', status: 'COMPLETED' }
    expect(upsertRun([first], updated)).toEqual([updated])
    expect(upsertRun([], { id: 'run-2', createdAt: '2026-10-18T11:00:00Z', status: 'QUEUED' })).toHaveLength(1)
  })

  it('follows a new active run only when nothing else is running', () => {
    const runs = [{ id: 'run-1', status: 'COMPLETED' }]
    expect(followActiveRun('run-1', runs, { id: 'run-2', status: 'RUNNING' })).toBe('run-2')
    expect(followActiveRun('run-3', [{ id: 'run-3', status: 'RUNNING' }], { id: 'run-4', status: 'RUNNING' })).toBe('run-3')
  })
})
