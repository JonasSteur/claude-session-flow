# claude-session-flow

Makes Claude Code sessions easier to start and close:

- **Session names**: after the first answer, an unnamed session gets a name like `PROJ-123: add retries to the export job`.
- **`/handoff [notes]`**: writes a start prompt for a follow-up session to `~/.claude/handoffs/`, saves memories and opens the new session in a terminal tab (iTerm2, or PyCharm with the plugin below).
- **`/restore`**: reopens the sessions that were killed together (for example by an IDE restart), each in a new tab with `claude --resume`. The mod keeps a file per open session in `~/.claude/session-flow/open/` and removes it when the session exits normally.
- **`/advisor-session <sessions and goal>`**: turns a session on a stronger model into an advisor for other sessions. The workers message it at fixed checkpoints (plan, before a push, before a PR); it reviews read-only and stays quiet in between. `/handoff` decides whether the follow-up work needs one and adds the checkpoints to the start prompts.
- **`/done [notes]`**: saves memories, checks for loose ends and closes the session. Claude can only close a session after you typed `/done` (or `/handoff ... and close`).

## What is in here

- `session-flow/`: the Claude Code mod (hooks module). It names sessions and gives Claude the `start_session`, `resume_session` and `end_session` tools.
- `skills/done`, `skills/handoff`, `skills/restore`, `skills/advisor-session`: the `/done`, `/handoff`, `/restore` and `/advisor-session` skills.
- `pycharm-sessions/`: a PyCharm plugin that opens a terminal tab for a new session. The mod drops a request in `~/.claude/pycharm-session-requests/`; the plugin opens a tab in the matching project and runs `claude -n '<title>' 'Read <prompt file> and follow it.'`, or `claude --resume <session id>` for `/restore`. Without the plugin, the start command goes to the clipboard.

## Install

The mod, in a Claude Code terminal session:

```
/plugin install session-flow --marketplace JonasSteur/claude-session-flow
```

Or load it from this checkout by adding it to the `env` block of `~/.claude/settings.json`:

```json
"CLAUDE_CODE_PLUGIN_DIRS": "~/.claude/mods/session-flow"
```

The skills:

```
ln -s ~/.claude/mods/skills/done ~/.claude/skills/done
ln -s ~/.claude/mods/skills/handoff ~/.claude/skills/handoff
ln -s ~/.claude/mods/skills/restore ~/.claude/skills/restore
ln -s ~/.claude/mods/skills/advisor-session ~/.claude/skills/advisor-session
```

The PyCharm plugin (builds against the installed `/Applications/PyCharm.app` and its bundled Java 21):

```
cd pycharm-sessions && gradle buildPlugin
```

Then in PyCharm: Settings, Plugins, the gear icon, Install Plugin from Disk, pick `pycharm-sessions/build/distributions/claude-sessions-<version>.zip`, and restart PyCharm.
