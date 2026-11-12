import { useState } from 'react'
import { useAuth } from './auth/useAuth'
import { SignInScreen } from './auth/SignInScreen'
import { AppShell, type ConsoleSection } from './components/AppShell'
import { AgentsPanel } from './features/agents/AgentsPanel'
import { ApprovalsPanel } from './features/approvals/ApprovalsPanel'
import { DemoPanel } from './features/demo/DemoPanel'
import { LedgerPanel } from './features/ledger/LedgerPanel'
import { ErrorBoundary } from './components/ErrorBoundary'

export default function App() {
  const { username } = useAuth()
  const [section, setSection] = useState<ConsoleSection>('ledger')

  if (!username) {
    return <SignInScreen />
  }

  return (
    <AppShell section={section} onSectionChange={setSection}>
      {section === 'ledger' ? (
        <ErrorBoundary>
          <LedgerPanel />
        </ErrorBoundary>
      ) : null}
      {section === 'approvals' ? (
        <ErrorBoundary>
          <ApprovalsPanel />
        </ErrorBoundary>
      ) : null}
      {section === 'agents' ? (
        <ErrorBoundary>
          <AgentsPanel />
        </ErrorBoundary>
      ) : null}
      {section === 'demo' ? (
        <ErrorBoundary>
          <DemoPanel />
        </ErrorBoundary>
      ) : null}
    </AppShell>
  )
}
