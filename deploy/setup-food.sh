#!/usr/bin/env bash
set -euo pipefail

# Erstinstallation des Kalorienzaehlers (food.fherrmann.com) auf dem Heimserver.
#
# Aufruf VOM LAPTOP aus - das -t ist noetig, sonst kann sudo nicht nach dem
# Passwort fragen und das Skript bricht ohne Ausgabe ab:
#
#     ssh -t HeimServerRemote '~/scripts/setup-food.sh'
#
# Oder direkt auf dem Server in einer normalen Shell:
#
#     ~/scripts/setup-food.sh
#
# Das Skript ist idempotent: jeder Schritt prueft erst, ob er schon erledigt
# ist. Ein zweiter Lauf nach einem Abbruch macht dort weiter, wo es hakte.
#
# Voraussetzung, die es NICHT selbst herstellen kann: der DNS-Eintrag fuer
# food.fherrmann.com muss stehen und auf die Lightsail-IP zeigen, sonst
# scheitert certbot in Schritt 7.

REPO="git@github.com:Flexii2000/food.git"
BUILD_DIR="$HOME/services/food"
APP_DIR="/opt/food"
SERVICE_USER="food"
DOMAIN="food.fherrmann.com"
WEBROOT="/var/www/$DOMAIN"
PORT=48180
JAVA="/opt/java/jdk-25.0.1+8/bin/java"
PRIVATE_MODE_CONF="/etc/nginx/conf.d/private-mode.conf"
# Arbeitsverzeichnis der Schnellerfassungs-Session. Liegt bewusst NEBEN dem Repo
# und nicht darin: dort stehen die Rechte des Agents, und weder ein Deploy noch
# der Agent selbst sollen sie verschieben koennen (dasselbe Prinzip wie beim
# Finance Cockpit).
AGENT_DIR="$HOME/services/food-agent"

step() { echo; echo "=== $* ==="; }
fail() { echo "FEHLER: $*" >&2; exit 1; }

# Konfiguration pruefen und nginx neu einlesen.
#
# Der Umweg ueber die Warteschleife hat einen konkreten Grund: der taegliche
# certbot-Lauf STOPPT nginx fuer rund zehn Sekunden, weil drei Zertifikate auf
# `authenticator = standalone` stehen und certbot dafuer selbst an Port 80 muss.
# Ein Reload, der in dieses Fenster faellt, scheitert mit "Unit cannot be
# reloaded because it is inactive" - und riss dieses Skript beim ersten Lauf
# mitten in der Installation ab.
#
# Bewusst NICHT `reload-or-restart`: das wuerde nginx waehrend des
# certbot-Laufs starten, ihm den Port wegnehmen und die Erneuerung kaputt
# machen. Und es faellt nicht unter die NOPASSWD-Regel in
# /etc/sudoers.d/10-flexii-deploy, die exakt auf `systemctl reload nginx` passt.
nginx_apply() {
    sudo nginx -t
    for attempt in $(seq 1 30); do
        if systemctl is-active --quiet nginx; then
            sudo systemctl reload nginx
            return 0
        fi
        if [[ $attempt -eq 1 ]]; then
            echo "    nginx laeuft gerade nicht - vermutlich ein certbot-Lauf. Warte ..."
        fi
        sleep 2
    done
    fail "nginx ist seit 60 s nicht aktiv. Status: systemctl status nginx"
}

[[ $EUID -eq 0 ]] && fail "Bitte NICHT mit sudo starten - das Skript ruft sudo selbst auf, wo es noetig ist."

step "1/10 Repo holen"
if [[ -d "$BUILD_DIR/.git" ]]; then
    git -C "$BUILD_DIR" pull --ff-only
else
    mkdir -p "$(dirname "$BUILD_DIR")"
    git clone "$REPO" "$BUILD_DIR"
fi

step "2/10 Jar bauen"
# Als flexii bauen, nicht als root: sonst gehoert der Gradle-Cache hinterher root.
(cd "$BUILD_DIR" && JAVA_HOME="$(dirname "$(dirname "$JAVA")")" ./gradlew bootJar --quiet)
JAR="$BUILD_DIR/build/libs/Food-0.0.1-SNAPSHOT.jar"
[[ -f "$JAR" ]] || fail "$JAR wurde nicht gebaut."
echo "    $(du -h "$JAR" | cut -f1)"

