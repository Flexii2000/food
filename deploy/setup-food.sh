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

step() { echo; echo "=== $* ==="; }
fail() { echo "FEHLER: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] && fail "Bitte NICHT mit sudo starten - das Skript ruft sudo selbst auf, wo es noetig ist."

step "1/9  Repo holen"
if [[ -d "$BUILD_DIR/.git" ]]; then
    git -C "$BUILD_DIR" pull --ff-only
else
    mkdir -p "$(dirname "$BUILD_DIR")"
    git clone "$REPO" "$BUILD_DIR"
fi

step "2/9  Jar bauen"
# Als flexii bauen, nicht als root: sonst gehoert der Gradle-Cache hinterher root.
(cd "$BUILD_DIR" && JAVA_HOME="$(dirname "$(dirname "$JAVA")")" ./gradlew bootJar --quiet)
JAR="$BUILD_DIR/build/libs/Food-0.0.1-SNAPSHOT.jar"
[[ -f "$JAR" ]] || fail "$JAR wurde nicht gebaut."
echo "    $(du -h "$JAR" | cut -f1)"

step "3/9  Service-User und Verzeichnisse"
if ! id -u "$SERVICE_USER" >/dev/null 2>&1; then
    sudo useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin "$SERVICE_USER"
    echo "    User $SERVICE_USER angelegt."
else
    echo "    User $SERVICE_USER existiert bereits."
fi
sudo mkdir -p "$APP_DIR/data"
sudo chown -R "$SERVICE_USER:$SERVICE_USER" "$APP_DIR"

step "4/9  Token aus $PRIVATE_MODE_CONF uebernehmen"
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

step "5/9  Jar installieren"
sudo install -o "$SERVICE_USER" -g "$SERVICE_USER" -m 644 "$JAR" "$APP_DIR/app.jar"

step "6/9  systemd-Unit"
sudo cp "$BUILD_DIR/deploy/food.service" /etc/systemd/system/food.service
sudo systemctl daemon-reload
sudo systemctl enable --now food
sleep 2
sudo systemctl is-active --quiet food || {
    sudo journalctl -u food -n 30 --no-pager >&2
    fail "food.service laeuft nicht - Log siehe oben."
}
echo "    food.service laeuft."

step "7/9  nginx :80 + Zertifikat"
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
    sudo nginx -t
    sudo systemctl reload nginx
    sudo certbot certonly --webroot -w "$WEBROOT" -d "$DOMAIN" --non-interactive --agree-tos \
        --register-unsafely-without-email || fail "certbot fehlgeschlagen - DNS fuer $DOMAIN gesetzt?"
fi

step "8/9  nginx vollstaendig (mit Privat-Gate)"
sudo cp "$BUILD_DIR/deploy/nginx-food.fherrmann.com.conf" "/etc/nginx/sites-available/$DOMAIN"
sudo ln -sfn "/etc/nginx/sites-available/$DOMAIN" "/etc/nginx/sites-enabled/$DOMAIN"
sudo nginx -t
sudo systemctl reload nginx

step "9/9  Health-Check"
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
