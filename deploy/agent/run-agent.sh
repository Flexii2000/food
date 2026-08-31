#!/usr/bin/env bash
set -uo pipefail

# Startet EINE Claude-Code-Session, die aus einer Mahlzeitbeschreibung ein
# JSON-Objekt macht. Aufgerufen wird das Skript von der Food-App; der Prompt
# kommt über die Standardeingabe, das Ergebnis geht nach stdout.
#
# Warum ein Skript und nicht ein direkter Aufruf aus der App: Claude Code lädt
# CLAUDE.md und .claude/settings.json aus dem ARBEITSVERZEICHNIS. Das Verzeichnis
# ist damit die eigentliche Leitplanke - nicht der Prompt, den ein Nutzer
# beeinflussen kann. Deshalb liegt es auch außerhalb des Git-Repos: so kann
# weder ein Deploy noch der Agent selbst die Rechte verschieben.

AGENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PATH="$HOME/.local/bin:$PATH"

# Jede Anfrage ist eine frische Session, also entstehen jedes Mal rund 10k
# Tokens Systemkontext neu - das dominiert die Kosten, nicht der eigentliche
# Satz. Mit Sonnet sind das ~4,5 ct pro Eintrag, mit Haiku etwa ein Achtel
# davon. Sonnet als Vorgabe, weil das Schaetzen von Naehrwerten der ganze Zweck
# ist; umstellbar ueber die Umgebungsvariable, ohne das Skript anzufassen.
MODEL="${FOOD_AGENT_MODEL:-claude-sonnet-5}"

cd "$AGENT_DIR" || { echo "Agent-Verzeichnis nicht erreichbar" >&2; exit 1; }

command -v claude >/dev/null 2>&1 || { echo "claude nicht im PATH" >&2; exit 1; }

# --output-format json legt einen Umschlag um die Antwort; die App kommt mit
# beidem klar. --permission-mode default heißt hier: was nicht erlaubt ist,
# wird abgelehnt statt nachgefragt - im headless-Betrieb gibt es niemanden,
# der eine Rückfrage beantworten könnte. Erlaubt ist nichts (settings.json).
exec claude -p \
    --model "$MODEL" \
    --permission-mode default \
    --output-format json
