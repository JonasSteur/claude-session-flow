---
name: done
description: Close this session. Saves what is worth keeping, checks for loose ends, then ends the session.
argument-hint: "[last steps or extra context]"
disable-model-invocation: true
---

# Close this session

Notes from the user: $ARGUMENTS

Do what the notes ask first (extra context to save, remove a worktree, ...). Then:

1. Save what is worth keeping, following the memory rules in the system prompt.
   - Update the memory of the ticket or topic this session worked on: where it stands now (PR number and status, merged, deployed, backfill done) with absolute dates, and its line in `MEMORY.md`.
   - Save feedback the user gave in this session, and facts the code and git history don't hold.
   - Update or delete memories this session proved wrong. Don't add what is already there.
   - Scratchpad files that are still needed (scripts, query results) disappear with the session. Move them to `~/.claude/handoffs/<YYYY-MM-DD>-<topic>/` and name that path in the memory.
2. Check every repository and worktree this session touched for uncommitted changes, unpushed commits and stashes. Only report them. Don't commit, push, delete, comment on Jira or move tickets unless the notes ask for it.
3. If something would get lost or needs the user's decision (uncommitted work, an open question, a production step that was promised but not done), stop: list it and ask. Don't close the session.
4. Otherwise write the closing message: what you saved (file names) and what is still open, at most five lines. Then call `mcp__session-flow__end_session`. The session closes when this turn ends.
