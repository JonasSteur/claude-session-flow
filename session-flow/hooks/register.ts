import { atom, read, update } from 'claude-code'
import type { EngineInterface, Register } from 'claude-code'

const START_SESSION_TOOL = 'mcp__session-flow__start_session'
const RESUME_SESSION_TOOL = 'mcp__session-flow__resume_session'
const END_SESSION_TOOL = 'mcp__session-flow__end_session'
const CLOSING_COMMANDS = ['done', 'handoff']
const TITLE_MODEL = 'haiku'
const TITLE_TIMEOUT_MS = 20_000
const MAX_TITLE_LENGTH = 60
const MAX_TITLE_WORDS = 10
const MAX_TITLE_CONTEXT_CHARACTERS = 3000

const isNamed = atom({ plugin: 'session-flow', key: 'isNamed' } as const, false)
const closingCommand = atom({ plugin: 'session-flow', key: 'closingCommand' } as const, '')
const isEndRequested = atom({ plugin: 'session-flow', key: 'isEndRequested' } as const, false)

const TITLE_INSTRUCTIONS = `You name Claude Code work sessions. You get the user's first message and the assistant's first answer, and reply with a short title for the session. Never summarize the conversation.

Rules:
- When the work is about one Jira ticket (a key like PROJ-123), start with the key, a colon and a space.
- Then 2 to 6 lowercase words saying what the session does.
- Without a ticket, only the words.
- Answer with the title alone: no quotes, no explanation, at most 60 characters.

Good titles:
PROJ-123: add retries to the export job
PROJ-456: fix timezone in daily report
PROJ-789: rename the order status field
investigate slow search queries
claude code session commands`

const PYCHARM_TERMINAL = 'JetBrains-JediTerm'
const PYCHARM_REQUEST_TIMEOUT_MS = 20_000

const RECORD_OPEN_SESSION = 'mkdir -p "$HOME/.claude/session-flow/open" && touch "$HOME/.claude/session-flow/open/$1"'
const REFRESH_OPEN_SESSION = '[ ! -f "$HOME/.claude/session-flow/open/$1" ] || touch "$HOME/.claude/session-flow/open/$1"'
const FORGET_OPEN_SESSION = 'rm -f "$HOME/.claude/session-flow/open/$1"'

const REQUEST_PYCHARM_TAB = `requests="$HOME/.claude/pycharm-session-requests"
mkdir -p "$requests"
cat > "$requests/$1.tmp" && mv "$requests/$1.tmp" "$requests/$1.json"
for attempt in $(seq 1 40); do
  if [ -f "$requests/$1.result" ]; then
    cat "$requests/$1.result"
    rm -f "$requests/$1.result"
    exit 0
  fi
  sleep 0.25
done
if rm "$requests/$1.json" 2>/dev/null; then
  echo 'no answer within 10 seconds, the plugin is not running'
else
  echo 'the plugin took the request but did not answer within 10 seconds, the tab may still open'
fi`

const OPEN_ITERM_TAB =`on run argv
  tell application "iTerm2"
    if (count of windows) is 0 then
      set newWindow to (create window with default profile)
      tell current session of newWindow to write text (item 1 of argv)
    else
      tell current window
        set newTab to (create tab with default profile)
        tell current session of newTab to write text (item 1 of argv)
      end tell
    end if
  end tell
end run`

type StartSessionInput = { title: string; prompt_file: string; directory: string }
type ResumeSessionInput = { title: string; session_id: string; directory: string }
type SessionTab = { title: string; directory: string; command: string; request: Record<string, string> }

