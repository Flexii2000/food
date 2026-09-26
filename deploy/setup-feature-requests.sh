#!/usr/bin/env bash
set -euo pipefail

# Richtet die Feature Requests ein (fherrmann.com/feature-requests/): den zweiten
# Claude-Agenten, der aus einem Wunsch eine Story Card entwirft, seine sudo-Regel,
# den Eintrag in /etc/food.env und den Pfad unter fherrmann.com in nginx.
#
# Aufruf VOM LAPTOP aus - das -t ist noetig, sonst kann sudo nicht nach dem
# Passwort fragen und das Skript bricht ohne Ausgabe ab:
#
#     ssh -t HeimServerRemote '~/services/food/deploy/setup-feature-requests.sh'
#
# Voraussetzung: der Kalorienzaehler laeuft schon mit einem Stand, der die
# Feature Requests kennt - also vorher ~/scripts/update-food.sh. Das To-Do
# (fherrmann.com/todo) sollte laufen, damit Anfragen gleich als Unteraufgaben
# ankommen; faellt es aus, legt der Nachlauf sie spaeter an.
#
# Idempotent: jeder Schritt prueft erst, ob er schon erledigt ist.

BUILD_DIR="$HOME/services/food"
# Arbeitsverzeichnis der Entwurfs-Session. Wie beim Agenten der Schnellerfassung
# NEBEN dem Repo und nicht darin: dort stehen die Rechte, und weder ein Deploy noch
# der Agent selbst sollen sie verschieben koennen.
AGENT_DIR="$HOME/services/story-agent"
FOOD_ENV="/etc/food.env"
SUDOERS_TARGET="/etc/sudoers.d/21-story-agent"
NGINX_CONF="/etc/nginx/sites-available/fherrmann.com"
SERVICE_USER="food"
PORT=48180
TODO_PORT=48210
URL="https://fherrmann.com/feature-requests/"

step() { echo; echo "=== $* ==="; }
fail() { echo "FEHLER: $*" >&2; exit 1; }

# Eine Zeile KEY=... in einer env-Datei setzen oder anhaengen (wie in
# setup-health-users.sh). Ueber tee statt mv, damit Besitzer und Rechte
# (root:food 640) der Datei erhalten bleiben.
set_env() {
    local file="$1" key="$2" value="$3" current updated
    current="$(sudo cat "$file")"
    if grep -qE "^${key}=" <<<"$current"; then
        updated="$(awk -v k="$key" -v v="$value" 'index($0, k "=") == 1 { print k "=" v; next } { print }' <<<"$current")"
    else
        updated="$(printf '%s\n%s=%s' "$current" "$key" "$value")"
    fi
    printf '%s\n' "$updated" | sudo tee "$file" >/dev/null
}

get_env() {
    sudo grep -E "^$2=" "$1" | tail -1 | cut -d= -f2- || true
}

# Wie in setup-food.sh: der taegliche certbot-Lauf stoppt nginx fuer ein paar
# Sekunden, ein Reload in diesem Fenster scheitert. Also warten statt abbrechen -
# und bewusst nicht reload-or-restart (Begruendung dort).
nginx_apply() {
    sudo nginx -t
    for attempt in $(seq 1 30); do
        if systemctl is-active --quiet nginx; then
            sudo systemctl reload nginx
            return 0
        fi
        [[ $attempt -eq 1 ]] && echo "    nginx laeuft gerade nicht - vermutlich ein certbot-Lauf. Warte ..."
        sleep 2
    done
    fail "nginx ist seit 60 s nicht aktiv. Status: systemctl status nginx"
}

[[ $EUID -eq 0 ]] && fail "Bitte NICHT mit sudo starten - das Skript ruft sudo selbst auf, wo es noetig ist."
[[ -d "$BUILD_DIR/deploy/story-agent" ]] \
    || fail "$BUILD_DIR/deploy/story-agent fehlt - erst ~/scripts/update-food.sh (holt und installiert den neuen Stand)."
sudo test -f "$FOOD_ENV" || fail "$FOOD_ENV fehlt - ist der Kalorienzaehler eingerichtet (setup-food.sh)?"

