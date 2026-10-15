import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { CurrentUser } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { AuthStage, ErrorText } from '../components/ui'

function EyeIcon({ off }: { off: boolean }) {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      {off ? (
        <>
          <path d="M3 3l18 18" />
          <path d="M10.6 10.6a2 2 0 0 0 2.8 2.8" />
          <path d="M9.9 5.2A10.8 10.8 0 0 1 12 5c5.2 0 9.2 4 10.4 6.6a12 12 0 0 1-2.2 3.2" />
          <path d="M6.1 6.1C4.3 7.4 2.9 9.2 2.2 11.4 3.4 14.2 7.2 19 12 19c1.5 0 2.9-.3 4.2-.9" />
        </>
      ) : (
        <>
          <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" />
          <circle cx="12" cy="12" r="3" />
        </>
      )}
    </svg>
  )
}

function PasswordField({
  id,
  label,
  value,
  onChange,
  autoComplete,
}: {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  autoComplete: string
}) {
  const [visible, setVisible] = useState(false)
  return (
    <div className="password-field">
      <label htmlFor={id}>{label}</label>
      <div className="password-control">
        <input
          id={id}
          type={visible ? 'text' : 'password'}
          autoComplete={autoComplete}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          required
        />
        <button
          type="button"
          className="password-toggle"
          aria-label={visible ? `Hide ${label.toLowerCase()}` : `Show ${label.toLowerCase()}`}
          aria-pressed={visible}
          onClick={() => setVisible((current) => !current)}
        >
          <EyeIcon off={visible} />
        </button>
      </div>
    </div>
  )
}

export function LoginPage() {
  const { user, ready, refresh } = useAuth()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  if (ready && user) return <Navigate to="/" replace />

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (!email.includes('@') || password.length === 0) {
      setError('Enter the email and password for your account.')
      return
    }
    setPending(true)
    try {
      await api<CurrentUser>('/api/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password }),
      })
      await refresh()
      navigate('/')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Login failed.')
    } finally {
      setPending(false)
    }
  }

  return (
    <AuthStage>
    <form className="auth-form" noValidate onSubmit={onSubmit}>
      <h1>Log in</h1>
      <p className="muted">See what a public code change could affect before you merge it.</p>
      <ErrorText error={error} />
      <label>
        Email
        <input type="email" autoComplete="username" value={email} onChange={(event) => setEmail(event.target.value)} required />
      </label>
      <PasswordField
        id="login-password"
        label="Password"
        value={password}
        onChange={setPassword}
        autoComplete="current-password"
      />
      <button className="button" type="submit" disabled={pending}>
        {pending ? 'Signing in…' : 'Log in'}
      </button>
      <Link to="/register">Create an account</Link>
    </form>
    </AuthStage>
  )
}

export function RegisterPage() {
  const { user, ready, refresh } = useAuth()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirmation, setPasswordConfirmation] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  if (ready && user) return <Navigate to="/" replace />

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (password !== passwordConfirmation) {
      setError('Password confirmation does not match.')
      return
    }
    if (password.length < 10 || !/[A-Za-z]/.test(password) || !/\d/.test(password)) {
      setError('Use at least 10 characters with one letter and one digit.')
      return
    }
    setPending(true)
    try {
      await api<CurrentUser>('/api/auth/register', {
        method: 'POST',
        body: JSON.stringify({ email, password, passwordConfirmation }),
      })
      await refresh()
      navigate('/')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Registration failed.')
    } finally {
      setPending(false)
    }
  }

  return (
    <AuthStage>
    <form className="auth-form" noValidate onSubmit={onSubmit}>
      <h1>Create account</h1>
      <p className="muted">A new account does not gain access to anyone else’s projects.</p>
      <ErrorText error={error} />
      <label>
        Email
        <input type="email" autoComplete="username" value={email} onChange={(event) => setEmail(event.target.value)} required />
      </label>
      <PasswordField
        id="register-password"
        label="Password"
        value={password}
        onChange={setPassword}
        autoComplete="new-password"
      />
      <p className="hint">At least 10 characters, including one letter and one digit.</p>
      <PasswordField
        id="register-password-confirmation"
        label="Confirm password"
        value={passwordConfirmation}
        onChange={setPasswordConfirmation}
        autoComplete="new-password"
      />
      <button className="button" type="submit" disabled={pending}>
        {pending ? 'Creating account…' : 'Create account'}
      </button>
      <Link to="/login">Already have an account?</Link>
    </form>
    </AuthStage>
  )
}
