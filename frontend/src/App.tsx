import { Navigate, Route, Routes } from 'react-router-dom'
import { AccountPanel, RequireAuth, Shell } from './components/ui'
import { LoginPage, RegisterPage } from './pages/AuthPages'
import { DashboardPage, NewProjectPage } from './pages/DashboardPage'
import { ActivityPage } from './pages/ActivityPage'
import { IncidentsPage } from './pages/IncidentsPage'
import { AnalysisPage, GraphPage, IngestionPage, ProjectOverviewPage, ProjectSettingsPage, RepositoryPage } from './pages/ProjectPages'

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route element={<Shell />}>
        <Route element={<RequireAuth />}>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/projects/new" element={<NewProjectPage />} />
          <Route path="/projects/:projectId" element={<ProjectOverviewPage />} />
          <Route path="/projects/:projectId/settings" element={<ProjectSettingsPage />} />
          <Route path="/projects/:projectId/activity" element={<ActivityPage />} />
          <Route path="/projects/:projectId/repository" element={<RepositoryPage />} />
          <Route path="/projects/:projectId/graph" element={<GraphPage />} />
          <Route path="/projects/:projectId/ingestions" element={<IngestionPage />} />
          <Route path="/projects/:projectId/analysis" element={<AnalysisPage />} />
          <Route path="/projects/:projectId/incidents" element={<IncidentsPage />} />
          <Route path="/account" element={<AccountPanel />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}
