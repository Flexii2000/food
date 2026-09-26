#!/usr/bin/env bash
set -euo pipefail

# Richtet persoenliche Healthy-Zugaenge ein: je Person EIN Token, der den
# Kalorienzaehler (food.fherrmann.com) UND den Weight Tracker
# (weight.fherrmann.com) oeffnet - im Browser per Setup-Link, in der
# Android-App als Bearer-Token. Jede Person sieht nur ihre eigenen Daten.
#
# Aufruf VOM LAPTOP aus - das -t ist noetig, sonst kann sudo nicht nach dem
# Passwort fragen:
#
#     ssh -t HeimServerRemote '~/services/food/deploy/setup-health-users.sh --detailed torben'
#
# Mit Push an Android (Firebase-Dienstkonto vorher per scp nach ~ kopiert):
#
#     ssh -t HeimServerRemote '~/services/food/deploy/setup-health-users.sh --fcm-key ~/fcm-healthy.json torben'
#
# Optionen:
#     --fcm-key <datei>      Firebase-Dienstkonto nach /etc/fcm-healthy.json legen
#     --no-quick-capture     die genannten Personen bekommen KEINE Schnellerfassung
#                            (sie laeuft auf Felix' Claude-Login)
#     --detailed             die genannten Personen erfassen die ganze
#                            Naehrwerttabelle (ges. Fettsaeuren, Zucker,
#                            Ballaststoffe, Salz) statt nur kcal und Makros
#
# Idempotent: vorhandene Token bleiben, wie sie sind; nur wer noch keinen hat,
# bekommt einen. Ohne Namen richtet es nur Verzeichnis, nginx und ggf. Firebase
# ein und zeigt die vorhandenen Setup-Links.
#
# Voraussetzung: beide Dienste laufen schon mit einem Stand, der HEALTH_TOKENS
# kennt - also vorher ~/scripts/update-food.sh und das Update des Weight
# Trackers (siehe SERVER-CONTEXT.md). Ein aelterer Stand ignoriert die Token
# schlicht, die Pruefung am Ende faellt dann durch.

BUILD_DIR="$HOME/services/food"
FOOD_ENV="/etc/food.env"
WEIGHT_ENV="/etc/health-viz.env"
ANDROID_DIR="/opt/healthy-android"
FCM_TARGET="/etc/fcm-healthy.json"
DOMAIN="food.fherrmann.com"
FOOD_PORT=48180
WEIGHT_PORT=48173

step() { echo; echo "=== $* ==="; }
fail() { echo "FEHLER: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] && fail "Bitte NICHT mit sudo starten - das Skript ruft sudo selbst auf, wo es noetig ist."

FCM_KEY=""
QUICK_CAPTURE=1
DETAILED=0
PEOPLE=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --fcm-key) FCM_KEY="${2:-}"; [[ -n "$FCM_KEY" ]] || fail "--fcm-key braucht eine Datei"; shift 2 ;;
        --no-quick-capture) QUICK_CAPTURE=0; shift ;;
        --detailed) DETAILED=1; shift ;;
        -*) fail "Unbekannte Option $1" ;;
        *) PEOPLE+=("$1"); shift ;;
    esac
done

for person in "${PEOPLE[@]}"; do
    # Die Namen werden zu Ordnern (data/users/<name>/) - dieselbe Regel wie in
    # HealthUsers.java, sonst startet der Dienst nicht.
    [[ "$person" =~ ^[a-z][a-z0-9_-]{0,31}$ ]] || fail "Ungueltiger Name '$person' (klein, a-z, 0-9, _ und -)."
done

sudo test -f "$FOOD_ENV" || fail "$FOOD_ENV fehlt - ist der Kalorienzaehler eingerichtet (setup-food.sh)?"
sudo test -f "$WEIGHT_ENV" || fail "$WEIGHT_ENV fehlt - laeuft der Weight Tracker?"

# Eine Zeile KEY=... in einer env-Datei setzen oder anhaengen. Ueber tee statt
# mv, damit Besitzer und Rechte (root:<dienst> 640) der Datei erhalten bleiben.
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
# Sekunden, ein Reload in diesem Fenster scheitert. Also warten statt abbrechen.
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

OWNER="$(get_env "$FOOD_ENV" HEALTH_OWNER)"
OWNER="${OWNER:-felix}"

step "1/6 Token"
declare -A TOKENS=()
EXISTING="$(get_env "$FOOD_ENV" HEALTH_TOKENS)"
IFS=',' read -ra PAIRS <<<"$EXISTING"
for pair in "${PAIRS[@]}"; do
    [[ -z "$pair" ]] && continue
    TOKENS["${pair%%:*}"]="${pair#*:}"
done
for person in "${PEOPLE[@]}"; do
    if [[ -n "${TOKENS[$person]:-}" ]]; then
        echo "    $person hat schon einen Token - bleibt."
    else
        TOKENS["$person"]="$(openssl rand -hex 24)"
        echo "    $person: neuer Token."
    fi
done
JOINED=""
for name in $(printf '%s\n' "${!TOKENS[@]}" | sort); do
    JOINED+="${JOINED:+,}${name}:${TOKENS[$name]}"
done
# Derselbe Wert in beiden Dateien - ein Token, zwei Dienste. food.env ist die
# Quelle; was in health-viz.env stand, wird ueberschrieben.
set_env "$FOOD_ENV" HEALTH_TOKENS "$JOINED"
set_env "$WEIGHT_ENV" HEALTH_TOKENS "$JOINED"
echo "    HEALTH_TOKENS in $FOOD_ENV und $WEIGHT_ENV: ${#TOKENS[@]} Person(en)."

