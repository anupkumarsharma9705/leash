import { useState, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/useAuth'
import { BrandMark } from '../components/BrandMark'
import { HealthIndicator } from '../components/HealthIndicator'
import { ThemeToggle } from '../components/ThemeToggle'

function signInMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) {
      return 'That username and password did not match the admin credentials.'
    }
    if (error.status === 403) {
      return 'The CSRF handshake failed. Reload the page and try again.'
    }
    return error.detail
  }
  return error instanceof Error ? error.message : 'Sign in failed.'
}

export function SignInScreen() {
  const { signIn } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setPending(true)
    setError(null)
    try {
      await signIn(username.trim(), password)
    } catch (cause) {
      setError(signInMessage(cause))
    } finally {
      setPending(false)
    }
  }

  return (
    <div className="signin">
      <header className="signin__top">
        <div className="brand">
          <BrandMark />
          <span className="brand__name">Leash</span>
          <span className="brand__tag">spend control</span>
        </div>
        <div className="topbar__right">
          <HealthIndicator />
          <ThemeToggle />
        </div>
      </header>

      <main className="signin__body">
        <section className="signin-card" aria-labelledby="signin-title">
          <span className="eyebrow">Operator access</span>
          <h1 id="signin-title">Put the agents on a leash.</h1>
          <p className="signin-card__lede">
            Review spend, release escalations, and stop an agent before it pays.
          </p>

          <form onSubmit={handleSubmit}>
            {error ? (
              <div className="alert alert--danger" role="alert">
                {error}
              </div>
            ) : null}

            <div className="field">
              <label className="field__label" htmlFor="admin-username">
                Username
              </label>
              <input
                id="admin-username"
                className="field__input"
                type="text"
                autoComplete="username"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                required
                autoFocus
              />
            </div>

            <div className="field">
              <label className="field__label" htmlFor="admin-password">
                Password
              </label>
              <input
                id="admin-password"
                className="field__input"
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                required
              />
            </div>

            <button
              type="submit"
              className="btn btn--primary btn--block"
              disabled={pending || username.trim() === '' || password === ''}
            >
              {pending ? 'Signing in…' : 'Sign in'}
            </button>
          </form>

          <p className="signin-card__note">
            Credentials are sent as HTTP Basic and stay in this tab&apos;s memory only. Every write
            is CSRF-protected.
          </p>
        </section>
      </main>

      <footer className="signin__footer">
        <span>PayPal sandbox</span>
        <span>No real money moves</span>
      </footer>
    </div>
  )
}
