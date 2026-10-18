import { describe, expect, it } from 'vitest'
import type { Project } from './api/types'
import { nextSentence, nextStep, projectProgress } from './progress'

const project: Project = {
  id: 'project-1',
  name: 'Payment Service',
  description: 'Owned project',
  repositoryConnected: false,
  repository: null,
  graphStatus: 'READY',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  latestIngestion: null,
  latestAnalysis: null,
}

describe('project progress', () => {
  it('starts at the repository once setup is ready', () => {
    const step = nextStep(project)
    expect(step.id).toBe('repository')
    expect(nextSentence(step)).toBe('Next: connect a public repository.')
    expect(projectProgress(project).map((item) => item.label)).toEqual(['Prepare', 'Repository', 'Ingestion', 'Impact'])
  })

  it('keeps a failed setup ahead of a connected repository', () => {
    const step = nextStep({
      ...project,
      graphStatus: 'FAILED',
      repositoryConnected: true,
    })
    expect(step.id).toBe('graph')
    expect(step.detail).toBe('Needs a retry')
  })

  it('ends on a ready impact report', () => {
    const step = nextStep({
      ...project,
      repositoryConnected: true,
      latestIngestion: { id: 'job-1', status: 'SUCCEEDED', detail: null, createdAt: project.createdAt },
      latestAnalysis: { id: 'analysis-1', status: 'COMPLETED', detail: null, createdAt: project.createdAt },
    })
    expect(step.done).toBe(true)
    expect(nextSentence(step)).toBe('The impact report is ready to read.')
  })
})