step "2/6 Schnellerfassung und Detailwerte"
ALLOWED="$(get_env "$FOOD_ENV" FOOD_QUICK_CAPTURE)"
ALLOWED="${ALLOWED:-$OWNER}"
if [[ "$ALLOWED" != "*" && $QUICK_CAPTURE -eq 1 ]]; then
    for person in "${PEOPLE[@]}"; do
        [[ ",$ALLOWED," == *",$person,"* ]] || ALLOWED+=",$person"
    done
fi
set_env "$FOOD_ENV" FOOD_QUICK_CAPTURE "$ALLOWED"
echo "    freigeschaltet: $ALLOWED"

# Detailwerte: nur hinzufuegen, nie wegnehmen - wer schon detailliert erfasst,
# bleibt dabei, auch wenn das Skript spaeter fuer jemand anderen laeuft.
DETAILED_PEOPLE="$(get_env "$FOOD_ENV" FOOD_DETAILED_NUTRIENTS)"
if [[ "$DETAILED_PEOPLE" != "*" && $DETAILED -eq 1 ]]; then
    for person in "${PEOPLE[@]}"; do
        [[ ",$DETAILED_PEOPLE," == *",$person,"* ]] || DETAILED_PEOPLE+="${DETAILED_PEOPLE:+,}$person"
    done
fi
if [[ -n "$DETAILED_PEOPLE" ]]; then
    set_env "$FOOD_ENV" FOOD_DETAILED_NUTRIENTS "$DETAILED_PEOPLE"
    echo "    ganze Naehrwerttabelle: $DETAILED_PEOPLE"
fi

step "3/6 Verzeichnis fuer die Android-App"
# Gehoert flexii, damit das Veroeffentlichen ohne sudo geht (scp); der Dienst
# liest nur - 755/644 reichen, ProtectSystem=strict erlaubt Lesen.
sudo install -d -o "$(id -un)" -g "$(id -gn)" -m 755 "$ANDROID_DIR"
set_env "$FOOD_ENV" FOOD_ANDROID_DIR "$ANDROID_DIR"
if [[ -f "$ANDROID_DIR/healthy.apk" && -f "$ANDROID_DIR/latest.json" ]]; then
    echo "    $ANDROID_DIR - veroeffentlicht: $(cat "$ANDROID_DIR/latest.json")"
else
    echo "    $ANDROID_DIR - noch keine App veroeffentlicht (tools/publish.sh im Repo healthy-android)."
fi

step "4/6 Push an Android (Firebase)"
if [[ -n "$FCM_KEY" ]]; then
    [[ -r "$FCM_KEY" ]] || fail "$FCM_KEY nicht lesbar."
    python3 - "$FCM_KEY" <<'CHECK' || fail "$FCM_KEY ist keine Dienstkonto-Datei von Firebase."
import json, sys
data = json.load(open(sys.argv[1]))
assert data.get("type") == "service_account"
assert data.get("project_id") and data.get("private_key") and data.get("client_email")
CHECK
    sudo install -o root -g food -m 640 "$FCM_KEY" "$FCM_TARGET"
    set_env "$FOOD_ENV" FCM_SERVICE_ACCOUNT_FILE "$FCM_TARGET"
    echo "    $FCM_TARGET installiert (root:food 640). Die Kopie $FCM_KEY kann weg."
elif sudo test -f "$FCM_TARGET"; then
    echo "    $FCM_TARGET ist schon da."
else
    echo "    kein Dienstkonto - Android bekommt vorerst keine Benachrichtigungen."
fi

step "5/6 nginx ohne Privat-Gate"
# Der Dienst prueft jeden Zugang selbst; ein Gate in nginx sperrte die
# persoenlichen Token aus (siehe Kommentar in der Konfiguration).
sudo cp "$BUILD_DIR/deploy/nginx-food.fherrmann.com.conf" "/etc/nginx/sites-available/$DOMAIN"
nginx_apply
echo "    $DOMAIN ausgeliefert."

step "6/6 Neustart und Pruefung"
sudo systemctl restart food health-viz
for port in $FOOD_PORT $WEIGHT_PORT; do
    for i in $(seq 1 45); do
        code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$port/" || true)"
        [[ "$code" != "000" ]] && break
        sleep 1
    done
    [[ "${code:-000}" != "000" ]] || fail "Port $port antwortet nicht. Log: journalctl -u food / -u health-viz"
done
for name in $(printf '%s\n' "${!TOKENS[@]}" | sort); do
    token="${TOKENS[$name]}"
    food="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
        -H "Authorization: Bearer $token" "http://127.0.0.1:$FOOD_PORT/api/food/features" || true)"
    weight="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
        -H "Authorization: Bearer $token" "http://127.0.0.1:$WEIGHT_PORT/api/weight/summary" || true)"
    [[ "$food" == "200" && "$weight" == "200" ]] \
        || fail "$name: Kalorienzaehler $food, Weight Tracker $weight statt 200/200 - laufen beide schon mit dem neuen Stand?"
    echo "    $name: beide Dienste 200."
done

echo
echo "Fertig. Je Person ein Link - im Browser einmal oeffnen, oder in der"
echo "Android-App einfuegen (nimmt den Link oder den Token):"
for name in $(printf '%s\n' "${!TOKENS[@]}" | sort); do
    echo "    $name:  https://$DOMAIN/setup?token=${TOKENS[$name]}"
done
echo
echo "Die Android-App zum Herunterladen (nach dem Setup-Link im selben Browser):"
echo "    https://$DOMAIN/api/app/android/apk"
