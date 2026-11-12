declare module 'claude-code' {
  interface PluginState {
    'session-flow': {
      isNamed: boolean
      closingCommand: string
      isEndRequested: boolean
      role: '' | 'advisor' | 'worker'
      appliedColors: string
      isTabColored: boolean
    }
  }
}