step "3/10 Service-User und Verzeichnisse"
if ! id -u "$SERVICE_USER" >/dev/null 2>&1; then
    sudo useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin "$SERVICE_USER"
    echo "    User $SERVICE_USER angelegt."
else
    echo "    User $SERVICE_USER existiert bereits."
fi
sudo mkdir -p "$APP_DIR/data"
sudo chown -R "$SERVICE_USER:$SERVICE_USER" "$APP_DIR"

step "4/10 Token aus $PRIVATE_MODE_CONF uebernehmen"
# Der Token steht genau einmal auf dem System - in der nginx-Map. Hier wird er
# gelesen statt neu erfunden, damit Cookie und App-Pruefung nicht auseinander
# laufen koennen.
[[ -r "$PRIVATE_MODE_CONF" ]] || sudo test -r "$PRIVATE_MODE_CONF" \
    || fail "$PRIVATE_MODE_CONF nicht lesbar - laeuft der private Modus ueberhaupt?"
TOKEN="$(sudo grep -oE '"[0-9a-fA-F]{24,}"' "$PRIVATE_MODE_CONF" | head -1 | tr -d '"')"
[[ -n "$TOKEN" ]] || fail "Kein Token in $PRIVATE_MODE_CONF gefunden."
printf 'FH_PRIVATE_TOKEN=%s\n' "$TOKEN" | sudo tee /etc/food.env >/dev/null
sudo chown root:"$SERVICE_USER" /etc/food.env
sudo chmod 640 /etc/food.env
echo "    /etc/food.env geschrieben (Token ${TOKEN:0:6}…, $(echo -n "$TOKEN" | wc -c) Zeichen)."

step "5/10 Agent-Verzeichnis fuer die Schnellerfassung"
mkdir -p "$AGENT_DIR/.claude"
cp "$BUILD_DIR/deploy/agent/CLAUDE.md" "$AGENT_DIR/CLAUDE.md"
cp "$BUILD_DIR/deploy/agent/run-agent.sh" "$AGENT_DIR/run-agent.sh"
cp "$BUILD_DIR/deploy/agent/.claude/settings.json" "$AGENT_DIR/.claude/settings.json"
chmod +x "$AGENT_DIR/run-agent.sh"
echo "    $AGENT_DIR eingerichtet."

# Der Dienstnutzer kommt an /home/flexii nicht heran (750) - die Session laeuft
# deshalb ueber eine einzelne sudo-Ausnahme als flexii. Die Regel wird VOR dem
# Installieren geprueft: eine kaputte Datei unter /etc/sudoers.d/ legt sudo auf
# dem ganzen System lahm.
sudo visudo -c -q -f "$BUILD_DIR/deploy/sudoers-food-agent" \
    || fail "sudoers-Regel ist fehlerhaft - nichts installiert."
sudo install -o root -g root -m 440 \
    "$BUILD_DIR/deploy/sudoers-food-agent" /etc/sudoers.d/20-food-agent
echo "    /etc/sudoers.d/20-food-agent installiert."

# Jetzt gegenpruefen, ob es auch wirklich greift - ohne dabei eine (kostende)
# Claude-Session zu starten. Schlaegt das fehl, bleibt die Schnellerfassung aus,
# statt einen Knopf anzubieten, der erst beim Druecken scheitert.
if sudo -u "$SERVICE_USER" sudo -n -u flexii "$AGENT_DIR/run-agent.sh" --version >/dev/null 2>&1 \
        || sudo -u "$SERVICE_USER" sudo -n -l -u flexii "$AGENT_DIR/run-agent.sh" >/dev/null 2>&1; then
    AGENT_COMMAND="/usr/bin/sudo -n -u flexii $AGENT_DIR/run-agent.sh"
    echo "    $SERVICE_USER darf die Session als flexii starten."