step "1/6 Agent-Verzeichnis $AGENT_DIR"
mkdir -p "$AGENT_DIR/.claude"
cp "$BUILD_DIR/deploy/story-agent/CLAUDE.md" "$AGENT_DIR/CLAUDE.md"
cp "$BUILD_DIR/deploy/story-agent/run-agent.sh" "$AGENT_DIR/run-agent.sh"
cp "$BUILD_DIR/deploy/story-agent/.claude/settings.json" "$AGENT_DIR/.claude/settings.json"
chmod +x "$AGENT_DIR/run-agent.sh"
echo "    $AGENT_DIR eingerichtet."

# Wie in setup-food.sh: ein vertrautes Verzeichnis, sonst meldet Claude Code
# "this workspace has not been trusted". Die deny-Liste greift auch ohne - aber
# eine Session, die beim Start ueber ihr Verzeichnis stolpert, soll es hier nicht
# geben.
python3 - "$AGENT_DIR" <<'TRUST'
import json, pathlib, shutil, sys, datetime
agent_dir = sys.argv[1]
config = pathlib.Path.home() / ".claude.json"
if not config.exists():
    print("    HINWEIS: ~/.claude.json fehlt - Vertrauen nicht gesetzt.")
    raise SystemExit
data = json.loads(config.read_text())
if data.get("projects", {}).get(agent_dir, {}).get("hasTrustDialogAccepted"):
    print(f"    Workspace {agent_dir} ist schon vertrauenswuerdig.")
    raise SystemExit
shutil.copy2(config, config.with_suffix(f".json.bak-{datetime.datetime.now():%Y%m%d-%H%M%S}"))
data.setdefault("projects", {}).setdefault(agent_dir, {})["hasTrustDialogAccepted"] = True
config.write_text(json.dumps(data, indent=2))
print(f"    Workspace {agent_dir} als vertrauenswuerdig eingetragen.")
TRUST

step "2/6 sudo-Regel $SUDOERS_TARGET"
# Erst pruefen, dann installieren: eine kaputte Datei unter /etc/sudoers.d/ legt
# sudo auf dem ganzen System lahm.
sudo visudo -c -q -f "$BUILD_DIR/deploy/sudoers-story-agent" \
    || fail "sudoers-Regel ist fehlerhaft - nichts installiert."
sudo install -o root -g root -m 440 "$BUILD_DIR/deploy/sudoers-story-agent" "$SUDOERS_TARGET"
echo "    $SUDOERS_TARGET installiert."

step "3/6 FOOD_STORY_AGENT_COMMAND in $FOOD_ENV"
# Gegenprobe, ob die Regel wirklich greift - ohne eine (kostende) Session zu
# starten: run-agent.sh --version zeigt nur die Version von claude, das beweist
# sudo, Nutzer und Binary auf einmal. Klappt es nicht, bleibt der Entwurf aus und
# die Seite oeffnet den Editor leer, statt bei jedem Wunsch zu scheitern.
if VERSION="$(sudo -u "$SERVICE_USER" sudo -n -u flexii "$AGENT_DIR/run-agent.sh" --version </dev/null 2>/dev/null)"; then
    AGENT_COMMAND="/usr/bin/sudo -n -u flexii $AGENT_DIR/run-agent.sh"
    echo "    $SERVICE_USER darf den Agenten als flexii starten (${VERSION%%$'\n'*})."
else
    AGENT_COMMAND=""
    echo "    HINWEIS: die sudo-Ausnahme greift nicht."
    echo "             Steht NoNewPrivileges in /etc/systemd/system/food.service?"
    echo "             Feature Requests laufen so lange ohne Claude-Entwurf."
fi
set_env "$FOOD_ENV" FOOD_STORY_AGENT_COMMAND "$AGENT_COMMAND"
echo "    FOOD_STORY_AGENT_COMMAND=${AGENT_COMMAND:-(leer)}"

step "4/6 nginx: /feature-requests/ unter fherrmann.com"
if grep -q "location /feature-requests/" "$NGINX_CONF"; then
    echo "    Schon eingebunden."
