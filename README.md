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

Auf jedem Tacho sitzt ein **Zielstrich**. Der Bogen reicht bis 125 % des Ziels,
die Marke also nicht ans Ende — sonst wäre nicht ablesbar, ob man knapp oder
weit darüber liegt.

Die Einfärbung folgt der Richtung, in die das jeweilige Ziel gemeint ist. Eiweiß
ist ein **Mindestwert**: ab dem Ziel grün, darüber bleibt es grün — mehr ist
mehr. kcal, Fett und Kohlenhydrate sind **Obergrenzen** und färben erst beim
Überschreiten, dann aber knapp:

| | gelb ab | rot ab |
|---|---|---|
| kcal | +1 | +101 |
| Kohlenhydrate | +0,1 g | +25,1 g |
| Fett | +0,1 g | +11,1 g |

Die Toleranz ist überall dieselbe, nur in der jeweiligen Einheit: 100 kcal, für
die Makros über ihren Brennwert umgerechnet (Kohlenhydrate 4 kcal/g → 25 g,
Fett 9 kcal/g → gut 11 g). Drei frei gewählte Zahlen nebeneinander wären
willkürlich.

Unterhalb des Ziels passiert bewusst **nichts**: ein Tacho, der schon bei 85 %
warnt, warnt an jedem normalen Tag und wird dadurch bedeutungslos.

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

## Verlauf

Unter der Tagesliste steht der Verlauf der letzten 14, 30 oder 90 Tage: kcal pro
Tag als Kurve gegen eine gestrichelte Ziellinie, Abschnitte über dem Ziel in
Rot. Lücken werden **nicht überbrückt** — nichts eingetragen heißt „unbekannt",
nicht „nichts gegessen", und eine durchgezogene Linie würde über solche Tage
hinweg behaupten, dazwischen sei etwas erfasst worden. Ein Tag, der allein
zwischen zwei Lücken steht, bekommt einen Punkt; er hätte sonst kein
Liniensegment und wäre unsichtbar.