const quoteForShell = (text: string): string => `'${text.replaceAll("'", `'\\''`)}'`

const FIND_LAST_TITLE = 'grep -h \'"type":"custom-title"\' "$HOME"/.claude/projects/*/"$1".jsonl | tail -n 1'

const readSessionTitle = async ($: EngineInterface): Promise<string> => {
  const sessionId = await $.session.id()
  const found = await $.process.run(['sh', '-c', FIND_LAST_TITLE, 'sh', sessionId])
  const lastTitleRecord = found.stdout.trim()
  if (!lastTitleRecord) return ''

  return (JSON.parse(lastTitleRecord) as { customTitle?: string }).customTitle ?? ''
}

const nameSession = async ($: EngineInterface, answer: string): Promise<void> => {
  await update($, isNamed, () => true)
  if (await readSessionTitle($)) return

  const messages = await $.session.messages()
  const firstRequest = messages
    .filter(message => message.role === 'user' && message.text.trim())
    .slice(0, 2)
    .map(message => message.text)
    .join('\n\n')
  const conversation =
    `<user>\n${firstRequest.slice(0, MAX_TITLE_CONTEXT_CHARACTERS)}\n</user>\n\n` +
    `<assistant>\n${answer.slice(0, MAX_TITLE_CONTEXT_CHARACTERS)}\n</assistant>\n\n` +
    'Reply with the title only, at most 6 words after the ticket key.'

  let rejectedTitle = ''
  for (const prompt of [conversation, `${conversation}\n\nYour last reply was too long to be a title.`]) {
    const completion = await $.model.complete({
      model: TITLE_MODEL,
      system: TITLE_INSTRUCTIONS,
      prompt,
      effort: 'low',
      timeoutMs: TITLE_TIMEOUT_MS,
    })
    if (!completion.isAnswered) {
      $.ui.log(`session-flow: no session title, the model call ended with ${completion.reason}`)
      return
    }
    const title = (completion.text.trim().split('\n')[0] ?? '').replace(/^["'`]+|["'`.]+$/g, '').trim()
    if (title && title.length <= MAX_TITLE_LENGTH && title.split(/\s+/).length <= MAX_TITLE_WORDS) {
      await $.command.run({ command: 'rename', args: title })
      return
    }
    rejectedTitle = title
  }
  $.ui.log(`session-flow: no session title, the model kept answering with a summary: ${rejectedTitle}`)
}

const openSessionTab = async ($: EngineInterface, tab: SessionTab) => {
  const terminal = await $.process.run(['sh', '-c', 'printf %s "$TERMINAL_EMULATOR"'])
  if (terminal.stdout === PYCHARM_TERMINAL) {
    const requestId = `${await $.clock.now()}-${Math.random().toString(36).slice(2, 8)}`
    const requested = await $.process.run(['sh', '-c', REQUEST_PYCHARM_TAB, 'sh', requestId], {
      stdin: JSON.stringify(tab.request),
      timeoutMs: PYCHARM_REQUEST_TIMEOUT_MS,
    })
    const answer = requested.stdout.trim() || requested.stderr.trim()
    if (answer.startsWith('ok')) return { result: `Opened "${tab.title}" in a new PyCharm terminal tab, ${answer}` }

    const copied = await $.process.run(['pbcopy'], { stdin: tab.command })
    if (copied.exitCode !== 0) return { deny: `Could not copy the command: ${copied.stderr.trim()}` }

    return {
      result:
        `The Claude Sessions PyCharm plugin did not open "${tab.title}" (${answer}). ` +
        `The command is on the clipboard: ${tab.command}\n` +
        'Tell the user why, and to open a new tab in the PyCharm terminal (Cmd+T there), paste and press Enter.',
    }
  }

  const opened = await $.process.run(['osascript', '-e', OPEN_ITERM_TAB, tab.command])
  if (opened.exitCode !== 0) return { deny: `Could not open an iTerm2 tab: ${opened.stderr.trim()}` }

  return { result: `Opened "${tab.title}" in a new iTerm2 tab: ${tab.command}` }
}

