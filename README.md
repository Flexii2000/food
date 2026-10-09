# Food – Kalorienzähler

Kleine Spring-Boot-Anwendung, die den Tagesbedarf einer einzelnen Person gegen das
hält, was sie gegessen hat. Im Kern auf **vier Werte** beschränkt: kcal, Eiweiß,
Kohlenhydrate, Fett. Wer mehr will, bekommt je Person die übrigen Zeilen der
Nährwerttabelle und Mikronährstoffe dazu (siehe unten). Keine Rezeptdatenbank,
keine Barcode-Scans.

Läuft unter <https://food.fherrmann.com> und gehört zum
[Weight Tracker](https://github.com/Flexii2000/weight-app) — dort blendet ein
Umschalter die hier erfassten kcal in die Gewichtskurven ein.

## Anzeige

Oben drei kleine Tachos nebeneinander für die **bisher verzehrten** Makros
(Eiweiß, Fett, Kohlenhydrate), darunter ein großer Tacho für die **noch übrigen**
kcal, daneben als bloße Zahl der verzehrte Wert.

Auf jedem Tacho sitzt ein **Zielstrich**, als Kerbe neben dem Bogen statt quer
hindurch — eine Linie mitten durch die Füllung zerschneidet sie optisch. Der
Bogen reicht bis 125 % des Ziels, die Marke also nicht ans Ende: sonst wäre
nicht ablesbar, ob man knapp oder weit darüber liegt.

Die Bögen sind mit einem Farbverlauf in der Tonart des Werts gezeichnet
(`<linearGradient>` einmal im Dokument, per `url(#…)` referenziert), ohne
Schein und Schatten — die Tönung ist die Information. Die Farben der Stopps
stehen als CSS-Variablen: das helle und das dunkle Schema bekommen je eigene
Nuancen, die Bedeutung bleibt dieselbe wie in den Apps.

Hell ist der Standard, dunkel folgt der Systemeinstellung
(`prefers-color-scheme`); alle Farben kommen aus CSS-Variablen, auch die des
Diagramms. Ab 1100 px Breite stehen Tagesübersicht und Mahlzeiten
nebeneinander, Verlauf und Einstellungen darunter.

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

### Verbrauch und Defizit (seit 2026-10)

Unter dem kcal-Tacho stehen zwei Zeilen aus der Energiebilanz des
[Weight Trackers](https://github.com/Flexii2000/weight-app) (`GET /api/energy`,
dort per CORS für diese Seite freigegeben): `Verbrauch 2.610 kcal · Uhr 2.840 ·
−8 %` und `Defizit 460 kcal` bzw. `Überschuss …`. Der Verbrauch ist Ruhe- plus
Aktivenergie aus Apple Health bzw. Health Connect, **am Gewicht kalibriert** —
gerechnet wird drüben, hier wird nur gezeigt. Heute steht ein „≈“ davor: die
Ruheenergie ist auf den ganzen Tag hochgerechnet, die Aufnahme ist die bisherige.
Für künftige Tage und Tage ohne Werte der Uhr fehlen die Zeilen; ein Tag, der
nicht als getrackt gilt (unter 80 % des Ziels und nicht Frühstück, Mittag und
Abend), hat Verbrauch, aber kein Defizit. Eine Fehlermeldung erscheint nur, wenn
es für die Person schon einmal Verbrauchswerte gab — wer keine Uhr hat, sieht
keine rote Zeile.

Im **Verlauf** sind „Verbrauch ⌀“ und „Defizit ⌀“ vorgewählt: der Verbrauch im
7-Tage-Mittel auf der kcal-Achse, das Defizit im 7-Tage-Mittel (nur getrackte
Tage, `deficitAvg7`) auf einer eigenen Skala rechts mit gestrichelter Nulllinie,
und dazu ist die Fläche zwischen „Verbrauch ⌀“ und „kcal ⌀“ eingefärbt — Petrol,
wo der Verbrauch darüber liegt, Rot bei einem Überschuss. Farben und Regeln wie
in den Apps (Weight Tracker, `docs/HEALTHY-CONTRACT.md` §5).

## Detailwerte je Person

Für alle gibt es kcal und die drei Makros. Wer mehr will, bekommt die ganze
**Nährwerttabelle der EU** dazu: davon gesättigte Fettsäuren, davon Zucker,
Ballaststoffe, Salz — eingestellt je Person (`FOOD_DETAILED_NUTRIENTS=torben`,
`setup-health-users.sh --detailed`). `/api/food/features` sagt den Oberflächen
mit `detailedNutrients`, ob sie die Felder anbieten.

Im Datenmodell sind das vier **optionale** Felder an `Nutrients`. Sie hängen am
Gericht (je 100 g) und wandern wie die übrigen Werte in die Kopie am Eintrag;
fehlen sie, stehen sie auch im JSON nicht. Felix' Tagebuch hat sie nie — seine
Antworten, seine iPhone-App und alles, was mitliest (Weight Tracker, Habits,
Statusboard), sehen dieselben vier Zahlen wie vorher.

- **Kein Ziel, kein Rest:** Detailwerte werden summiert, aber nicht gegen ein
  Tagesziel gerechnet — danach hat niemand gefragt.
- **Lücken werden gesagt:** hat ein Eintrag des Tages keine Angabe, steht der
  Feldname in `DaySummary.detailGaps`, und die Summe ist dort eine Untergrenze.
  Ein einziger Apfel ohne Angabe soll nicht den ganzen Tag Zucker wegwerfen,
  aber auch nicht als vollständig durchgehen.
- **Freiwillig, aber geprüft:** jeder Wert 0–100 g je 100 g; leer bleibt leer
  statt 0.
- **Schnellerfassung:** für diese Personen fragt der Auftrag die vier Werte mit
  ab (siehe `deploy/agent/CLAUDE.md`), mit derselben Herkunftsangabe je Wert.
  Bei allen anderen fragt er nicht und liest auch nichts, was ungefragt kommt.
- **Rundung:** Detailwerte auf zwei Stellen — Salz steht als „0,03 g" auf der
  Packung.

## Mikronährstoffe je Person

Ein Wunsch von Torben: sehen, ob er seinen Bedarf an Vitaminen und
Mineralstoffen deckt. Dafür gibt es vierzehn **Mikronährstoffe**, eingestellt
je Person (`FOOD_MICRONUTRIENTS=torben`, `setup-health-users.sh --micronutrients`);
`/api/food/features` meldet das als `micronutrients`.

| Vitamine | | Mineralstoffe | |
|---|---|---|---|
| `vitaminAUg` | Vitamin A (µg Retinol-Aktivitäts-Äquivalente) | `calciumMg` | Calcium |
| `vitaminDUg` | Vitamin D | `magnesiumMg` | Magnesium |
| `vitaminEMg` | Vitamin E | `potassiumMg` | Kalium |
| `vitaminCMg` | Vitamin C | `ironMg` | Eisen |
| `vitaminB2Mg` | Vitamin B2 | `zincMg` | Zink |
| `vitaminB12Ug` | Vitamin B12 | `iodineUg` | Jod |
| `folateUg` | Folat (µg Folat-Äquivalente) | `seleniumUg` | Selen |

Die Einheit steht im Schlüssel wie bei `proteinG`: `Mg` Milligramm, `Ug`
Mikrogramm. Die Reihenfolge ist überall dieselbe (`Micronutrient`).

- **Eine Zuordnung statt vierzehn Felder:** `Nutrients.micros` ist ein Objekt
  `{schlüssel: zahl}` — je 100 g am Gericht und in der Kopie am Eintrag, als
  Tagessumme in `consumed`. Ein fehlender Schlüssel heißt „keine Angabe", eine
  0 wird nie erfunden; ohne einen einzigen Wert fehlt das Feld ganz.
- **Nur für diese Personen:** anders als die Detailwerte speichert der Dienst
  `micros` nur für Freigeschaltete. Bei allen anderen wird das Feld in jeder
  Anfrage übergangen, ungeprüft, und keine Antwort bekommt ein neues Feld —
  `MicronutrientsApiTest` vergleicht Felix' Antworten Zeichen für Zeichen mit
  dem Stand davor. Einzige Ausnahme: `micronutrients: false` in `features`.
- **Teilsummen mit Lücken** wie bei den Detailwerten: `DaySummary.microGaps`
  nennt die Schlüssel, bei denen ein Eintrag des Tages keinen Wert hat.
- **Geprüft:** bekannter Schlüssel, endlich, ≥ 0, höchstens das Gegenstück von
  10 g je 100 g (10.000 mg bzw. 10.000.000 µg) — das fängt vor allem die
  Verwechslung von mg und µg ab. `PUT /dishes/{id}` ersetzt das Gericht ganz:
  wer `micros` hat, muss sie mitschicken.
- **Tagesziele mit Vorgabe:** `targets.micros` in `GET /targets` und im Tag.
  Hat jemand noch nie Mikro-Ziele gespeichert, gelten die DGE-Referenzwerte für
  Männer von 25 bis unter 51 Jahren (Stand 2026-09-27). Gespeichert werden sie
  getrennt als `microTargets` in `food.json`: fehlt das Feld, gilt die Vorgabe;
  eine leere Zuordnung heißt „bewusst ohne Ziel" — in `targets` ließe sich das
  nicht unterscheiden. `PUT /targets` ohne `micros` lässt die gespeicherten
  stehen, ältere Clients löschen also nichts. Ein Ziel muss größer als 0 sein.
- **Kein Rest:** Mikro-Ziele sind Mindestwerte wie das Eiweiß; die Oberflächen
  zeigen die Zielerreichung, `remaining` bleibt ohne `micros`.
- **Schnellerfassung:** der Auftrag fragt `microsPer100g` mit ab — Schätzen
  ist ausdrücklich erwünscht, sonst blieben fast alle Einträge leer (siehe
  `deploy/agent/CLAUDE.md`). Die Herkunft steht je Schlüssel in `valueSources`.
  Die gespeicherten Gerichte gehen ohne ihre Mikronährstoffe in den Auftrag:
  für ein bekanntes Gericht gelten ohnehin die gespeicherten Werte.

In der Weboberfläche: aufgeklappte Einträge zeigen die Werte bei ihrer Menge,
der Tag eine Übersicht mit der Zielerreichung je Nährstoff (auf dem Desktop
links unter den Detailwerten, auf dem Handy nach den Mahlzeiten), und die
Formulare für Gerichte, Vorschlag und Tagesziele einen Abschnitt mit den
vierzehn Feldern. Unter dem Ziel steht nie „100 %"; eine 0 als Tagesziel heißt
dort „kein Ziel".

## Veganer Modus

Ein Wunsch von Torben (Feature Request 340fa6ce): beim Erfassen nicht aus
Versehen ein nicht-veganes Gericht erwischen. Anders als Detailwerte und
Mikronährstoffe ist das **keine Serverfreigabe**, sondern ein Schalter, den jede
Person selbst umlegt — im Web unter „Tagesziele", in der Android-App im Menü.

- **Kennzeichen am Gericht:** `Dish.vegan` ist `true`, `false` oder fehlt
  (unbekannt). Ebenso `vegan` im Rumpf von `POST/PUT /dishes` und im neuen
  Gericht eines Eintrags. `PUT` ersetzt das Gericht ganz: fehlt `vegan`, ist es
  danach unbekannt. Einträge tragen kein Kennzeichen.
- **Schalter:** `PUT /api/food/vegan-mode` mit `{enabled}` antwortet mit den
  neuen `features`, dort steht `veganMode` (fehlt nie). Gespeichert wird er als
  `veganMode` in der `food.json` der Person; fehlt das Feld, war er nie an.
- **Erstes Einschalten markiert:** alle Gerichte mit unbekanntem Kennzeichen
  werden einmalig `true`, ausdrücklich `false` bleibt. Späteres Aus- und
  Einschalten markiert nichts mehr — was dann noch unbekannt ist, kam ohne
  Einordnung dazu.
- **Durchgesetzt im Dienst, solange er an ist:** ein Eintrag mit `dishId` eines
  Gerichts, das nicht `vegan: true` ist, wird mit 400 abgelehnt; ein neues
  Gericht (im Eintrag oder über `POST /dishes`) ohne `vegan` gilt als vegan, mit
  `vegan: false` ist es ein 400. Korrigieren per `PUT` auf `false` geht immer.
  `GET /dishes` liefert weiter alle Gerichte — filtern tun die Oberflächen.
- **Schnellerfassung:** wer den Modus je eingeschaltet hat, bekommt im Auftrag
  die Frage nach `vegan` (siehe `deploy/agent/CLAUDE.md`); der Vorschlag trägt
  dann `vegan` mit Herkunft in `valueSources.vegan`. Ein bekanntes Gericht
  bringt sein gespeichertes Kennzeichen mit (`stored`).
- **Felix bleibt unverändert:** wer den Modus nie einschaltet, bekommt jede
  Antwort wie zuvor, nur `features` hat `veganMode: false` —
  `VeganModeApiTest` vergleicht das Zeichen für Zeichen.

In der Weboberfläche: der Schalter unter „Tagesziele", ein Häkchen „Vegan" im
Formular für ein neues Gericht, in der Gerichteliste und im Vorschlag. Im Modus
zeigt die Suche nur vegane Gerichte, das Häkchen eines neuen Gerichts ist
gesetzt und gesperrt, ein nicht-veganer Vorschlag lässt sich nicht übernehmen,
und die Gerichteliste zeigt nicht-vegane gedämpft. Ein Gericht mit unbekanntem
Kennzeichen, dessen Häkchen niemand anfasst, wird ohne `vegan` gespeichert und
bleibt unbekannt.

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

Optional lässt sich das **Körpergewicht** einblenden, in zwei getrennt
schaltbaren Serien: das **7-Tage-Mittel** zeigt den Trend, der **Messwert** die
Schwankung. Wer nach einem Ausrutscher sucht, braucht den Tageswert; wer die
Richtung sehen will, stört er. Beide kommen vom
[Weight Tracker](https://github.com/Flexii2000/weight-app)
(`GET /api/weight/last90`, per CORS für diese Seite freigegeben) und liegen auf
einer eigenen Achse rechts. Ein Aufruf liefert ohnehin beides, also wird auch
beides abgelegt und die Auswahl erst beim Zeichnen getroffen — geholt aber erst
beim ersten Einblenden, nicht auf Verdacht.

Die Farben sind dieselben wie drüben: kcal gelb, 7-Tage-Mittel grün, Messwert
hellblau. Wer beide Seiten benutzt, soll nicht zweimal lernen müssen, welche
Linie was ist.

Anders als bei den kcal werden die Gewichtslücken **überbrückt**: ein nicht
gewogener Tag ist eine Lücke in der Messreihe, keine Aussage — während ein Tag
ohne kcal-Eintrag bedeutet, dass nichts erfasst wurde.

Die beiden Apps zeigen damit dieselbe Beziehung von zwei Seiten: hier die
Gewichtskurve über den Kalorien, dort die Kalorien unter der Gewichtskurve.

Vorgewählt steht neben dem kcal-Mittel der **Verbrauch im 7-Tage-Mittel**
(indigo, aus `/api/energy`, gestrichelt, solange das Fenster noch offen ist):
die Lücke zwischen beiden Kurven ist das Defizit.

## Nach Mahlzeiten getrennt

Die Tagesliste zerfällt in **Frühstück, Mittagessen, Abendessen, Snacks**, jeder
Abschnitt mit eigenem `+` und einer Teilsumme gegen sein eigenes kcal-Ziel
(`536/575 kcal`). Eine durchlaufende Liste wird über den Tag hinweg
unübersichtlich, und die Frage „war das Frühstück zu groß?" lässt sich an einer
Gesamtsumme nicht beantworten.

### Die Mahlzeitenziele sind Anteile, keine Zahlen

Gespeichert wird nicht „575 kcal fürs Frühstück", sondern **25 %**. Voreinstellung
für einen Tag ist 25 / 35 / 30 / 10 (bei 2300 kcal also 575 / 805 / 690 / 230).

Der Grund: so bleiben die Mahlzeitenziele stimmig, wenn das Tagesziel sich
ändert — bei 2000 kcal wird das Frühstück automatisch zu 500 —, statt an zweiter
Stelle von Hand nachgezogen werden zu müssen. Und weil die Anteile in Summe
100 % ergeben müssen (das prüft der Server und die Eingabe zeigt die Summe live),
summieren sich die vier Ziele immer genau auf das Tagesziel.

Die Teilsummen sind bewusst **nicht eingefärbt**: der große Tacho trägt das
Urteil über den Tag, und vier weitere Warnflächen machen die Seite zur Nörgelei.

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

**Auf dem Handy bleiben nur Name und kcal stehen.** Sechs Zahlen nebeneinander
liest auf einem schmalen Display niemand im Vorbeigehen; die Teilsumme steht
ohnehin in der Abschnittszeile, Menge und Makros stehen auf dem Desktop.

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

### Mit Foto

Die iOS-App kann statt oder zusätzlich zum Text ein **Foto der Mahlzeit**
schicken (`imageJpegBase64`, JPEG als Base64, von der App auf 1280 px und
unter 700 kB verkleinert). Der Text ist dann Kontext — „die kleine Portion",
„mit extra Käse" — und darf leer sein.

Der Dienst legt das Bild als `data/inbox/<auftrag>.jpg` ab (nur unter `data/`
darf er schreiben, siehe `deploy/food.service`), nennt dem Agenten den Pfad
und löscht die Datei nach der Auswertung — gelungen oder nicht. Der Agent
darf genau diesen Ordner lesen (`Read(//opt/food/data/inbox/**)` in
`deploy/agent/.claude/settings.json`), sonst weiterhin keine Datei. Aus dem
Bild schätzt er Gericht, Menge (Tellergrösse, Füllung) und Nährwerte; alles
davon kommt als `estimated` zurück, ein Foto ist keine abgelesene Zahl.

### Nachschlagen statt raten

Nennt die Beschreibung ein konkretes Produkt — „6 Wagner Piccolinis", „Big Mac" —,
schlägt der Agent die Nährwerte im Netz nach, unaufgefordert. Bei allgemeinen
Gerichten („ein Teller Nudeln mit Tomatensoße") lohnt das nicht; das entscheidet
er selbst.

Der Vorschlag unterscheidet deshalb **vier** Herkünfte je Wert: *gespeichert*,
*aus dem Text*, *nachgeschlagen* und *geschätzt*. Findet er das Produkt nicht,
sagt er das (`lookedUp: []`, Grund in der `note`) statt einen Fund vorzutäuschen.

### Auftrag statt offener Leitung

Eine Auswertung mit Nachschlagen dauert bis zu einer Minute. Sie läuft deshalb
**als Hintergrundauftrag**, nicht in der HTTP-Anfrage:

| | |
|---|---|
| `POST /api/food/quick-capture` | nimmt an, antwortet in ~50 ms mit `202` und einer Auftragsnummer |
| `GET /api/food/quick-capture/{id}` | Stand: `running` (mit Laufzeit), `done` (mit Vorschlag) oder `failed` (mit Grund) |

Die Oberfläche fragt alle zwei Sekunden nach und zeigt die laufende Sekundenzahl
im Fortschrittsbalken.

Der Grund ist nicht Eleganz, sondern ein konkreter Ausfall: vorher hing die
Anfrage bis zu einer Minute am Draht und lief in nginx' Vorgabe von 60 s. Weil
die Seite HTTP/2 spricht, kam dabei **kein 504** an, sondern ein zurückgesetzter
Stream — im Browser „Failed to fetch", ohne dass irgendwo ein Fehlerstatus
auftauchte. Nachgemessen: HTTP 000 nach 60,17 s. Denselben Strick hätte jede
weitere Zwischenstation gespannt; der Weg geht über eine VPS und einen
WireGuard-Tunnel, beide mit eigenen Timeouts. Jetzt ist jede einzelne Anfrage
kurz, und die Frage stellt sich nicht mehr.

Aufträge liegen **nur im Speicher**, einer nach dem anderen (ein Arbeitsthread —
jede Auswertung kostet Geld, zwei Klicks sollen sich anstellen statt zwei
Sessions zu bezahlen). Ein Neustart verliert sie; die Oberfläche bekommt dann
ein `404` und sagt das, statt endlos zu warten. Für eine Auswertung, die eine
Minute dauert, wäre alles andere unverhältnismäßig.

Der agenteneigene Timeout bleibt bei `food.agent.timeout-seconds=180` — gemessen
wurden bis 56 s.

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

## Feature Requests

Unter <https://fherrmann.com/feature-requests/> schreibt Torben (oder jede andere
Person mit Token) einen Wunsch in eigenen Worten auf — für **Healthy, coHabit,
Fokus oder die Einkaufsliste** (Auswahl oben im Formular, vorwählbar per
`/feature-requests/neu?app=cohabit`). Claude entwirft daraus eine
**Story Card** — Titel, User Story („Als … möchte ich …, damit …") und
Akzeptanzkriterien —, die Person **bearbeitet jedes Feld und gibt sie frei**, und
beim Absenden entsteht in Felix' To-Do eine Unteraufgabe. Die Liste zeigt jeder
Person ihre Anfragen mit dem Stand **offen** oder **erledigt**; erledigt ist eine
Anfrage, sobald Felix ihre Unteraufgabe abhakt.

| | |
|---|---|
| Seite | `/feature-requests/` (Liste), `/feature-requests/neu`, `/feature-requests/<id>` (Kartenseite, dorthin zeigt der Link im To-Do). Statisch unter `static/feature-requests/`, die Adressen liefert `FeatureRequestPage` |
| Wer was sieht | die Eigentümerin alle Anfragen, jede andere Person ihre eigenen; eine fremde Karte ist 404, als gäbe es sie nicht |
| Anmeldung | wie überall hier (`health_token`, Bearer, `fh_private`), **kein** `permitAll` — ohne Token sind auch Seite und Dateien 403 |
| Ablage | `data/feature-requests.json`, eine Datei für alle, jede Anfrage mit `author`. Atomar geschrieben (daneben schreiben, umbenennen) |
| Originaltext | bleibt neben der Karte stehen, damit sich die Karte am Wortlaut messen lässt |
| App | `app` je Anfrage (`healthy`, `cohabit`, `fokus`, `einkaufsliste`, Liste in `FeatureApp`). Fehlt sie — Anfragen von vor der Auswahl —, gilt Healthy. Der Story-Agent bekommt sie in der Zeile vor dem Zitat, was die Apps können, steht in seiner `CLAUDE.md` |

nginx reicht `location /feature-requests/` in `sites-available/fherrmann.com`
unverändert an `:48180` weiter, **ohne Privat-Gate** — Torben hat keinen
`fh_private`, sondern seinen `health_token`, und der gilt für
`Domain=fherrmann.com`. Die Seite verweist nur relativ auf ihre Dateien, denn
unter fherrmann.com gehört die Wurzel der Landing Page.

### Claude entwirft, die Person gibt frei

Ein **zweiter Agent** neben der Schnellerfassung, mit eigenem Verzeichnis:

```
~/services/story-agent/        (Vorlage: deploy/story-agent/)
  CLAUDE.md                    Auftrag, Ausgabeformat, Produktkontext
  .claude/settings.json        Rechte: jedes Werkzeug verboten
  run-agent.sh                 startet claude -p, Prompt über stdin
```

Nicht der Agent der Schnellerfassung: dessen `CLAUDE.md` ist ganz auf Mahlzeiten
zugeschnitten, und er darf im Netz nachschlagen. Dieser darf **nichts** — keine
Websuche, keine Datei, kein Kommando. `--tools ""` im Skript nimmt der Session
jedes Werkzeug schon beim Start, die deny-Liste in `settings.json` ist die zweite
Schranke dahinter. Der Wunsch geht in `<wunsch>`-Klammern hinein und ist in
`CLAUDE.md` als Zitat markiert, nie als Anweisung; steht im Text selbst ein
`</wunsch>`, wird es vorher entschärft.

Wie die Schnellerfassung **ein Auftrag im Hintergrund**: der Start antwortet
sofort mit einer Auftragsnummer, die Seite fragt alle anderthalb Sekunden nach
(`running` → `done` oder `failed`), Zeitlimit 120 s. Ein Arbeitsthread für alle,
höchstens drei offene Entwürfe je Person. Scheitert der Entwurf (Login
abgelaufen, Zeitlimit, unlesbare Antwort), kommt eine deutsche Meldung, und der
Editor öffnet leer mit dem eigenen Text darunter. Ohne `FOOD_STORY_AGENT_COMMAND`
gibt es gar keinen Entwurf: der Knopf heißt dann „Weiter", und die Person schreibt
die Karte selbst — die Funktion geht trotzdem.

Entwerfen darf, wer die Seite sieht; es läuft auf Felix' Claude-Login. Das Modell
steht in `STORY_AGENT_MODEL` (Vorgabe `claude-sonnet-5`) und lässt sich in
`/etc/food.env` umstellen — sudo reicht genau diese Variable an das Skript durch
(`deploy/sudoers-story-agent`), das sie nur in der Form eines Modellnamens annimmt.

### Die Unteraufgabe im To-Do

Beim Absenden wird die Anfrage **zuerst gespeichert**, erst dann legt
`FeatureRequestTodos` im To-Do an:

1. den Bereich **„Server"** — fehlt er, wird er angelegt (Groß- und Kleinschreibung
   egal, wie im To-Do selbst);
2. darin die offene Aufgabe mit dem **Namen der App** („Healthy", „coHabit",
   „Fokus", „Einkaufsliste") der obersten Ebene — fehlt sie oder ist die
   vorhandene erledigt, eine neue;
3. darunter die Unteraufgabe: Titel der Karte, `link` =
   `https://fherrmann.com/feature-requests/<id>` (Basis in `FOOD_FEATURE_REQUESTS_URL`).

Stammt die Anfrage **nicht von Felix**, gibt das Anlegen der Unteraufgabe eine
`notification` mit („Feature Request · coHabit“ / „Torben: <Titel>“). Die
schickt das To-Do als Push an Fokus, sobald die Aufgabe steht; ein Tipp öffnet
die Karte. Weil sie am Anlegen hängt, kommt sie bei einem Ausfall mit dem
Nachlauf, und eine übernommene Aufgabe (siehe unten) meldet sich nicht doppelt.
Ein To-Do von vor der Benachrichtigung übergeht das Feld still.

Angemeldet wird mit dem Privat-Cookie, es ist derselbe `FH_PRIVATE_TOKEN`. Das
To-Do antwortet auf jedes Anlegen mit dem ganzen Brett statt mit einer Id; die
neue Id ergibt sich aus dem Vergleich vorher/nachher, bei zwei neuen entscheiden
Titel und Elternaufgabe.

**Fällt das To-Do aus, geht nichts verloren.** Die Anfrage bleibt ohne `todoId`
gespeichert, und ein Nachlauf alle zehn Minuten legt fehlende Unteraufgaben an.
Kam die Antwort auf ein Anlegen nicht mehr an, erkennt er die schon vorhandene
Aufgabe an ihrem Link und übernimmt sie, statt sie doppelt anzulegen — das setzt
ein To-Do voraus, das `link` speichert; das ältere vergisst das Feld, die
Unteraufgabe entsteht dort trotzdem, nur ohne Link.

Der Stand kommt aus dem Brett (`GET /api/board?all=true`, `doneAt` der
Unteraufgabe): abgehakt heißt erledigt, der Haken zurück wieder offen. Antwortet
das To-Do nicht oder ist die Aufgabe gelöscht, gilt der zuletzt gesehene Stand,
den der Nachlauf mitschreibt.

### Schnittstelle

| Methode | Pfad | |
|---|---|---|
| GET | `/feature-requests/api/features` | `{me, owner, drafting}` — wer fragt, ob alle Anfragen sichtbar sind, ob Claude entwirft |
| POST | `/feature-requests/api/drafts` | `{text}` → `202 {jobId, status, …}`; 503 ohne Agent |
| GET | `/feature-requests/api/drafts/{jobId}` | `{jobId, status, card, error, elapsedSeconds}`, `status` ist `running`, `done` oder `failed`; der einer anderen Person ist 404 |
| POST | `/feature-requests/api/requests` | `{originalText, title, story, acceptanceCriteria[]}` → 201 mit der Anfrage |
| GET | `/feature-requests/api/requests` | die sichtbaren Anfragen, neueste zuerst, je mit `status` (`open`/`done`), `doneAt`, `inTodo`, `url` |
| GET | `/feature-requests/api/requests/{id}` | eine Anfrage — nur für Autor und Eigentümerin |
| DELETE | `/feature-requests/api/requests/{id}` | 204 — nur die Eigentümerin, alle anderen 404; löscht auch die Unteraufgabe im To-Do |

Beim Löschen verschwindet zuerst die Anfrage, danach die Unteraufgabe
(`DELETE /todo/api/todos/{todoId}`). Ist die schon weg oder das To-Do nicht
erreichbar, bleibt die Anfrage trotzdem gelöscht; der Fehler steht nur im
Journal. Auf der Kartenseite hat nur die Eigentümerin den Knopf „Löschen".

Prüfungen: Titel 1–120 Zeichen (eine Zeile), Story 1–2000, bis zu 10
Akzeptanzkriterien mit je höchstens 300 (leere Zeilen fallen weg), Originaltext
höchstens 4000.

## Push: der Server meldet sich

Die Schnellerfassung dauert bis zu einer Minute. Die App muss dafür nicht offen
bleiben: ist ein Auftrag fertig, schickt der Server eine Benachrichtigung an
die angemeldeten Geräte **der Person, die ihn gestartet hat** — iPhones über
APNs, Android-Handys über Firebase Cloud Messaging.

**Warum das nicht in der App allein geht:** legt man das Handy weg, friert iOS
sie nach etwa dreißig Sekunden ein. Nur der Server läuft weiter, und nur er
weiß, wann die Auswertung fertig ist.

**Anmeldung:** `POST /api/food/devices` mit `{"token": "…"}` (iPhone) bzw.
`{"token": "…", "platform": "android"}`. Die Apps rufen das bei jedem Start
auf, weil iOS und Firebase die Kennung gelegentlich austauschen. Gespeichert
wird je Person in `devices.json` (APNs) und `devices-android.json` (Firebase) —
bei der Eigentümerin unter `data/`, bei allen anderen unter
`data/users/<name>/`, bewusst neben `food.json` und nicht darin: das Tagebuch
ist der Bestand, den man aufhebt, Gerätekennungen sind flüchtig. Meldet sich
eine Kennung für eine andere Person an, verschwindet sie bei der ersten — ein
umgewidmetes Handy bekäme sonst weiter fremde Benachrichtigungen. Lehnt der
Dienst eine ab (APNs: 410 oder `BadDeviceToken`; Firebase: `UNREGISTERED`),
fliegt sie raus; erst dann weiß man sicher, dass sie tot ist.

**Ohne Bibliothek.** APNs ist ein HTTP/2-POST mit einem signierten Token im
Kopf, und beides kann das JDK. Eine Abhängigkeit für dreißig Zeilen wäre mehr
Pflege als Ersparnis.

### Der Fallstrick: DER gegen JOSE

Die JCA liefert eine ES256-Signatur als DER-Struktur mit variabler Länge, JWT
erwartet 64 rohe Bytes — R und S, je 32, rechtsbündig. Wer die DER-Bytes direkt
einsetzt, bekommt von Apple `InvalidProviderToken` und sucht den Fehler beim
Schlüssel oder der Team-ID, wo keiner ist. `ApnsClientTest` hält das fest,
unter anderem mit fünfzig echten Signaturen, weil die DER-Länge je nach
Zufallswerten schwankt.

### Sandbox oder Produktion

Entscheidet **nicht** der Server, sondern womit die App signiert wurde. Eine
Entwicklungssignatur liefert Kennungen, die nur die Sandbox kennt; an den
Produktionshost geschickt kommt `BadDeviceToken` und sonst nichts.
`APNS_HOST` steht deshalb standardmäßig auf `api.sandbox.push.apple.com`.

### Konfiguration

Alles über `/etc/food.env`; **ohne Schlüssel passiert schlicht nichts**, die
Schnellerfassung läuft unverändert weiter.

```
APNS_KEY_FILE=/etc/apns-cockpit.p8    # .p8 aus dem Developer-Portal, chmod 640 root:food
APNS_KEY_ID=…                          # zehn Zeichen, steht neben dem Schlüssel im Portal
APNS_TEAM_ID=…
APNS_TOPIC=com.fherrmann.cockpit       # die Bundle-ID der App
APNS_HOST=https://api.sandbox.push.apple.com
```

Die `.p8` gehört **nicht** ins Repo und lässt sich im Portal nur ein einziges
Mal herunterladen.

### Android: Firebase Cloud Messaging

Ebenfalls ohne SDK (`FcmClient`): die HTTP-v1-Schnittstelle ist ein POST mit
einem Bearer-Token, und das Token holt man mit einem RS256-signierten JWT aus
dem Dienstkonto. Geschickt werden **reine Datennachrichten** mit Priorität
`high`; die App baut die Benachrichtigung selbst:

```
{kind: "quick-capture", jobId, status: "done"|"failed", title, body}
{kind: "app-update", versionCode, versionName, title, body}
```

Die Dienstkonto-Datei kommt aus der Firebase-Konsole (Projekteinstellungen →
Dienstkonten → Neuen privaten Schlüssel generieren), liegt als
`/etc/fcm-healthy.json` (`root:food`, 640) und steht in `/etc/food.env` als
`FCM_SERVICE_ACCOUNT_FILE`. `deploy/setup-health-users.sh --fcm-key <datei>`
legt sie an. **Ohne Datei passiert nichts** — die Android-App fragt eine
laufende Schnellerfassung weiter selbst nach, solange sie offen ist.

Als tot gilt eine Kennung nur, wenn Firebase das ausdrücklich sagt
(`UNREGISTERED`, `SENDER_ID_MISMATCH`, „registration token" bei 400). Ein 404
allein könnte auch ein falsch eingetragenes Projekt sein, und dann flögen alle
Kennungen raus.

## Symbol

`favicon.svg` ist ein SVG, das nur ein Emoji als Text enthält (🍎). Der Browser
rendert es mit der Emoji-Schrift des Systems — das spart eine eigene Grafik und
sieht auf jeder Plattform so aus, wie der Nutzer es dort gewohnt ist.

Für den Home-Bildschirm auf iOS geht das nicht: `apple-touch-icon` akzeptiert
kein SVG. Dafür liegt eine 180×180-PNG daneben, gerendert aus demselben Emoji
auf dem Hintergrund der Seite.

Dazu je Farbschema ein `theme-color`-Meta im Ton der Oberfläche, damit die
Browserleiste auf dem Handy zur Seite passt.

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

**Eine Datei je Person.** `data/food.json` gehört der Eigentümerin
(`health.owner`, Felix) und bleibt, wo sie immer lag. Jede weitere Person
(siehe [Zugriffsschutz](#zugriffsschutz)) hat ihr eigenes Tagebuch unter
`data/users/<name>/food.json` — eigene Ziele, eigene Gerichteliste, eigene
Einträge; eine neue Person startet mit den Default-Zielen. Bewusst kein Umzug
der vorhandenen Datei: ein Rollback auf ein älteres Jar findet sie weiter.

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
| PUT | `/api/food/entries/{id}` | `{grams, meal?, date?}` — Menge, Mahlzeit oder Tag berichtigen; Name und Nährwerte je 100 g bleiben, wie sie beim Eintragen waren. Antwort: der Tag, auf dem der Eintrag danach liegt |
| GET     | `/api/food/targets`       | Tagesziele                                              |
| PUT     | `/api/food/targets`       | Tagesziele ändern (mit `micros` die Mikro-Ziele, siehe oben) |
| GET     | `/api/food/daily?from=&to=` | Tagessummen einer Spanne mit `meals` — liest die Weight-App |
| GET     | `/api/food/daily-average?from=&to=` | Gleitendes 7-Tage-Mittel der kcal je Tag — liest die Weight-App (siehe unten) |
| GET     | `/api/food/status`        | Kennzahlen für die Statusboard-Karte                    |
| GET     | `/api/food/features`      | `{quickCapture, me, detailedNutrients, micronutrients, veganMode}` — was dieser Person angeboten wird, und wer sie ist |
| PUT     | `/api/food/vegan-mode`    | `{enabled}` — veganen Modus schalten, Antwort: die neuen `features` (siehe „Veganer Modus") |
| POST    | `/api/food/quick-capture` | Freitext und/oder Foto → **Vorschlag** (schreibt nichts); 403, wenn für diese Person nicht freigeschaltet |
| GET     | `/api/food/quick-capture/{id}` | Stand eines Auftrags; der einer anderen Person ist 404 |
| POST    | `/api/food/devices`       | Push-Kennung anmelden, `{token, platform?}`             |
| GET     | `/api/app/android`        | `{versionCode, versionName, sizeBytes, sha256}` der veröffentlichten Android-App, 404 ohne |
| GET     | `/api/app/android/apk`    | die APK selbst                                           |
| GET     | `/setup?token=`           | Browser mit einem persönlichen Token einrichten (setzt `health_token`) |

Alle Endpunkte arbeiten auf dem Tagebuch der Person zum Token. Dazu kommen die
Feature Requests unter `/feature-requests/api/…` (siehe dort).

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

### Das 7-Tage-Mittel

Die Verlaufsdiagramme – hier und im Weight Tracker, im Browser wie in der
App – zeigen seit September 2026 standardmäßig das **gleitende 7-Tage-Mittel**
der kcal statt der Tageswerte; der Tageswert bleibt als zweiter, blasserer
Umschalter da. Der Tageswert springt von Mahlzeit zu Mahlzeit, das Mittel
sagt, ob eine Woche gepasst hat.

`/api/food/daily-average?from=&to=` liefert je Tag `{date, kcal, days,
complete}`. Das Fenster ist **zentriert** – drei Tage davor, der Tag, drei
danach – und damit dasselbe wie beim 7-Tage-Mittel des Weight Trackers: im
gemeinsamen Diagramm decken beide Kurven dieselben Tage ab. Gemittelt wird
**nur über abgeschlossene Tage mit Eintrag**: ein Tag ohne Eintrag ist
unbekannt und zieht das Mittel nicht auf null, und der **laufende Tag zählt
nicht mit** – seine Summe wächst bis zum Abend und würde das Mittel bis dahin
nach unten ziehen; vorerfasste künftige Tage sind ein Plan und zählen ebenso
nicht. `days` sagt, wie viele Tage eingegangen sind. `complete` ist falsch,
solange das Fenster bis heute oder darüber hinaus reicht – der Wert der
letzten vier Tage kann sich noch ändern, die Oberflächen zeichnen ihn
gepunktet. Gerechnet wird über den ganzen Bestand, nicht nur über den
angefragten Zeitraum, und nie für Tage nach heute. Tage, in deren Fenster
gar nichts liegt, fehlen in der Antwort.

Gerechnet wird **einmal, hier**: vier Oberflächen (zwei Web, zwei iOS) zeigen
denselben Wert, statt ihn viermal nachzubauen.

`/api/food/daily` liefert **nur Tage mit Einträgen**. Ein Tag ohne Eintrag ist
„unbekannt", nicht „nichts gegessen" — als 0 kcal in einer Kurve wäre das eine
Falschaussage.

Je Tag steht außerdem `meals`: die Mahlzeiten mit mindestens einem Eintrag, in
der Reihenfolge Frühstück, Mittag, Abend, Snack (`[]` ist möglich). Der Weight
Tracker braucht das für seine Energiebilanz: ein Tag zählt dort als **getrackt**,
wenn er mindestens 80 % des kcal-Ziels erreicht oder Frühstück, Mittag und Abend
je einen Eintrag haben — dieselbe Regel wie „Track food“ in coHabit. Einträge
ohne Mahlzeit (Altbestand) machen keine Mahlzeit voll.

## Zugriffsschutz

Kein Login, keine Registrierung — langlebige Token, geprüft von der App selbst
(`SecurityConfig` / `PrivateCookieAuthFilter`). Drei Wege, in dieser
Reihenfolge; der Filter setzt den Namen der Person als Principal, und jeder
Endpunkt arbeitet auf ihrem Tagebuch:

| Weg | Wer | Person |
|---|---|---|
| `Authorization: Bearer <token>` | die Android-App | die zum Token |
| Cookie `health_token` | ein Browser, eingerichtet über `/setup?token=…` | die zum Token |
| Cookie `fh_private` | Felix' Browser, die iPhone-App, Habits, das Statusboard | die Eigentümerin |

**Persönliche Token** (seit 2026-09): je Person einer, in `/etc/food.env` als
`HEALTH_TOKENS=torben:…` — **wortgleich** auch in `/etc/health-viz.env`, denn
derselbe Token öffnet den Weight Tracker. `/setup` setzt ihn als Cookie für die
ganze Domain (`Domain=fherrmann.com`), also öffnet ein Link beide Dienste.
Angelegt und verteilt werden die Token von `deploy/setup-health-users.sh`, das
am Ende die Setup-Links ausgibt. Kommt ein persönlicher Token zusammen mit
`fh_private` an, gewinnt der persönliche — sonst ließe sich in Felix' Browser
nie prüfen, was eine andere Person sieht.

**Der Privat-Cookie** `fh_private` ist der von fherrmann.com, einmalig pro
Gerät gesetzt über `https://fherrmann.com/setup?token=<secret>` auf
`Domain=.fherrmann.com`. Der Token steht an genau einer Stelle auf dem Server
(`/etc/nginx/conf.d/private-mode.conf`); `deploy/setup-food.sh` liest ihn von
dort nach `FH_PRIVATE_TOKEN` in `/etc/food.env`. **Diese App stellt ihn nie
aus** — er öffnet weit mehr als sie; `/setup` hier nimmt nur persönliche Token.

**Kein Gate mehr in nginx.** Bis 2026-09 hing die ganze Domain zusätzlich
hinter `$fh_private` in nginx. Mit persönlichen Token geht das nicht mehr:
nginx kann einen gültigen nicht von einem ungültigen unterscheiden und sperrte
genau die aus, für die es sie gibt. Die App ist jetzt die einzige Schranke —
wie bei der Einkaufsliste. Sie lauscht nur auf `127.0.0.1` und antwortet ohne
gültigen Token mit 403.

**Schnellerfassung je Person:** jede Auswertung läuft auf Felix' Claude-Login,
deshalb ist sie schaltbar (`FOOD_QUICK_CAPTURE=felix,torben`, `*` = alle, leer
= nur die Eigentümerin). `/api/food/features` sagt der Oberfläche, ob sie den
Knopf zeigt.

### CORS

Drei Endpunkte werden von anderen Subdomains gelesen: `/api/food/daily` und `/api/food/daily-average` von
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

Eine zweite Person lokal: `HEALTH_TOKENS=torben:0123456789abcdef0123456789abcdef
./gradlew bootRun`, dann <http://localhost:48180/setup?token=0123456789abcdef0123456789abcdef>
oder `curl -H 'Authorization: Bearer 0123…'`.

## Tests

```bash
./gradlew test
```

- **`FoodServiceTest`** — Tagessummen, Gramm-Rechnung, Gerichte-Upsert über den
  Namen, Unveränderlichkeit bereits erfasster Einträge, Spannen-Abfrage, Validierung.
- **`FoodRepositoryTest`** — fehlende Datei ⇒ Defaults, Round-Trip.
- **`NutrientsTest`** — Rechnung mit den Mikronährstoffen: Skalieren,
  Teilsummen, kein Rest, Rundung, feste Reihenfolge, JSON-Form, DGE-Vorgabe.
- **`MicronutrientsApiTest`** — durch den ganzen Dienst: Felix' Antworten
  Zeichen für Zeichen wie vor den Mikronährstoffen (obwohl seine Anfragen
  `micros` mitschicken), Torbens Werte, Lücken und Schnellerfassung, Mikro-Ziele
  mit Vorgabe und „bewusst ohne Ziel".
- **`FoodControllerTest`** — Cookie-Prüfung (403 ohne/mit falschem Cookie, 200 mit
  richtigem) und die CORS-Header genau auf den beiden freigegebenen Endpunkten.
- **`FoodAccessTest`** — mit echtem Filter: welcher Token welche Person meint,
  dass sie nur ihr eigenes Tagebuch sieht, `/setup`, `features` je Person.
- **`QuickCaptureJobsTest`** — Aufträge gehören der Person, die sie startet;
  ohne Freischaltung 403, bevor etwas läuft.
- **`DeviceTokensTest`**, **`FcmClientTest`** (gegen einen lokalen Server, der
  Google spielt: signiertes JWT, Datennachricht, tote Kennungen),
  **`AndroidReleaseTest`** (Prüfsumme, halbe Veröffentlichung, Ankündigung
  genau einmal).
- **Feature Requests** (`featurerequest/`): `FeatureRequestServiceTest` (Prüfungen,
  wer was sieht, Stand aus dem Brett), `FeatureRequestAccessTest` (echter Filter:
  eigene, alle, fremde 404, ohne Token 403 auch auf Seite und Dateien),
  `ClaudeStoryAgentTest` (gegen ein Ersatz-Skript: Auftrag mit Zitat, Umschlag,
  Fehler, Zeitlimit), `StoryDraftJobsTest`, `TodoClientTest` und
  `FeatureRequestTodosTest` (gegen einen lokalen Server, der das To-Do spielt:
  „Server" und „Healthy" finden oder anlegen, Ausfall und Nachlauf, Link-Übernahme),
  `FeatureRequestsIT` (der ganze Weg gegen einen echten Server).
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
| `health.owner`              | `felix` (env: `HEALTH_OWNER`)                       | Wem `fh_private` und `data/food.json` gehören |
| `health.tokens`             | leer (env: `HEALTH_TOKENS`)                         | Weitere Personen, `name:token,…`    |
| `health.cookie-domain`      | `fherrmann.com` (env: `HEALTH_COOKIE_DOMAIN`)       | Domain des `health_token`-Cookies   |
| `food.agent.people`         | leer = Eigentümerin (env: `FOOD_QUICK_CAPTURE`)     | Wer die Schnellerfassung benutzen darf |
| `food.detailed-people`      | leer = niemand (env: `FOOD_DETAILED_NUTRIENTS`)     | Wer die ganze Nährwerttabelle erfasst |
| `food.micronutrient-people` | leer = niemand (env: `FOOD_MICRONUTRIENTS`)         | Wer Mikronährstoffe erfasst |
| `food.fcm.service-account-file` | leer (env: `FCM_SERVICE_ACCOUNT_FILE`)          | Firebase-Dienstkonto für Push an Android |
| `food.android.dir`          | leer (env: `FOOD_ANDROID_DIR`)                      | Verzeichnis mit `healthy.apk` + `latest.json` |
| `food.story-agent.command`  | leer (env: `FOOD_STORY_AGENT_COMMAND`)              | Wrapper des Story-Agenten; leer = Feature Requests ohne Claude-Entwurf |
| `food.story-agent.timeout-seconds` | `120`                                        | danach wird die Entwurfs-Session beendet |
| `food.todo.url`             | `http://127.0.0.1:48210/todo` (env: `FOOD_TODO_URL`) | To-Do für die Unteraufgaben; leer = keine |
| `food.todo.sync-interval`   | `PT10M`                                             | Takt des Nachlaufs für fehlende Unteraufgaben |
| `food.feature-requests.base-url` | `https://fherrmann.com/feature-requests` (env: `FOOD_FEATURE_REQUESTS_URL`) | Basis der Links in den Unteraufgaben |
| `food.feature-requests.data-file` | `data/feature-requests.json` (env: `FOOD_FEATURE_REQUESTS_FILE`) | Ablage aller Anfragen |

## Architektur

```
com.fherrmann.food
  model/       Nutrients, Dish, FoodEntry, FoodData, Meal  (Domänenmodell)
  dto/         DaySummary, DayTotal, StatusInfo, …    (API-Transferobjekte)
  repository/  FoodRepository                         (JSON-I/O)
  service/     FoodService                            (Regeln, kein HTTP)
  controller/  FoodController, AppController           (HTTP)
  security/    SecurityConfig, PrivateCookieAuthFilter, HealthUsers, UserFiles,
               SetupController                         (wer ist wer, wessen Dateien, CORS)
  push/        ApnsClient, FcmClient, DeviceTokens, PushNotifier
  release/     AndroidRelease, ReleaseAnnouncer        (Android-App ausliefern und ankündigen)
  featurerequest/  FeatureRequestService, -Controller, -Page, -Repository,
               StoryDraftJobs, ClaudeStoryAgent, TodoClient, FeatureRequestTodos
                                                       (Feature Requests, siehe dort)

deploy/agent/        Vorlagen für das Agent-Arbeitsverzeichnis (siehe Schnellerfassung)
deploy/story-agent/  dasselbe für den Agenten der Feature Requests
```

## Deployment

```
Internet (443) → Lightsail → WireGuard → Homeserver:443 (nginx, TLS)
                                                 │
                                                 ▼
                                       Homeserver:48180 (Spring Boot, nur localhost)
```

| Was | Wo |
|---|---|
| Repo/Build | `~/services/food` |
| Laufzeit | `/opt/food` (`app.jar` + `data/`) |
| Dienst | `food.service`, User `food` |
| Token | `/etc/food.env` — `FH_PRIVATE_TOKEN` (aus `/etc/nginx/conf.d/private-mode.conf`), `HEALTH_TOKENS` (wortgleich in `/etc/health-viz.env`) |
| Android-App | `/opt/healthy-android` (`healthy.apk` + `latest.json`, gehört `flexii`) |
| Firebase | `/etc/fcm-healthy.json` (`root:food`, 640) |
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

**Persönliche Zugänge** (Token für Kalorienzähler und Weight Tracker,
Schnellerfassung, Verzeichnis der Android-App, Firebase, nginx ohne Gate) —
idempotent, gibt am Ende die Setup-Links aus. **Erst** beide Dienste
aktualisieren, dann:

```bash
ssh -t HeimServerRemote '~/services/food/deploy/setup-health-users.sh torben'
# mit Detailwerten und Mikronährstoffen für die genannten Personen:
ssh -t HeimServerRemote '~/services/food/deploy/setup-health-users.sh --detailed --micronutrients torben'
# mit Push an Android, Dienstkonto vorher nach ~ kopiert:
ssh -t HeimServerRemote '~/services/food/deploy/setup-health-users.sh --fcm-key ~/fcm-healthy.json torben'
```

**Feature Requests** (Story-Agent unter `~/services/story-agent`, sudo-Regel
`/etc/sudoers.d/21-story-agent`, `FOOD_STORY_AGENT_COMMAND` nur bei gelungener
sudo-Probe, `location /feature-requests/` in `sites-available/fherrmann.com`,
Neustart, Prüfung auf 403 ohne Cookie) — idempotent, **nach** `update-food.sh`:

```bash
ssh -t HeimServerRemote '~/services/food/deploy/setup-feature-requests.sh'
```