else
    sudo cp "$NGINX_CONF" "$NGINX_CONF.bak.$(date +%s)"
    # Direkt vor den /grades/-Block: gleiche Ebene, gleiche Bauart wie /todo/ und
    # /shopping-list/ - und wie bei der Einkaufsliste OHNE Privat-Gate.
    grep -q "location /grades/" "$NGINX_CONF" || fail "Kein /grades/-Block in $NGINX_CONF - wo soll /feature-requests/ hin?"
    python3 - "$NGINX_CONF" "$BUILD_DIR/deploy/nginx-feature-requests.conf" <<'PY'
import sys, pathlib, subprocess
conf, snippet = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]).read_text()
text = conf.read_text()
marker = "      location /grades/ {"
assert text.count(marker) == 1, "der /grades/-Block steht nicht genau einmal da"
neu = text.replace(marker, snippet + marker)
subprocess.run(["sudo", "tee", str(conf)], input=neu.encode(), check=True, stdout=subprocess.DEVNULL)
PY
    nginx_apply
    echo "    Eingebunden und nginx neu geladen."
fi

step "5/6 food neu starten"
# Neu starten, damit der Dienst FOOD_STORY_AGENT_COMMAND aus der env-Datei liest.
sudo systemctl restart food
for i in $(seq 1 45); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/" || true)"
    [[ "$code" != "000" ]] && break
    sleep 1
done
[[ "${code:-000}" != "000" ]] || fail "food antwortet nicht. Log: journalctl -u food -n 50"
echo "    food laeuft."

step "6/6 Pruefung"
TOKEN="$(get_env "$FOOD_ENV" FH_PRIVATE_TOKEN)"
[[ -n "$TOKEN" ]] || fail "Kein FH_PRIVATE_TOKEN in $FOOD_ENV."

# Beim Dienst selbst: kennt er die Feature Requests, und greift die Anmeldung?
features="$(curl -s --max-time 5 --cookie "fh_private=$TOKEN" "http://127.0.0.1:$PORT/feature-requests/api/features" || true)"
grep -q '"drafting"' <<<"$features" \
    || fail "food kennt /feature-requests/ nicht (Antwort: ${features:0:120}) - laeuft der neue Stand? Erst ~/scripts/update-food.sh."
if grep -q '"drafting":true' <<<"$features"; then
    echo "    Dienst: Feature Requests mit Claude-Entwurf."
else
    echo "    Dienst: Feature Requests ohne Claude-Entwurf (Editor oeffnet leer)."
fi

# Ueber nginx, wie Torben die Seite sieht. --resolve schickt den Aufruf an das
# nginx dieser Kiste statt einmal durchs Internet; klappt das nicht, der normale Weg.
public="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 --resolve fherrmann.com:443:127.0.0.1 "$URL" || true)"
if [[ "$public" == "000" ]]; then
    public="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$URL" || true)"
fi
[[ "$public" == "403" ]] || fail "$URL ohne Cookie: HTTP $public statt 403."
echo "    $URL ohne Cookie: 403."
authed="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 --resolve fherrmann.com:443:127.0.0.1 \
    --cookie "fh_private=$TOKEN" "${URL}api/features" || true)"
if [[ "$authed" == "000" ]]; then
    authed="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 --cookie "fh_private=$TOKEN" "${URL}api/features" || true)"
fi
[[ "$authed" == "200" ]] || fail "${URL}api/features mit Privat-Cookie: HTTP $authed statt 200."
echo "    Mit Privat-Cookie: 200."

# Das To-Do nur als Hinweis: ohne es gehen Anfragen nicht verloren, sie warten.
todo="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 --cookie "fh_private=$TOKEN" \
    "http://127.0.0.1:$TODO_PORT/todo/api/board" || true)"
if [[ "$todo" == "200" ]]; then
    echo "    To-Do erreichbar - Anfragen landen unter Server / Healthy."
else
    echo "    HINWEIS: To-Do antwortet mit HTTP $todo - Anfragen warten, bis der Nachlauf sie anlegt."
fi

echo
echo "Fertig. $URL"
echo "Torben kommt mit seinem health_token hinein (gilt fuer fherrmann.com), Felix mit fh_private."
echo "Damit jeder Deploy auch den Story-Agenten auffrischt: deploy/update-food.sh nach ~/scripts/ kopieren."
