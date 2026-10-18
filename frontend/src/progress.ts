import type { Project } from './api/types'

export interface ProgressStep {
  id: 'graph' | 'repository' | 'ingestion' | 'impact'
  label: string
  detail: string
  done: boolean
  to: string
}

export function projectProgress(project: Project): ProgressStep[] {
  const repoName = project.repository
    ? `${project.repository.ownerName}/${project.repository.repositoryName}`
    : 'Connect'
  const ingestion = project.latestIngestion?.status
  const analysis = project.latestAnalysis?.status
  return [
    {
      id: 'graph',
      label: 'Prepare',
      detail: project.graphStatus === 'READY' ? 'Ready' : project.graphStatus === 'FAILED' ? 'Needs a retry' : 'Starting',
      done: project.graphStatus === 'READY',
      to: `/projects/${project.id}/settings`,
    },
    {
      id: 'repository',
      label: 'Repository',
      detail: project.repositoryConnected ? repoName : 'Connect',
      done: project.repositoryConnected,
      to: `/projects/${project.id}/repository`,
    },
    {
      id: 'ingestion',
      label: 'Ingestion',
      detail: ingestion ? ingestion.replaceAll('_', ' ') : 'Start',
      done: ingestion === 'SUCCEEDED',
      to: `/projects/${project.id}/ingestions`,
    },
    {
      id: 'impact',
      label: 'Impact',
      detail: analysis ? analysis.replaceAll('_', ' ') : 'Run',
      done: analysis != null && analysis !== 'FAILED',
      to: `/projects/${project.id}/analysis`,
    },
  ]
}

export function nextStep(project: Project): ProgressStep {
  const steps = projectProgress(project)
  return steps.find((step) => !step.done) ?? steps[steps.length - 1]
}

export function nextSentence(step: ProgressStep): string {
  if (step.done) return 'The impact report is ready to read.'
  if (step.id === 'graph') return 'Next: retry setup so the project can store code relationships.'
  if (step.id === 'repository') return 'Next: connect a public repository.'
  if (step.id === 'ingestion') return 'Next: ingest a commit.'
  return 'Next: run the impact report.'
}