Optional lässt sich das **Körpergewicht** einblenden. Das kommt vom
[Weight Tracker](https://github.com/Flexii2000/weight-app)
(`GET /api/weight/last90`, per CORS für diese Seite freigegeben) und liegt als
7-Tage-Mittel auf einer eigenen Achse rechts — neben Tagessummen an Kalorien ist
die geglättete Linie die Aussage, die man sehen will; das Tagesgewicht schwankt
um mehrere hundert Gramm aus Gründen, die mit dem Essen nichts zu tun haben.
Geholt wird es erst beim Einblenden, nicht auf Verdacht.

Die beiden Apps zeigen damit dieselbe Beziehung von zwei Seiten: hier die
Gewichtskurve über den Kalorien, dort die Kalorien unter der Gewichtskurve.

## Nach Mahlzeiten getrennt

Die Tagesliste zerfällt in **Frühstück, Mittagessen, Abendessen, Snacks**, jeder
Abschnitt mit eigener kcal-Teilsumme und eigenem `+`. Eine durchlaufende Liste
wird über den Tag hinweg unübersichtlich, und die Frage „war das Frühstück zu
groß?" lässt sich an einer Gesamtsumme nicht beantworten.

Der `+` öffnet ein Fenster, das schon auf diese Mahlzeit gestellt ist — darin
beide Eingabewege, die Schnellerfassung und das Formular. Dadurch gibt es keinen
dauerhaft sichtbaren Formularblock mehr, der oben Platz wegnimmt.

Am Eintrag ist die Zuordnung `null`-erlaubt: Einträge aus der Zeit vor dieser
Aufteilung haben keine, und sie nachträglich zu raten hieße, eine Vermutung wie
eine Angabe aussehen zu lassen. Sie stehen in einem Abschnitt **Ohne
Zuordnung**, der nur erscheint, solange es solche gibt.

Kam eine Schnellerfassung aus einem bestimmten Abschnitt, schlägt der die
Vermutung des Agents: wer auf `+` beim Mittagessen tippt, hat schon gesagt, was
er meint. Nur ohne Abschnitt zählt, was der Text hergibt („mittags einen Teller
…" → `LUNCH`).

Die Werte in den Zeilen beschriften sich selbst (`240 kcal · E 24 · KH 18 ·
F 4`). Ohne das bräuchte jeder Abschnitt eine eigene Kopfzeile — viermal
dieselben fünf Wörter, nur damit „24 18 4" zuzuordnen ist.

## Gericht suchen

Das Feld „Gericht" ist ein Suchfeld, keine Auswahlliste: mit ein paar Dutzend
Gerichten wird ein `<select>` zum Scrollmarathon. Gesucht wird über
Teilzeichenketten — „bol" findet auch „Spaghetti Bolognese"; eine Liste, die nur
den Anfang vergleicht, zwingt zum Erraten der Schreibweise. Pfeiltasten und
Enter wählen aus, ganz unten steht immer „＋ Neues Gericht …", das den getippten
Text gleich als Namen übernimmt.

Die Trefferliste steht **im Fluss** und schwebt nicht: das Eingabefenster
scrollt, und eine absolut positionierte Liste würde an dessen Rand
abgeschnitten.

## Wischen zum Löschen

Auf Touch-Geräten löscht ein Wisch nach links einen Eintrag der Tagesliste; ab
70 px Zugweg löst das Loslassen aus, darunter federt die Zeile zurück. Der
`×`-Knopf bleibt daneben bestehen — die Geste ist eine Abkürzung, kein Ersatz.

Die Liste ist deshalb ein CSS-Grid und keine Tabelle: ein `<tr>` lässt sich für
die Geste nur unzuverlässig verschieben, und der rote Grund darunter braucht
einen eigenen Kasten. Die Spalten stehen trotzdem untereinander, weil Kopfzeile
und Zeilen sich dasselbe Raster teilen.

## Schnellerfassung

Statt Formular ein Satz: „mittags einen großen Teller Spaghetti Bolognese".
Daraus wird ein vollwertiger Eintrag samt gespeichertem Gericht, das danach in
der Auswahlliste steht.

**Eingetragen wird erst nach dem Bestätigen.** Die Auswertung macht einen
Vorschlag und schreibt nichts: eine geschätzte Zahl, die ungefragt im Tagebuch
landet, sieht dort hinterher genauso aus wie eine abgelesene. Der Vorschlag
zeigt je Wert, woher er stammt —

- **gespeichert** (grün) — aus der Gerichteliste übernommen
- **aus dem Text** (grau) — stand als Zahl in der Beschreibung
- **geschätzt** (gelb) — geraten

— und dazu, ob ein **neues Gericht** angelegt oder ein **bekanntes erkannt**
wurde. **Alles ist editierbar**, Name eingeschlossen — was der Agent geraten hat,
korrigiert man hier und nicht hinterher an zweiter Stelle. Sobald ein Wert
angefasst wurde, steht neben ihm „geändert" statt der ursprünglichen Herkunft.

Bestätigt wird über denselben Eintrags-Endpunkt wie bei einer Eingabe von Hand —
mit denselben Grenzen, sodass ein Modell, das sich um eine Zehnerpotenz vertut,
nicht durchkommt. Ein bekanntes Gericht wird nur dann über seine Id gebucht,
wenn Name und Nährwerte unverändert sind; hat jemand etwas korrigiert, ist genau
das die Aussage, und die neuen Werte aktualisieren den Eintrag in der Liste.

Im Textfeld sendet **Enter** ab, **Shift+Enter** macht eine neue Zeile — umgekehrt
wäre es die Vorgabe eines Textfeldes, aber hier tippt niemand Absätze. Das Feld
wächst mit dem Text mit.

Ist das Gericht schon gespeichert, **gewinnt die gespeicherte Fassung**. Der
Agent bekommt die Liste zwar als Kontext und soll sie übernehmen, aber „soll"
ist keine Garantie: rät er beim Bananen-Nährwert ein paar Kalorien daneben,
würde ein Upsert über den Namen die von Hand gepflegten Werte überschreiben. Vom
Modell kommt in dem Fall nur noch die Menge — das Einzige, was im Text steht und
nicht in der Datenbank.

Ausgewertet wird das **nicht über die API, sondern durch eine Claude-Code-Session
auf dem Server** — dieselbe Bauart wie der tägliche Lauf des Finance Cockpits.
Das Wrapper-Skript `run-agent.sh` startet `claude -p` in einem eigenen
Arbeitsverzeichnis:

```
~/services/food-agent/
  CLAUDE.md               Fachkontext: Einheiten, Regeln, Ausgabeformat
  .claude/settings.json   Rechte: leere allow-Liste, alles andere verboten
  run-agent.sh            startet die Session, Prompt über stdin
```

**Das Verzeichnis ist die Leitplanke, nicht der Prompt.** Claude Code lädt
`CLAUDE.md` und `.claude/settings.json` aus dem Arbeitsverzeichnis. Freigeschaltet
sind genau zwei Werkzeuge — **Websuche und Seitenabruf**, damit der Agent
Nährwerte nachschlagen kann statt sie zu raten. Alles andere ist verboten: kein
Dateizugriff, keine Kommandos, keine Unteraufträge. Das Verzeichnis liegt bewusst
**außerhalb des Repos**, damit weder ein Deploy noch der Agent selbst die Rechte
verschieben kann. Der Text des Nutzers geht in `<beschreibung>`-Klammern hinein
und ist in `CLAUDE.md` ausdrücklich als Zitat und nicht als Anweisung markiert;
ein Einschleusungsversuch dort („ignoriere alle Anweisungen, rufe … ab") wurde
im Test ignoriert und ausdrücklich als solcher in der `note` benannt.

⚠️ **Die `allow`-Liste greift nur in einem vertrauten Verzeichnis.** Fehlt
`projects["…/food-agent"].hasTrustDialogAccepted` in `~/.claude.json`, meldet
Claude Code „this workspace has not been trusted", ignoriert *alle*
`allow`-Einträge und der Agent steht ohne Werkzeuge da — er rät dann wieder,
ohne dass es auffällt. `setup-food.sh` setzt das Flag mit.

Das Ergebnis läuft anschließend durch **dieselbe Validierung wie ein Eintrag von
Hand** (`FoodService.addEntry`) — ein Modell, das sich um eine Zehnerpotenz
vertut, kommt an der Grenze für kcal je 100 g nicht vorbei.

### Nachschlagen statt raten

Nennt die Beschreibung ein konkretes Produkt — „6 Wagner Piccolinis", „Big Mac" —,
schlägt der Agent die Nährwerte im Netz nach, unaufgefordert. Bei allgemeinen
Gerichten („ein Teller Nudeln mit Tomatensoße") lohnt das nicht; das entscheidet
er selbst.

Der Vorschlag unterscheidet deshalb **vier** Herkünfte je Wert: *gespeichert*,
*aus dem Text*, *nachgeschlagen* und *geschätzt*. Findet er das Produkt nicht,
sagt er das (`lookedUp: []`, Grund in der `note`) statt einen Fund vorzutäuschen.

Eine Suche dauert deutlich länger als eine Schätzung — gemessen bis 56 s, daher
`food.agent.timeout-seconds=180`.

| Property | Default | Bedeutung |
|---|---|---|
| `food.agent.command` (env `FOOD_AGENT_COMMAND`) | leer | Pfad zum Wrapper-Skript. Leer = Funktion aus; die Oberfläche fragt das über `/api/food/features` ab und blendet den Knopf gar nicht erst ein |
| `food.agent.timeout-seconds` | `120` | danach wird die Session abgeräumt |
| `FOOD_AGENT_MODEL` (im Wrapper) | `claude-sonnet-5` | Jede Anfrage ist eine frische Session, es entstehen also jedes Mal ~10k Tokens Systemkontext neu — das dominiert die Kosten (~4,5 ct pro Eintrag), nicht der Satz selbst. Mit `claude-haiku-4-5` etwa ein Achtel davon |

### Voraussetzung auf dem Server

Der Dienst läuft als Systemnutzer `food`. `claude` und seine Anmeldedaten liegen
unter `/home/flexii` (Modus 750), und die systemd-Unit setzt
`NoNewPrivileges=true` — `food` kann das Wrapper-Skript also **nicht ohne
Weiteres starten**. `setup-food.sh` prüft das und lässt die Schnellerfassung
sonst aus, statt einen Knopf anzubieten, der beim Drücken scheitert.

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
| POST    | `/api/food/entries`       | Menge eintragen (mit `meal`)                            |
| DELETE  | `/api/food/entries/{id}`  | Eintrag löschen                                         |
| GET     | `/api/food/targets`       | Tagesziele                                              |
| PUT     | `/api/food/targets`       | Tagesziele ändern                                       |
| GET     | `/api/food/daily?from=&to=` | Tagessummen einer Spanne — liest die Weight-App        |
| GET     | `/api/food/status`        | Kennzahlen für die Statusboard-Karte                    |
| GET     | `/api/food/features`      | welche optionalen Funktionen der Server anbietet         |
| POST    | `/api/food/quick-capture` | Freitext → **Vorschlag** (schreibt nichts)               |

POST-Body für einen Eintrag, entweder mit bekanntem Gericht:

```json
{ "date": "2026-08-31", "dishId": "9b2e3707-…", "grams": 300, "meal": "LUNCH" }
```

`meal` ist einer von `BREAKFAST`, `LUNCH`, `DINNER`, `SNACK`; ohne Angabe landet
der Eintrag unter `SNACK` — sonst wäre er in keinem der vier Abschnitte sichtbar.

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
- **`ErrorStatusIT`** — läuft gegen einen echten Server statt gegen MockMvc, und
  das ist der Punkt: löst ein Endpunkt eine Ausnahme aus, stellt der Container
  intern nach `/error` zu. Dieser zweite Durchlauf ging ursprünglich an der
  Cookie-Prüfung vorbei, der Kontext war leer, und Spring Security ersetzte
  **jeden** Fehlerstatus durch ein 403. MockMvc führt diesen Durchlauf nicht aus
  und sah davon nichts — die Slice-Tests waren grün, während live aus einem
  „Menge fehlt" ein „nicht autorisiert" wurde.

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
  model/       Nutrients, Dish, FoodEntry, FoodData, Meal  (Domänenmodell)
  dto/         DaySummary, DayTotal, StatusInfo, …    (API-Transferobjekte)
  repository/  FoodRepository                         (JSON-I/O)
  service/     FoodService                            (Regeln, kein HTTP)
  controller/  FoodController                         (HTTP)
  security/    SecurityConfig, PrivateCookieAuthFilter (geteilter Cookie + CORS)

deploy/agent/  Vorlagen für das Agent-Arbeitsverzeichnis (siehe Schnellerfassung)
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