else
    AGENT_COMMAND=""
    echo "    HINWEIS: die sudo-Ausnahme greift nicht."
    echo "             Steht NoNewPrivileges noch in /etc/systemd/system/food.service?"
    echo "             Schnellerfassung bleibt so lange deaktiviert."
fi

step "6/10 Jar installieren"
sudo install -o "$SERVICE_USER" -g "$SERVICE_USER" -m 644 "$JAR" "$APP_DIR/app.jar"

# Erst hier, weil AGENT_COMMAND aus Schritt 5 kommt.
printf 'FOOD_AGENT_COMMAND=%s\n' "$AGENT_COMMAND" | sudo tee -a /etc/food.env >/dev/null

step "7/10 systemd-Unit"
sudo cp "$BUILD_DIR/deploy/food.service" /etc/systemd/system/food.service
sudo systemctl daemon-reload
sudo systemctl enable food
# restart statt "enable --now": laeuft der Dienst schon (zweiter Lauf des
# Skripts), wuerde --now ihn nicht anfassen - das gerade installierte Jar
# bliebe dann ungenutzt, und das Skript meldete trotzdem Erfolg.
sudo systemctl restart food
sleep 2
sudo systemctl is-active --quiet food || {
    sudo journalctl -u food -n 30 --no-pager >&2
    fail "food.service laeuft nicht - Log siehe oben."
}
echo "    food.service laeuft."

step "8/10 nginx :80 + Zertifikat"
sudo mkdir -p "$WEBROOT"
sudo chown -R www-data:www-data "$WEBROOT"
if sudo test -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem"; then
    echo "    Zertifikat existiert bereits."
else
    # Erst nur den :80-Block ausliefern - der :443-Block wuerde ohne Zertifikat
    # den nginx-Start verhindern.
    awk '/^server \{/{n++} n==1' "$BUILD_DIR/deploy/nginx-food.fherrmann.com.conf" \
        | sudo tee "/etc/nginx/sites-available/$DOMAIN" >/dev/null
    sudo ln -sfn "/etc/nginx/sites-available/$DOMAIN" "/etc/nginx/sites-enabled/$DOMAIN"
    nginx_apply
    sudo certbot certonly --webroot -w "$WEBROOT" -d "$DOMAIN" --non-interactive --agree-tos \
        --register-unsafely-without-email \
        || fail "certbot fehlgeschlagen. Haeufigste Gruende: DNS fuer $DOMAIN fehlt, oder der taegliche certbot-Lauf haelt gerade die Sperre (dann einfach dieses Skript nochmal starten)."
fi

step "9/10 nginx vollstaendig (mit Privat-Gate)"
sudo cp "$BUILD_DIR/deploy/nginx-food.fherrmann.com.conf" "/etc/nginx/sites-available/$DOMAIN"
sudo ln -sfn "/etc/nginx/sites-available/$DOMAIN" "/etc/nginx/sites-enabled/$DOMAIN"
nginx_apply

step "10/10 Health-Check"
# Ohne Cookie antwortet die App mit 403 - jeder HTTP-Status beweist, dass sie
# bedient; laeuft sie nicht, scheitert curl und $code ist 000.
for i in $(seq 1 30); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/" || true)"
    if [[ "$code" != "000" ]]; then
        echo "    App antwortet auf 127.0.0.1:$PORT (HTTP $code)."
        break
    fi
    sleep 1
done
[[ "${code:-000}" != "000" ]] || fail "App antwortet nicht. Log: journalctl -u food -n 50"

# Mit Cookie muss es 200 sein - das prueft Token-Datei und App-Pruefung zugleich.
authed="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 \
    --cookie "fh_private=$TOKEN" "http://127.0.0.1:$PORT/api/food/day" || true)"
[[ "$authed" == "200" ]] || fail "Mit Cookie kam HTTP $authed statt 200 - Token in /etc/food.env pruefen."
echo "    Auth mit dem privaten Cookie funktioniert (HTTP 200)."

echo
echo "Fertig. https://$DOMAIN ist erreichbar, sobald der Browser den"
echo "fh_private-Cookie hat (einmalig https://fherrmann.com/setup?token=… oeffnen)."
echo "Spaetere Updates: sudo ~/scripts/update-food.sh"
