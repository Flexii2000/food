#!/usr/bin/env bash
set -euo pipefail

# Deployt eine neue Version des Kalorienzaehlers (food.fherrmann.com).
#
#     ~/scripts/update-food.sh
#
# Baut auf dem Server aus ~/services/food und installiert das Ergebnis nach
# /opt/food. Der Build laeuft bewusst als flexii, installiert wird ueber das
# Root-Skript update-service.sh - ohne Passwort, also auch fuer Agenten. NICHT mit
# sudo starten.
#
# /opt/food/data/ wird BEWUSST nicht angefasst: food.json ist die Live-Quelle,
# die die App selbst fortschreibt (Gerichte, Eintraege, Tagesziele).

BUILD_DIR="$HOME/services/food"
JAR="$BUILD_DIR/build/libs/Food-0.0.1-SNAPSHOT.jar"
JAVA="/opt/java/jdk-25.0.1+8/bin/java"
STAGING="$HOME/staging/food"
ROOT_SCRIPT="/usr/local/sbin/update-service.sh"

[[ $EUID -ne 0 ]] || { echo "Bitte OHNE sudo starten - das Skript ruft sudo selbst auf." >&2; exit 1; }

echo "[1/5] git pull ..."
git -C "$BUILD_DIR" pull --ff-only

echo "[2/5] Jar bauen ..."
(cd "$BUILD_DIR" && JAVA_HOME="$(dirname "$(dirname "$JAVA")")" ./gradlew bootJar --quiet)
[[ -f "$JAR" ]] || { echo "    FEHLER: $JAR fehlt." >&2; exit 1; }

echo "[3/5] Agent-Dateien auffrischen ..."
# Kontext und Rechte der Schnellerfassung liegen ausserhalb des Repos, wandern
# aber mit jedem Deploy nach - sonst arbeitet der Agent nach einer veralteten
# CLAUDE.md weiter, ohne dass das irgendwo auffiele.
AGENT_DIR="$HOME/services/food-agent"
if [ -d "$AGENT_DIR" ]; then
    mkdir -p "$AGENT_DIR/.claude"
    cp "$BUILD_DIR/deploy/agent/CLAUDE.md" "$AGENT_DIR/CLAUDE.md"
    cp "$BUILD_DIR/deploy/agent/run-agent.sh" "$AGENT_DIR/run-agent.sh"
    cp "$BUILD_DIR/deploy/agent/.claude/settings.json" "$AGENT_DIR/.claude/settings.json"
    chmod +x "$AGENT_DIR/run-agent.sh"
    echo "    aktualisiert."
else
    echo "    ($AGENT_DIR gibt es nicht - Schnellerfassung ist hier nicht eingerichtet.)"
fi
# Dasselbe fuer den zweiten Agenten, der die Story Cards der Feature Requests
# entwirft (eingerichtet von deploy/setup-feature-requests.sh).
STORY_AGENT_DIR="$HOME/services/story-agent"
if [ -d "$STORY_AGENT_DIR" ] && [ -d "$BUILD_DIR/deploy/story-agent" ]; then
    mkdir -p "$STORY_AGENT_DIR/.claude"
    cp "$BUILD_DIR/deploy/story-agent/CLAUDE.md" "$STORY_AGENT_DIR/CLAUDE.md"
    cp "$BUILD_DIR/deploy/story-agent/run-agent.sh" "$STORY_AGENT_DIR/run-agent.sh"
    cp "$BUILD_DIR/deploy/story-agent/.claude/settings.json" "$STORY_AGENT_DIR/.claude/settings.json"
    chmod +x "$STORY_AGENT_DIR/run-agent.sh"
    echo "    Story-Agent aktualisiert."
fi

echo "[4/5] Jar bereitlegen ..."
mkdir -p "$STAGING"
cp "$JAR" "$STAGING/app.jar"

echo "[5/5] Installieren ..."
# Ohne Passwort ueber /usr/local/sbin/update-service.sh und die Regel
# /etc/sudoers.d/23-agent-deploy (~/scripts/setup-agent-deploy.sh, SERVER-CONTEXT.md):
# sichern, installieren, neu starten, pruefen, notfalls zurueckrollen.
sudo "$ROOT_SCRIPT" food
