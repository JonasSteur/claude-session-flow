---
name: restore
description: Reopen Claude Code sessions that were killed, for example by an IDE restart, each in a new terminal tab. Use when the user asks to restore or reopen the sessions that were open before a restart.
argument-hint: "[which sessions, when not the last batch]"
---

# Reopen killed sessions

Notes from the user: $ARGUMENTS

1. Run `python3 -I ~/.claude/skills/restore/find_closed_sessions.py`. It lists, newest first, the sessions that stopped without `/exit` or `/done` and are not running now: `session_id`, `title`, `directory`, `closed_at` and `closed_with_newest` (stopped within two minutes of the newest one, so most likely in the same restart).
2. Reopen the sessions with `closed_with_newest` true, unless the notes name other ones. When older ones are listed too, name them in one line and ask before reopening them.
3. Reopen each with `mcp__session-flow__resume_session`: `title` is the session's title (`resume <first 8 characters of the id>` when it has none), plus its `session_id` and `directory`.
4. Tell the user in a few lines which sessions reopened, and why any did not.