export const register: Register = on => {
  on('session.start', async ($, e, next) => {
    if (e.isInteractive) {
      const sessionId = await $.session.id()
      await $.process.run(['sh', '-c', RECORD_OPEN_SESSION, 'sh', sessionId])
    }
    await $.tool.register({
      name: 'start_session',
      description:
        'Starts a new Claude Code session that begins by reading a prompt file: in a new iTerm2 tab, ' +
        'or, when this session runs in the PyCharm terminal, in a new PyCharm terminal tab (clipboard as fallback). ' +
        'Use only from the /handoff routine, after writing the prompt file.',
      inputSchema: {
        type: 'object',
        properties: {
          title: {
            type: 'string',
            description: 'Session name, like "PROJ-123: add retries to the export job"',
          },
          prompt_file: { type: 'string', description: 'Absolute path of the start prompt file' },
          directory: { type: 'string', description: 'Absolute path the new session starts in' },
        },
        required: ['title', 'prompt_file', 'directory'],
      },
      isDeferred: false,
    })
    await $.tool.register({
      name: 'end_session',
      description:
        'Closes this Claude Code session once the current turn ends. ' +
        'Use only as the last step of /done, or of /handoff when the user asked to close this session. ' +
        'When the user asks in plain words to close or end the session, don\'t call it: ' +
        'remind them in one line to type /done.',
      isDeferred: false,
    })
    await $.tool.register({
      name: 'resume_session',
      description:
        'Reopens an earlier Claude Code session with claude --resume, in a new iTerm2 tab, ' +
        'or, when this session runs in the PyCharm terminal, in a new PyCharm terminal tab (clipboard as fallback). ' +
        'Use only from the /restore routine.',
      inputSchema: {
        type: 'object',
        properties: {
          title: { type: 'string', description: 'Name of the tab: the session name' },
          session_id: { type: 'string', description: 'The id of the session to resume' },
          directory: { type: 'string', description: 'Absolute path the session started in' },
        },
        required: ['title', 'session_id', 'directory'],
      },
      isDeferred: false,
    })

    return next(e)
  })

  on('command.run', async ($, e, next) => {
    const isTypedByUser = e.origin.kind === 'composer' || e.origin.kind === 'bridge'
    if (isTypedByUser && CLOSING_COMMANDS.includes(e.command)) await update($, closingCommand, () => e.command)

    return next(e)
  }).catch(($, e, next) => next(e))

  on('tool.call', { tool: START_SESSION_TOOL }, async ($, e) => {
    const { title, prompt_file: promptFile, directory } = e as unknown as StartSessionInput
    const command = `cd ${quoteForShell(directory)} && claude -n ${quoteForShell(title)} ${quoteForShell(
      `Read ${promptFile} and follow it.`,
    )}`

    return openSessionTab($, { title, directory, command, request: { title, prompt_file: promptFile, directory } })
  }).catch(() => ({ deny: 'session-flow: start_session failed.' }))

  on('tool.call', { tool: RESUME_SESSION_TOOL }, async ($, e) => {
    const { title, session_id: sessionId, directory } = e as unknown as ResumeSessionInput
    const command = `cd ${quoteForShell(directory)} && claude --resume ${quoteForShell(sessionId)}`

    return openSessionTab($, { title, directory, command, request: { title, session_id: sessionId, directory } })
  }).catch(() => ({ deny: 'session-flow: resume_session failed.' }))

  on('tool.call', { tool: END_SESSION_TOOL }, async $ => {
    if (!(await read($, closingCommand))) {
      return {
        deny:
          'end_session only works after the user typed /done or /handoff in this session. ' +
          'Remind the user in one line to type /done; they are building the habit.',
      }
    }
    await update($, isEndRequested, () => true)

    return { result: 'This session closes when this turn ends. Finish with a short summary.' }
  }).catch(() => ({ deny: 'session-flow: end_session failed.' }))

  on('turn.complete', async ($, e, next) => {
    const completed = await next(e)
    if (e.agentId !== undefined) return completed

    if ((await $.session.surfaces()).length > 0) {
      const sessionId = await $.session.id()
      $.process.run(['sh', '-c', RECORD_OPEN_SESSION, 'sh', sessionId]).catch(error =>
        $.ui.log(`session-flow: could not record the open session: ${error}`),
      )
    }
    if (await read($, isEndRequested)) {
      $.command.run({ command: 'exit' }).catch(error => $.ui.log(`session-flow: could not exit: ${error}`))
    } else if (e.reason === 'answer' && !(await read($, isNamed))) {
      nameSession($, e.answer).catch(error => $.ui.log(`session-flow: could not name the session: ${error}`))
    }

    return completed
  })

  on('session.end', async ($, e, next) => {
    const openSessionScript = e.reason === 'other' ? REFRESH_OPEN_SESSION : FORGET_OPEN_SESSION
    await $.process.run(['sh', '-c', openSessionScript, 'sh', e.sessionId])
    if (e.reason === 'clear') {
      await update($, isNamed, () => false)
      await update($, closingCommand, () => '')
      await update($, isEndRequested, () => false)
    }

    return next(e)
  })
}
