#!/usr/bin/env bash
set -uo pipefail

# Startet EINE Claude-Code-Session, die aus einem Wunsch eine Story Card macht.
# Aufgerufen wird das Skript vom Kalorienzaehler (Feature Requests); der Prompt
# kommt ueber die Standardeingabe, das Ergebnis geht nach stdout.
#
# Dieselbe Bauart wie deploy/agent/run-agent.sh (Schnellerfassung): Claude Code
# laedt CLAUDE.md und .claude/settings.json aus dem ARBEITSVERZEICHNIS, und das
# Verzeichnis liegt ausserhalb des Git-Repos - so kann weder ein Deploy noch der
# Agent selbst die Rechte verschieben. Ein eigenes Verzeichnis statt des
# Schnellerfassungs-Agenten, weil dessen CLAUDE.md ganz auf Mahlzeiten
# zugeschnitten ist und er im Netz nachschlagen darf. Dieser hier darf nichts.

AGENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PATH="$HOME/.local/bin:$PATH"

# Sonnet als Vorgabe: aus einem krummen Satz eine brauchbare Karte zu machen ist
# der ganze Zweck, und es passiert selten. Umstellbar ueber STORY_AGENT_MODEL in
# /etc/food.env - sudo reicht genau diese Variable durch (deploy/sudoers-story-agent).
# Weil sie damit von aussen kommt, nur in der Form eines Modellnamens: alles andere
# koennte sich als weitere Option ausgeben.
MODEL="${STORY_AGENT_MODEL:-claude-sonnet-5}"
if [[ ! "$MODEL" =~ ^[A-Za-z0-9][A-Za-z0-9._:@-]*$ ]]; then
    echo "STORY_AGENT_MODEL ungueltig, nehme claude-sonnet-5" >&2
    MODEL="claude-sonnet-5"
fi

cd "$AGENT_DIR" || { echo "Agent-Verzeichnis nicht erreichbar" >&2; exit 1; }

command -v claude >/dev/null 2>&1 || { echo "claude nicht im PATH" >&2; exit 1; }

# Fuer die Probe in setup-feature-requests.sh: beweist, dass sudo, Nutzer und
# Binary zusammenpassen, ohne eine (kostende) Session zu starten.
if [[ "${1:-}" == "--version" ]]; then
    exec claude --version
fi

# --tools "" nimmt der Session jedes Werkzeug schon beim Start; die deny-Liste in
# .claude/settings.json ist die zweite Schranke dahinter. Nebenbei entfallen die
# Werkzeugbeschreibungen im Systemkontext jeder frischen Session.
# --strict-mcp-config ohne --mcp-config laedt keinen einzigen MCP-Server.
# --permission-mode default heisst im headless-Betrieb: was nicht erlaubt ist,
# wird abgelehnt statt nachgefragt. --output-format json legt einen Umschlag um
# die Antwort; die App kommt mit beidem klar.
exec claude -p \
    --model "$MODEL" \
    --tools "" \
    --strict-mcp-config \
    --permission-mode default \
    --output-format json
