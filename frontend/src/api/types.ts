export interface FieldError {
  field: string
  message: string
}

export interface ApiErrorBody {
  code: string
  message: string
  fieldErrors?: FieldError[]
}

export interface CurrentUser {
  id: string
  email: string
  createdAt: string
}

export interface RepositorySummary {
  githubRepositoryId: number
  ownerName: string
  repositoryName: string
  defaultBranch: string
  repositoryUrl: string
  visibility: string
  lastSyncedAt: string | null
}

export interface ActivitySummary {
  id: string
  status: string
  detail: string | null
  createdAt: string
}

export interface Project {
  id: string
  name: string
  description: string | null
  repositoryConnected: boolean
  repository: RepositorySummary | null
  graphStatus: 'PENDING' | 'READY' | 'FAILED' | string
  createdAt: string
  updatedAt: string
  latestIngestion: ActivitySummary | null
  latestAnalysis: ActivitySummary | null
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export interface IngestionJob {
  id: string
  status: string
  requestedCommitSha: string | null
  progressMessage: string | null
  errorCode: string | null
  createdAt: string
  startedAt: string | null
  completedAt: string | null
}

export interface CommitSummary {
  sha: string
  message: string
  committedAt: string
}

export interface CommitPoll {
  commits: CommitSummary[]
  lastPolledAt: string | null
  pollIntervalSeconds: number
}

export interface CommitFile {
  filename: string
  previousFilename: string | null
  status: string
  additions: number
  deletions: number
}

export interface CommitDetail {
  sha: string
  message: string
  committedAt: string
  parentShas: string[]
  files: CommitFile[]
}

export interface PullRequest {
  number: number
  title: string
  headSha: string
  baseSha: string
  htmlUrl: string
}

export interface GraphNode {
  id: string
  type: string
  label: string
  properties: Record<string, unknown>
}

export interface GraphEdge {
  type: string
  source: string
  target: string
  properties: Record<string, unknown>
}

export interface GraphView {
  nodes: GraphNode[]
  edges: GraphEdge[]
  truncated: boolean
}

export interface AnalysisReport {
  id: string
  commitSha: string
  pullRequestNumber: number | null
  riskLevel: string
  riskScore: number
  riskScoreMax: number
  riskScoreInterpretation: string
  riskReasons: string[]
  changedFiles: { path: string; changeType: string }[]
  changedFunctions: { id: string; name: string; signature: string; filePath: string; changeType: string }[]
  impactedFunctions: { id: string; name: string; signature: string; filePath: string; direction: string; depth: number }[]
  impactedServices: string[]
  paths: { fromId: string; relationship: string; toId: string; explanation: string }[]
  recommendedTests: { id: string; name: string; filePath: string; direct: boolean; reason: string }[]
  relatedIncidents: {
    id: string
    title: string
    summary: string
    synthetic: boolean
    sourceType: string
    evidence: {
      id: string
      evidenceType: string
      relationship: string
      verificationStatus: string
      confidence: string
      synthetic: boolean
      sourceType: string
      summary: string
    }[]
  }[]
  uncertainties: string[]
}

export interface AgentReport {
  commitSha: string
  pullRequestNumber: number | null
  summary: string
  riskLevel: string
  riskScore: number
  riskScoreMax: number
  riskScoreInterpretation: string
  riskFactors: { statement: string; kind: string; evidenceIds: string[] }[]
  changedComponents: { id: string; kind: string; name: string; detail: string }[]
  impactedComponents: { id: string; kind: string; name: string; detail: string }[]
  impactPaths: { fromId: string; relationship: string; toId: string; explanation: string }[]
  recommendedTests: { id: string; name: string; direct: boolean; reason: string; evidenceIds: string[] }[]
  relatedIncidents: { id: string; title: string; summary: string; synthetic: boolean; sourceType: string }[]
  evidence: {
    id: string
    incidentId: string
    evidenceType: string
    relationship: string
    verificationStatus: string
    confidence: string
    synthetic: boolean
    summary: string
  }[]
  uncertainties: string[]
  investigationSummary: { tool: string; summary: string }[]
  agentStatus: string
}

export interface SyntheticSeed {
  message: string
  scenarioKeys: string[]
  linkedFunctionIds: string[]
  notes: string[]
}
