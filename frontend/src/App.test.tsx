import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { AuthProvider } from './auth/AuthProvider'
import { AgentReportView, AnalysisView } from './pages/ProjectPages'
import type { AgentReport, AnalysisReport, CurrentUser, Page, Project } from './api/types'

const user: CurrentUser = { id: 'user-1', email: 'ada@example.com', createdAt: '2026-01-01T00:00:00Z' }

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

function json(status: number, body: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function installFetch(routes: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    return Promise.resolve(routes(url, init))
  }))
}

function renderApp(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('authentication screens', () => {
  it('sends an anonymous visitor from the dashboard to login', async () => {
    installFetch((url) => {
      if (url.endsWith('/api/auth/me')) return json(401, { code: 'UNAUTHENTICATED', message: 'Sign in required.' })
      return json(500, { code: 'UNEXPECTED', message: 'unexpected' })
    })
    renderApp('/')
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument()
  })

  it('rejects a login form without a valid email before calling the server', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).endsWith('/api/auth/me')) return json(401, { code: 'UNAUTHENTICATED', message: 'Sign in required.' })
      return json(500, { code: 'UNEXPECTED', message: 'unexpected' })
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/login')
    await screen.findByRole('heading', { name: 'Log in' })
    await userEvent.type(screen.getByLabelText('Email'), 'not-an-email')
    await userEvent.type(screen.getByLabelText('Password'), 'secret')
    await userEvent.click(screen.getByRole('button', { name: 'Log in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Enter the email and password')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('requires matching passwords on registration', async () => {
    installFetch((url) => {
      if (url.endsWith('/api/auth/me')) return json(401, { code: 'UNAUTHENTICATED', message: 'Sign in required.' })
      return json(500, { code: 'UNEXPECTED', message: 'unexpected' })
    })
    renderApp('/register')
    await screen.findByRole('heading', { name: 'Create account' })
    await userEvent.type(screen.getByLabelText('Email'), 'ada@example.com')
    await userEvent.type(screen.getByLabelText('Password'), 'correcthorse1')
    await userEvent.type(screen.getByLabelText('Confirm password'), 'correcthorse2')
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('does not match')
  })

  it('shows and hides the password', async () => {
    installFetch((url) => {
      if (url.endsWith('/api/auth/me')) return json(401, { code: 'UNAUTHENTICATED', message: 'Sign in required.' })
      return json(500, { code: 'UNEXPECTED', message: 'unexpected' })
    })
    renderApp('/register')
    await screen.findByRole('heading', { name: 'Create account' })
    const password = screen.getByLabelText('Password')
    expect(password).toHaveAttribute('type', 'password')
    await userEvent.click(screen.getByRole('button', { name: 'Show password' }))
    expect(password).toHaveAttribute('type', 'text')
    await userEvent.click(screen.getByRole('button', { name: 'Hide password' }))
    expect(password).toHaveAttribute('type', 'password')
  })

  it('logs out and returns to the login screen', async () => {
    installFetch((url, init) => {
      if (url.endsWith('/api/auth/me')) return json(200, user)
      if (url.endsWith('/api/projects')) return json(200, { items: [], page: 0, size: 20, total: 0 } satisfies Page<Project>)
      if (url.endsWith('/api/auth/logout') && init?.method === 'POST') return new Response(null, { status: 204 })
      return json(500, { code: 'UNEXPECTED', message: 'unexpected' })
    })
    renderApp('/')
    await screen.findByRole('heading', { name: 'Projects' })
    await userEvent.click(screen.getByRole('button', { name: 'Log out' }))
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument()
  })
})

describe('project workflow', () => {
  it('lists only the projects returned for the signed-in user', async () => {
    installFetch((url) => {
      if (url.endsWith('/api/auth/me')) return json(200, user)
      if (url.endsWith('/api/projects')) {
        return json(200, { items: [project], page: 0, size: 20, total: 1 } satisfies Page<Project>)
      }
      return json(500, { code: 'UNEXPECTED', message: 'unexpected' })
    })
    renderApp('/')
    expect(await screen.findByRole('heading', { name: 'Payment Service' })).toBeInTheDocument()
    expect(screen.queryByText('Another tenant project')).not.toBeInTheDocument()
    expect(screen.getByText('READY')).toBeInTheDocument()
  })

  it('shows a loading state and then an API error', async () => {
    const pending: Array<(value: Response) => void> = []
    installFetch((url) => {
      if (url.endsWith('/api/auth/me')) return json(200, user)
      if (url.endsWith('/api/projects')) {
        return new Promise<Response>((resolve) => {
          pending.push(resolve)
        })
      }
      return json(500, { code: 'UNEXPECTED', message: 'unexpected' })
    })
    renderApp('/')
    expect(await screen.findAllByText('Loading projects…')).not.toHaveLength(0)
    pending.splice(0).forEach((resolve) => {
      resolve(json(404, { code: 'NOT_FOUND', message: 'Project not found.' }))
    })
    expect(await screen.findByRole('alert')).toHaveTextContent('Project not found.')
  })

  it('keeps project sections in the sidebar and edit on its own page', async () => {
    installFetch((url) => {
      if (url.endsWith('/api/auth/me')) return json(200, user)
      if (url.endsWith('/api/projects')) {
        return json(200, { items: [project], page: 0, size: 20, total: 1 } satisfies Page<Project>)
      }
      if (url.endsWith('/api/projects/project-1')) return json(200, project)
      return json(500, { code: 'UNEXPECTED', message: 'unexpected' })
    })
    renderApp('/projects/project-1')
    expect(await screen.findByRole('heading', { name: 'Payment Service' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Delete project' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Edit project' })).not.toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Project' })
    expect(screen.queryByRole('link', { name: 'Overview' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Graph' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Show Payment Service sections' }))
    expect(nav).toHaveTextContent('Graph')
    expect(nav).toHaveTextContent('Ingestion')
    expect(nav).toHaveTextContent('Analysis')
    await userEvent.click(screen.getByRole('link', { name: 'Edit' }))
    expect(await screen.findByRole('heading', { name: 'Edit project' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument()
  })

  it('explains that only public repositories can be connected', async () => {
    installFetch((url) => {
      if (url.endsWith('/api/auth/me')) return json(200, user)
      if (url.endsWith('/api/projects/project-1')) return json(200, project)
      return json(500, { code: 'UNEXPECTED', message: 'unexpected' })
    })
    renderApp('/projects/project-1/repository')
    expect(await screen.findByText(/public GitHub repositories only/i)).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('GitHub repository URL'), 'https://example.com/private/repo')
    await userEvent.click(screen.getByRole('button', { name: 'Connect public repository' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('https://github.com/owner/repository')
    expect(screen.queryByLabelText(/token/i)).not.toBeInTheDocument()
  })
})

describe('analysis results', () => {
  it('renders risk, tests, and the synthetic incident label from the report', () => {
    const report: AnalysisReport = {
      id: 'analysis-1',
      commitSha: 'abc123def456',
      pullRequestNumber: null,
      riskLevel: 'HIGH',
      riskScore: 58,
      riskScoreMax: 100,
      riskScoreInterpretation: '50–74 is HIGH on a 0–100 baseline.',
      riskReasons: ['A changed function has impacted callers'],
      changedFiles: [{ path: 'src/Payment.java', changeType: 'MODIFIED' }],
      changedFunctions: [],
      impactedFunctions: [],
      impactedServices: [],
      paths: [],
      recommendedTests: [{ id: 't1', name: 'retryTest', filePath: 'src/test/RetryTest.java', direct: true, reason: 'Directly tests the changed function' }],
      relatedIncidents: [{
        id: 'incident-1',
        title: 'Duplicate ledger entries',
        summary: 'Synthetic example',
        synthetic: true,
        sourceType: 'SYNTHETIC',
        evidence: [],
      }],
      uncertainties: ['Calls outside the ingested files are unresolved'],
    }
    render(<AnalysisView report={report} />)
    expect(screen.getByText('HIGH')).toBeInTheDocument()
    expect(screen.getByText('SYNTHETIC')).toBeInTheDocument()
    expect(screen.getByText('Duplicate ledger entries')).toBeInTheDocument()
    expect(screen.getByText(/Directly tests the changed function/)).toBeInTheDocument()
    expect(screen.queryByText('Another project incident')).not.toBeInTheDocument()
  })

  it('labels agent hypotheses, synthetic evidence, and deterministic fallback', () => {
    const report: AgentReport = {
      commitSha: 'abc123def456',
      pullRequestNumber: null,
      summary: 'AI-assisted reasoning was unavailable, so this report uses deterministic graph analysis.',
      riskLevel: 'LOW',
      riskScore: 22,
      riskScoreMax: 100,
      riskScoreInterpretation: '0-24 is LOW.',
      riskFactors: [{ statement: 'An unverified cause is recorded.', kind: 'HYPOTHESIS', evidenceIds: ['cause'] }],
      changedComponents: [],
      impactedComponents: [],
      impactPaths: [],
      recommendedTests: [{ id: 't1', name: 'coversRetry()', direct: false, reason: 'Inferred from a dependency.', evidenceIds: ['t1'] }],
      relatedIncidents: [{ id: 'incident-1', title: 'Duplicate ledger entries', summary: 'Synthetic example', synthetic: true, sourceType: 'SYNTHETIC' }],
      evidence: [{
        id: 'cause',
        incidentId: 'incident-1',
        evidenceType: 'HUMAN_REVIEWED_FINDING',
        relationship: 'SUPPORTS_CAUSE',
        verificationStatus: 'UNREVIEWED',
        confidence: 'MEDIUM',
        synthetic: true,
        summary: 'Synthetic hypothesis',
      }],
      uncertainties: ['AI-assisted reasoning was unavailable.'],
      investigationSummary: [{ tool: 'trace_function_dependencies', summary: 'Traced 1 potentially impacted function.' }],
      agentStatus: 'DETERMINISTIC_FALLBACK',
    }
    render(<AgentReportView report={report} />)
    expect(screen.getByText('HYPOTHESIS')).toBeInTheDocument()
    expect(screen.getByText('DETERMINISTIC_FALLBACK')).toBeInTheDocument()
    expect(screen.getByText('SYNTHETIC')).toBeInTheDocument()
    expect(screen.getByText(/Traced 1 potentially impacted function/)).toBeInTheDocument()
    expect(screen.getByText(/Inferred from a dependency/)).toBeInTheDocument()
  })
})
