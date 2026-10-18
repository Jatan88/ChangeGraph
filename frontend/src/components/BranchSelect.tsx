import { useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { BranchList, BranchSelection, RepositorySummary } from '../api/types'
import { ErrorText } from './ui'

export function BranchSelect({
  projectId,
  repository,
  onChange,
}: {
  projectId: string
  repository: RepositorySummary
  onChange?: (repository: RepositorySummary) => void
}) {
  const [branches, setBranches] = useState<BranchList | null>(null)
  const [selected, setSelected] = useState(repository.selectedBranch || repository.defaultBranch)
  const [savedBranch, setSavedBranch] = useState(repository.selectedBranch || repository.defaultBranch)
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    api<BranchList>(`/api/projects/${projectId}/branches`)
      .then((value) => {
        if (!cancelled) {
          setBranches(value)
          setSelected(value.selectedBranch)
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof ApiError ? err.message : 'Could not load branches.')
      })
    return () => {
      cancelled = true
    }
  }, [projectId])

  async function save() {
    setPending(true)
    setError(null)
    setNotice(null)
    try {
      const result = await api<BranchSelection>(`/api/projects/${projectId}/repository`, {
        method: 'PATCH',
        body: JSON.stringify({ branch: selected }),
      })
      onChange?.(result.repository)
      setSavedBranch(result.repository.selectedBranch || result.repository.defaultBranch)
      setNotice(result.workflowId
        ? 'Selected branch saved. Re-indexing that branch started.'
        : 'Selected branch saved.')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not change the branch.')
    } finally {
      setPending(false)
    }
  }

  return (
    <article className="card form">
      <h2>Branch</h2>
      <p className="muted">
        Selected branch {repository.selectedBranch || repository.defaultBranch}. Repository default branch {repository.defaultBranch}.
        Synchronization and commit tracking use the selected branch. Pull requests keep their own source and target branches.
      </p>
      <ErrorText error={error} />
      {notice && <p className="banner">{notice}</p>}
      {!branches && !error && <div className="skeleton" role="status">Loading branches…</div>}
      {branches && (
        <>
          <label>
            Selected branch
            <select value={selected} onChange={(event) => setSelected(event.target.value)}>
              {branches.branches.map((branch) => (
                <option key={branch.name} value={branch.name}>
                  {branch.name}{branch.repositoryDefault ? ' · default' : ''}{branch.selected ? ' · selected' : ''}
                </option>
              ))}
            </select>
          </label>
          {branches.truncated && <p className="hint">GitHub returned the first 100 branches.</p>}
          <button className="button secondary" type="button" onClick={save} disabled={pending || selected === savedBranch}>
            {pending ? 'Saving…' : 'Use this branch'}
          </button>
        </>
      )}
    </article>
  )
}
