import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { IncidentSummary, Project } from '../api/types'
import { IncidentsPage } from './IncidentsPage'

const project: Project = {
  id: 'project-1',
  name: 'Payment Service',
  description: null,
  repositoryConnected: true,
  repository: null,
  graphStatus: 'READY',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  latestIngestion: null,
  latestAnalysis: null,
}

const incident: IncidentSummary = {
  id: 'outage-1',
  title: 'Retry storm',
  summary: 'Duplicate charges',
  sourceType: 'MANUAL',
  synthetic: false,
  evidenceCount: 1,
  updatedAt: '2026-10-18T10:00:00Z',
}

describe('IncidentsPage', () => {
  it('lists incidents and shows the selected detail', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/projects/project-1')) {
        return new Response(JSON.stringify(project), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      if (url.endsWith('/api/projects/project-1/incidents')) {
        return new Response(JSON.stringify([incident]), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      if (url.includes('/api/projects/project-1/incidents/outage-1')) {
        return new Response(JSON.stringify({
          ...incident,
          evidence: [{
            id: 'report-1',
            evidenceType: 'INCIDENT_REPORT',
            relationship: 'REPORTS_ON',
            verificationStatus: 'VERIFIED',
            confidence: 'HIGH',
            summary: 'On-call note',
            sourceType: 'MANUAL',
            synthetic: false,
            functionId: 'pay',
          }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      if (url.includes('/api/projects/project-1/graph')) {
        return new Response(JSON.stringify({ nodes: [], edges: [], truncated: false }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      return new Response(JSON.stringify({ code: 'UNEXPECTED', message: 'unexpected' }), { status: 500 })
    }))
    render(
      <MemoryRouter initialEntries={['/projects/project-1/incidents']}>
        <Routes>
          <Route path="/projects/:projectId/incidents" element={<IncidentsPage />} />
        </Routes>
      </MemoryRouter>,
    )
    expect(await screen.findByRole('heading', { name: 'Incidents' })).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: /Retry storm/ })).toBeInTheDocument()
    expect(await screen.findByText('On-call note')).toBeInTheDocument()
    vi.unstubAllGlobals()
  })
})
