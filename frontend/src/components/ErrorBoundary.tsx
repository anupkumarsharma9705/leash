import { Component, type ReactNode, type ErrorInfo } from 'react'

interface ErrorBoundaryState {
  hasError: boolean
  error: Error | null
  errorInfo: ErrorInfo | null
}

/**
 * Catches render errors in the subtree and displays a recovery UI.
 * The console stays usable: other sections keep working, and the user can
 * navigate away from the broken panel without a full reload.
 */
export class ErrorBoundary extends Component<
  { children: ReactNode; fallback?: ReactNode },
  ErrorBoundaryState
> {
  state: ErrorBoundaryState = { hasError: false, error: null, errorInfo: null }

  static getDerivedStateFromError(error: Error): Partial<ErrorBoundaryState> {
    return { hasError: true, error }
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo): void {
    this.setState({ error, errorInfo })
    // Log to console for debugging; in production you'd send to an error service
    console.error('ErrorBoundary caught:', error, errorInfo)
  }

  private handleReload = (): void => {
    this.setState({ hasError: false, error: null, errorInfo: null })
  }

  render(): ReactNode {
    if (this.state.hasError) {
      if (this.props.fallback) {
        return this.props.fallback
      }
      return (
        <div className="panel" role="alert">
          <div className="panel__header">
            <h2 className="panel__title">Something went wrong</h2>
          </div>
          <div className="state state--error">
            <span className="state__mark state__mark--error" aria-hidden="true">
              !
            </span>
            <h3>This panel failed to render</h3>
            <p>
              {this.state.error?.message ?? 'An unexpected error occurred.'}
            </p>
            {this.state.errorInfo?.componentStack ? (
              <details className="error-details">
                <summary>Error details (for debugging)</summary>
                <pre>{this.state.errorInfo.componentStack}</pre>
              </details>
            ) : null}
            <button type="button" className="btn btn--primary" onClick={this.handleReload}>
              Try again
            </button>
          </div>
        </div>
      )
    }

    return this.props.children
  }
}