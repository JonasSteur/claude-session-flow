---
name: handoff
description: Hand the next piece of work to a fresh session. Writes a start prompt, saves memories and opens the new session in an iTerm2 tab.
argument-hint: "[what the next session should do; add \"and close\" to close this one]"
---

# Hand off to a new session

Notes from the user: $ARGUMENTS

1. Decide what the follow-up session should do, from the notes and the conversation. One session per ticket or per separate investigation: when there are several, prepare one for each. If it is unclear what the next session should do, ask before writing anything.
2. For each follow-up, write a start prompt to `~/.claude/handoffs/<YYYY-MM-DD>-<topic>/<name>.md` (example: `~/.claude/handoffs/2026-10-23-search-performance/PROJ-123-slow-queries.md`). The new session knows nothing of this conversation, so the file has to stand on its own:
   - A heading with the ticket key and what the session does, then the ticket link.
   - State on <date>: branch, worktree path, commits, PR, what is tested or deployed.
   - The problem or goal, and the decisions already made with their reason.
   - Constraints: read-only or not, production rules, what the user runs themselves.
   - Numbered next steps.
   - Related memory files.
   Move scratchpad files the new session needs into the same folder and refer to them by absolute path. Keep it plain; leave out how we got here.
3. Decide whether the follow-up work needs an advisor, using the list in step 1 of `~/.claude/skills/advisor-session/SKILL.md`. There are three outcomes:
   - **No advisor** (most work): add nothing.
   - **Built-in advisor**: one follow-up with some hard judgment calls. Add nothing to the start prompt; tell the user in step 6 to type `/advisor <model>` with a stronger model in the new session.
   - **Advisor session**: several follow-ups that have to agree, or choices that are expensive to undo. Don't start one on your own: recommend it in step 6 with the reason in one line. Start it only when the notes ask for it.

   When you start an advisor session or one is already running for this work (check with `ListAgents`), paste the block from "Rules for the workers" in that same file into each start prompt, with the advisor's session name filled in.

   To start a new advisor session, write its start prompt in the same folder: the names of the follow-up sessions, the goal and limits the user gave, the paths of their start prompts, and the line "Follow `~/.claude/skills/advisor-session/SKILL.md`." Title it `<TICKET>: advisor`, or `<topic>: advisor` with the topic of the handoff folder when the follow-ups span several tickets. In step 5, start it before the follow-ups, so it is there when the first one reaches a checkpoint. It opens on the default model and asks the user to confirm or switch the model before it reads anything.
4. Save what is worth keeping in memory, following the memory rules in the system prompt: where this session's ticket or topic stands, and new feedback. Don't copy the start prompt into memory.
5. Start each follow-up with `mcp__session-flow__start_session`:
   - `title`: `<TICKET>: <2 to 6 lowercase words>`, or only the words without a ticket. Example: `PROJ-123: add retries to the export job`.
   - `prompt_file`: the absolute path of the file from step 2.
   - `directory`: where the work happens. The branch's worktree when there is one (`git worktree list` shows its path), otherwise the repository root.
6. Tell the user in at most four lines which sessions you started and where their prompt files are, plus one line on the advisor when step 3 gave something other than "no advisor". When the tool says the PyCharm plugin did not open the tab, it put the start command on the clipboard: say why, and with several follow-ups, show each start command since the clipboard holds only the last one.
7. Only when the user typed `/handoff` themselves and the notes ask to close this session too (otherwise remind them in one line to type `/done`): do steps 2 and 3 of `~/.claude/skills/done/SKILL.md` (loose ends), then call `mcp__session-flow__end_session`.
