import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  readonly children: ReactNode
  readonly onReset?: () => void
}

interface State { readonly failed: boolean }

/** Intentionally never renders, records or logs the caught error object. */
export class ApplicationErrorBoundary extends Component<Props, State> {
  state: State = { failed: false }

  static getDerivedStateFromError(): State { return { failed: true } }

  componentDidCatch(_error: Error, _info: ErrorInfo): void {
    // Deliberately no raw error logging: render errors may contain private data.
  }

  private reset = (): void => {
    this.setState({ failed: false })
    if (this.props.onReset) this.props.onReset()
    else globalThis.location.reload()
  }

  render(): ReactNode {
    if (!this.state.failed) return this.props.children
    return <main role="alert">
      <h1>Something went wrong</h1>
      <p>This view could not be displayed safely. Please try again.</p>
      <button type="button" onClick={this.reset}>Try again</button>
    </main>
  }
}
