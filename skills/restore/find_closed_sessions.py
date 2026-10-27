import json
import os
import time
from datetime import datetime
from pathlib import Path
from typing import Any

CLAUDE_DIRECTORY = Path.home() / '.claude'
OPEN_SESSIONS_DIRECTORY = CLAUDE_DIRECTORY / 'session-flow' / 'open'
LIVE_SESSIONS_DIRECTORY = CLAUDE_DIRECTORY / 'sessions'
TRANSCRIPTS_DIRECTORY = CLAUDE_DIRECTORY / 'projects'
FORGET_AFTER_SECONDS = 7 * 86400
CLOSED_TOGETHER_SECONDS = 120


def is_process_running(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def read_live_session_ids() -> set[str]:
    live_session_ids = set()
    for registry_file in LIVE_SESSIONS_DIRECTORY.glob('*.json'):
        try:
            registry = json.loads(registry_file.read_text())
        except (OSError, ValueError):
            continue
        if is_process_running(registry.get('pid', 0)):
            live_session_ids.add(registry.get('sessionId'))
    return live_session_ids


def read_transcript_details(transcript: Path) -> tuple[str, str]:
    title = ''
    directory = ''
    with transcript.open() as lines:
        for line in lines:
            try:
                record = json.loads(line)
            except ValueError:
                continue
            if record.get('type') == 'custom-title':
                title = record.get('customTitle', '')
            if not directory and record.get('cwd'):
                directory = record['cwd']
    return title, directory


def main() -> None:
    live_session_ids = read_live_session_ids()
    closed_sessions: list[dict[str, Any]] = []
    for marker in OPEN_SESSIONS_DIRECTORY.glob('*'):
        session_id = marker.name
        if session_id in live_session_ids:
            continue
        transcripts = list(TRANSCRIPTS_DIRECTORY.glob(f'*/{session_id}.jsonl'))
        closed_at = max([marker.stat().st_mtime] + [transcript.stat().st_mtime for transcript in transcripts])
        if not transcripts or time.time() - closed_at > FORGET_AFTER_SECONDS:
            marker.unlink()
            continue
        title, directory = read_transcript_details(transcripts[0])
        closed_sessions.append(
            {
                'session_id': session_id,
                'title': title,
                'directory': directory,
                'closed_at': closed_at,
            }
        )

    closed_sessions.sort(key=lambda session: session['closed_at'], reverse=True)
    newest_closed_at = closed_sessions[0]['closed_at'] if closed_sessions else 0
    for session in closed_sessions:
        session['closed_with_newest'] = newest_closed_at - session['closed_at'] <= CLOSED_TOGETHER_SECONDS
        session['closed_at'] = datetime.fromtimestamp(session['closed_at']).isoformat(timespec='seconds')
    print(json.dumps(closed_sessions, indent=2))


if __name__ == '__main__':
    main()
