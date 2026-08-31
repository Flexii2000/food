# Food – Kalorienzähler

Kleine Spring-Boot-Anwendung, die den Tagesbedarf einer einzelnen Person gegen das
hält, was sie gegessen hat. Bewusst auf **vier Werte** beschränkt: kcal, Eiweiß,
Kohlenhydrate, Fett. Kein Mikronährstoff-Tracking, keine Rezeptdatenbank, keine
Barcode-Scans.

Läuft unter <https://food.fherrmann.com> und gehört zum
[Weight Tracker](https://github.com/Flexii2000/weight-app) — dort blendet ein
Umschalter die hier erfassten kcal in die Gewichtskurven ein.

## Anzeige

Oben drei kleine Tachos nebeneinander für die **bisher verzehrten** Makros
(Eiweiß, Fett, Kohlenhydrate), darunter ein großer Tacho für die **noch übrigen**
kcal, daneben als bloße Zahl der verzehrte Wert.

Die Einfärbung folgt der Richtung, in die das jeweilige Ziel gemeint ist:
Eiweiß ist ein **Mindestwert** (ab dem Ziel grün), kcal, Fett und Kohlenhydrate
sind **Obergrenzen** (ab 85 % gelb, darüber rot). Eine gemeinsame Skala für alle
vier würde die Hälfte der Fälle falsch herum bewerten.

## Gramm-basiert, mit optionaler Portion

Ein Gericht speichert seine Nährwerte **je 100 g** — so stehen sie auf der
Packung, und nur so lässt sich eine beliebige Menge ausrechnen. Zusätzlich kann
eine **übliche Portionsgröße in Gramm** hinterlegt werden; dann gibt es in der
Eingabemaske Knöpfe für ½, 1, 1½ und 2 Portionen, die das Grammfeld füllen.
Gerichte ohne Portionsgröße werden immer nach Gewicht eingetragen.

Ein Gericht, das beim Eintragen neu getippt wird, landet automatisch in der
Liste und kann danach ausgewählt werden — ein eigener Schritt „Gericht anlegen"
ist nicht nötig.

### Warum Einträge ihre eigene Kopie der Nährwerte tragen

Ein Eintrag speichert Name **und** 100-g-Werte des Gerichts, nicht bloß eine
Referenz darauf. Sonst würde eine Korrektur an einem Gericht rückwirkend
umschreiben, was letzten Monat auf dem Teller lag, und ein gelöschtes Gericht
würde die Historie leerräumen. Die `dishId` bleibt als Rückverweis erhalten
(für die Sortierung der Auswahlliste), gerechnet wird aber nie über sie.

## Single Source of Truth

Alle Daten liegen in **`data/food.json`** — Tagesziele, Gerichte und Einträge:

```json
{
  "targets": { "kcal": 2300.0, "proteinG": 200.0, "carbsG": 235.5, "fatG": 62.0 },
  "dishes": [
    {
      "id": "9b2e3707-…", "name": "Skyr mit Beeren",
      "per100g": { "kcal": 80.0, "proteinG": 8.0, "carbsG": 6.0, "fatG": 1.3 },
      "portionG": 300.0, "lastUsedOn": "2026-08-31"
    }
  ],
  "entries": [
    {
      "id": "4bc98037-…", "date": "2026-08-31", "dishId": "9b2e3707-…",
      "name": "Skyr mit Beeren", "grams": 300.0,
      "per100g": { "kcal": 80.0, "proteinG": 8.0, "carbsG": 6.0, "fatG": 1.3 },
      "createdAt": "2026-08-31T16:14:08.504659Z"
    }
  ]
}
```

Die Datei muss nicht existieren: fehlt sie, startet die App mit den Default-Zielen
und leerer Liste. Sie liegt deshalb auch nicht im Repo.

### Default-Tagesziele

2300 kcal, 200 g Eiweiß, 62 g Fett — die Kohlenhydrate füllen den Rest exakt auf:

```
200 g Eiweiß × 4 kcal  =  800 kcal
 62 g Fett   × 9 kcal  =  558 kcal
                          --------
                          1358 kcal
2300 − 1358 = 942 kcal  →  235,5 g Kohlenhydrate
```

Änderbar in der UI unter „Tagesziele anpassen"; dort zeigt eine Zeile mit, ob die
vier Zahlen noch zusammenpassen.

## REST-API

| Methode | Pfad                      | Beschreibung                                           |
|---------|---------------------------|---------------------------------------------------------|
| GET     | `/api/food/day?date=`     | Ziele, Tagessummen und Einträge eines Tages (Default: heute) |
| GET     | `/api/food/dishes`        | Gerichteliste, zuletzt benutzte zuerst                  |
| POST    | `/api/food/dishes`        | Gericht anlegen                                         |
| PUT     | `/api/food/dishes/{id}`   | Gericht korrigieren                                     |
| DELETE  | `/api/food/dishes/{id}`   | Gericht vergessen (Einträge bleiben)                    |
| POST    | `/api/food/entries`       | Menge eintragen                                         |
| DELETE  | `/api/food/entries/{id}`  | Eintrag löschen                                         |
| GET     | `/api/food/targets`       | Tagesziele                                              |
| PUT     | `/api/food/targets`       | Tagesziele ändern                                       |
| GET     | `/api/food/daily?from=&to=` | Tagessummen einer Spanne — liest die Weight-App        |
| GET     | `/api/food/status`        | Kennzahlen für die Statusboard-Karte                    |

POST-Body für einen Eintrag, entweder mit bekanntem Gericht:

```json
{ "date": "2026-08-31", "dishId": "9b2e3707-…", "grams": 300 }
```

…oder mit einem neuen, das dabei gleich gespeichert wird:

```json
{
  "date": "2026-08-31", "grams": 300,
  "dish": { "name": "Skyr mit Beeren", "kcal": 80, "proteinG": 8, "carbsG": 6, "fatG": 1.3, "portionG": 300 }
}
```

`/api/food/daily` liefert **nur Tage mit Einträgen**. Ein Tag ohne Eintrag ist
„unbekannt", nicht „nichts gegessen" — als 0 kcal in einer Kurve wäre das eine
Falschaussage.

## Zugriffsschutz

Die App liegt im **privaten Bereich von fherrmann.com** und stellt selbst keinen
Zugang aus. Maßgeblich ist der Cookie `fh_private`, der einmalig pro Gerät über

```
https://fherrmann.com/setup?token=<secret>
```

gesetzt wird — auf `Domain=.fherrmann.com`, gilt also auch hier. Der Token steht
an genau einer Stelle auf dem Server: `/etc/nginx/conf.d/private-mode.conf`.

Geprüft wird er **zweimal**:

1. **nginx** weist Anfragen ohne gültigen Cookie ab, bevor sie die App erreichen
   (Weiterleitung auf `fherrmann.com`, wo `/setup` liegt).
2. **Die App selbst** (`SecurityConfig` / `PrivateCookieAuthFilter`) prüft
   denselben Cookie noch einmal gegen `FH_PRIVATE_TOKEN` aus `/etc/food.env`.

Der zweite Schritt ist nicht überflüssig: die App lauscht zwar nur auf
`127.0.0.1`, aber ohne ihn wäre sie für alles auf dem Host offen, was diesen
Port erreicht — und ein Fehler im nginx-Block würde ein Ernährungstagebuch
stillschweigend freigeben.

`deploy/setup-food.sh` liest den Token direkt aus der nginx-Konfiguration, damit
Cookie und App-Prüfung nicht auseinanderlaufen können.

### CORS

Zwei Endpunkte werden von anderen Subdomains gelesen: `/api/food/daily` von
`weight.fherrmann.com` (kcal-Overlay in den Charts) und `/api/food/status` von
`status.fherrmann.com` (Statuskarte). Beide sind derselbe *Site* wie diese hier,
der Browser schickt den `SameSite=Lax`-Cookie also mit — CORS ist nur nötig,
weil die *Origin* eine andere ist. Deshalb `allowCredentials` plus eine
konkrete Origin-Liste (`food.cors.allowed-origins`): mit `*` verbietet die
Spezifikation das Mitsenden von Cookies.

## Lokal starten

Voraussetzung: JDK 25 (der Gradle-Wrapper liegt im Repo).

```bash
./gradlew bootRun
```

Danach den lokalen Cookie setzen und <http://localhost:48180> öffnen — z. B. in
der Browser-Konsole:

```js
document.cookie = 'fh_private=changeme-local-token; path=/';
```

## Tests

```bash
./gradlew test
```

- **`FoodServiceTest`** — Tagessummen, Gramm-Rechnung, Gerichte-Upsert über den
  Namen, Unveränderlichkeit bereits erfasster Einträge, Spannen-Abfrage, Validierung.
- **`FoodRepositoryTest`** — fehlende Datei ⇒ Defaults, Round-Trip.
- **`FoodControllerTest`** — Cookie-Prüfung (403 ohne/mit falschem Cookie, 200 mit
  richtigem) und die CORS-Header genau auf den beiden freigegebenen Endpunkten.

## Konfiguration

In `src/main/resources/application.properties`:

| Property                    | Default                                             | Bedeutung                          |
|-----------------------------|-----------------------------------------------------|-------------------------------------|
| `food.data-file`            | `data/food.json`                                    | Datendatei (relativ zum Arbeitsdir) |
| `server.port`               | `48180`                                             | Interner Port hinter nginx          |
| `server.address`            | `127.0.0.1`                                         | Lauscht nur lokal                   |
| `food.security.token`       | `changeme-local-token` (env: `FH_PRIVATE_TOKEN`)    | Der geteilte Privat-Cookie          |
| `food.cors.allowed-origins` | `weight.` + `status.fherrmann.com`                  | Origins für `/daily` und `/status`  |

## Architektur

```
com.fherrmann.food
  model/       Nutrients, Dish, FoodEntry, FoodData   (Domänenmodell)
  dto/         DaySummary, DayTotal, StatusInfo, …    (API-Transferobjekte)
  repository/  FoodRepository                         (JSON-I/O)
  service/     FoodService                            (Regeln, kein HTTP)
  controller/  FoodController                         (HTTP)
  security/    SecurityConfig, PrivateCookieAuthFilter (geteilter Cookie + CORS)
```

## Deployment

```
Internet (443) → Lightsail → WireGuard → Homeserver:443 (nginx, TLS, fh_private-Gate)
                                                 │
                                                 ▼
                                       Homeserver:48180 (Spring Boot, nur localhost)
```

| Was | Wo |
|---|---|
| Repo/Build | `~/services/food` |
| Laufzeit | `/opt/food` (`app.jar` + `data/`) |
| Dienst | `food.service`, User `food` |
| Token | `/etc/food.env` (aus `/etc/nginx/conf.d/private-mode.conf`) |
| nginx | `/etc/nginx/sites-available/food.fherrmann.com` |

**Erstinstallation** — legt User, Verzeichnisse, Token-Datei, systemd-Unit,
Zertifikat und nginx-Site an, idempotent:

```bash
ssh -t HeimServerRemote '~/scripts/setup-food.sh'
```

Das `-t` ist Pflicht: ohne TTY kann sudo nicht nach dem Passwort fragen und das
Skript bricht ohne jede Ausgabe ab. Voraussetzung ist ein gesetzter DNS-Eintrag
für `food.fherrmann.com`, sonst scheitert certbot.

**Spätere Updates** (baut auf dem Server, sichert das alte Jar, rollt bei einem
fehlgeschlagenen Health-Check automatisch zurück):

```bash
ssh -t HeimServerRemote '~/scripts/update-food.sh'
```

Beide Skripte liegen im Repo unter `deploy/` und werden von dort nach
`~/scripts/` kopiert. `/opt/food/data/` fasst keines davon an.
