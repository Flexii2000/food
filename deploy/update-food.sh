#!/usr/bin/env bash
set -euo pipefail

# Deployt eine neue Version des Kalorienzaehlers (food.fherrmann.com).
#
#     ~/scripts/update-food.sh
#
# Baut auf dem Server aus ~/services/food und installiert das Ergebnis nach
# /opt/food. Der Build laeuft bewusst als flexii, nur die Installation braucht
# Root - deshalb NICHT das ganze Skript mit sudo starten.
#
# /opt/food/data/ wird BEWUSST nicht angefasst: food.json ist die Live-Quelle,
# die die App selbst fortschreibt (Gerichte, Eintraege, Tagesziele).

BUILD_DIR="$HOME/services/food"
APP_DIR="/opt/food"
TARGET="$APP_DIR/app.jar"
JAR="$BUILD_DIR/build/libs/Food-0.0.1-SNAPSHOT.jar"
JAVA="/opt/java/jdk-25.0.1+8/bin/java"
PORT=48180

[[ $EUID -ne 0 ]] || { echo "Bitte OHNE sudo starten - das Skript ruft sudo selbst auf." >&2; exit 1; }

echo "[1/6] git pull ..."
git -C "$BUILD_DIR" pull --ff-only

echo "[2/6] Jar bauen ..."
(cd "$BUILD_DIR" && JAVA_HOME="$(dirname "$(dirname "$JAVA")")" ./gradlew bootJar --quiet)
[[ -f "$JAR" ]] || { echo "    FEHLER: $JAR fehlt." >&2; exit 1; }

echo "[3/6] Agent-Dateien auffrischen ..."
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

echo "[4/6] Laufendes Jar sichern ..."
BACKUP="$TARGET.bak-$(date +%Y%m%d-%H%M%S)"
sudo cp -p "$TARGET" "$BACKUP"
echo "    Backup: $BACKUP"

echo "[5/6] Installieren und neu starten ..."
sudo install -o food -g food -m 644 "$JAR" "$TARGET"
sudo systemctl restart food

echo "[6/6] Health-Check ..."
for i in $(seq 1 30); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/" || true)"
    # Ohne Cookie ist 403 die richtige Antwort; jeder Status heisst "App bedient".
    if [[ "$code" != "000" ]]; then
        echo "    OK (HTTP $code)"
        echo "food erfolgreich aktualisiert."
        exit 0
    fi
    sleep 1
done

echo "    FEHLER: App antwortet nicht. Rollback auf $BACKUP ..." >&2
sudo install -o food -g food -m 644 "$BACKUP" "$TARGET"
sudo systemctl restart food
echo "    Zurueckgerollt. Logs: journalctl -u food -n 50" >&2
exit 1
