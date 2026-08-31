// --- Tacho-Geometrie --------------------------------------------------------
// Alle Tachos benutzen dieselbe 100x100-viewBox und werden ueber CSS skaliert.
// Gezeichnet wird der Bogen nicht als <path>, sondern als <circle> mit
// stroke-dasharray: ein Kreis laesst sich exakt bemessen (Umfang = 2*pi*r), ein
// von Hand gerechneter Arc-Path dagegen kippt an jeder Viertelgrenze in die
// falsche Richtung, wenn man das large-arc-Flag verrechnet.
const R = 38;
const C = 2 * Math.PI * R;
// 270° Bogen, beginnend unten links (7:30 Uhr) - die klassische Tacho-Form.
const SWEEP = 0.75;

// Wie weit ueber dem Ziel noch gelb ist; darueber rot. Vorgabe waren 100 kcal -
// fuer die Makros in Gramm umgerechnet, damit ueberall dieselbe Toleranz gilt
// und nicht drei willkuerliche Zahlen nebeneinander stehen:
// Kohlenhydrate 4 kcal/g -> 25 g, Fett 9 kcal/g -> gut 11 g.
const TOLERANCE = { kcal: 100, carbsG: 25, fatG: 11 };

// Wie viel Luft der Tacho ueber dem Ziel laesst. Ohne diesen Aufschlag saesse
// der Zielstrich am Bogenende und waere als Marke wertlos - man saehe nie, ob
// man knapp oder weit darueber liegt.
const GAUGE_HEADROOM = 1.25;

const MACROS = [
    // Reihenfolge wie gewuenscht: Eiweiß, Fett, Kohlenhydrate.
    // `direction` sagt, in welche Richtung das Ziel gemeint ist - Eiweiß ist ein
    // Mindestwert (drueber ist das Ziel erreicht), Fett und Kohlenhydrate sind
    // Obergrenzen (drueber ist zu viel). Ohne diese Unterscheidung wuerde eine
    // gemeinsame Einfaerbung genau die Haelfte der Faelle falsch herum bewerten.
    { key: 'proteinG', label: 'Eiweiß', unit: 'g', direction: 'floor' },
    { key: 'fatG', label: 'Fett', unit: 'g', direction: 'ceiling' },
    { key: 'carbsG', label: 'Kohlenhydrate', unit: 'g', direction: 'ceiling' },
];

// Die Weight-App liegt auf einer eigenen Subdomain, aber unter derselben Site -
// der private Cookie reist also mit; credentials:'include' braucht es nur, weil
// die Origin eine andere ist (dort per CORS genau fuer diese Seite freigegeben).
const WEIGHT_API = /^(localhost|127\.0\.0\.1)$/.test(location.hostname)
    ? `${location.protocol}//${location.hostname}:48173`
    : 'https://weight.fherrmann.com';

const HISTORY_RANGES = [14, 30, 90];

// Reihenfolge wie der Tag verlaeuft, nicht alphabetisch. `null` steht fuer
// Eintraege aus der Zeit vor dieser Aufteilung: der Abschnitt taucht nur auf,
// solange es solche gibt, und verschwindet danach von selbst. Sie nachtraeglich
// einzusortieren hiesse, eine Vermutung wie eine Angabe aussehen zu lassen.
const MEALS = [
    { key: 'BREAKFAST', label: 'Frühstück' },
    { key: 'LUNCH', label: 'Mittagessen' },
    { key: 'DINNER', label: 'Abendessen' },
    { key: 'SNACK', label: 'Snacks' },
];
const UNASSIGNED = { key: null, label: 'Ohne Zuordnung' };

const CHART_COLORS = {
    kcal: 'rgba(92, 124, 250, 0.75)',
    kcalOver: 'rgba(239, 83, 80, 0.8)',
    target: 'rgba(230, 236, 245, 0.45)',
    weight: '#81c784',
};

let currentDate = todayIso();
let dishes = [];
let day = null;

// Zu welcher Mahlzeit das offene Eingabefenster gehoert.
let addMeal = MEALS[0].key;

let historyDays = 30;
let historyChart = null;
let dailyTotals = [];
// Gewicht wird erst geholt, wenn es jemand einblendet - ein Cross-Origin-Request
// auf gut Glueck waere unnoetig, und ohne Weight-Cookie schlaegt er ohnehin fehl.
let showWeight = false;
let weightByDate = {};
let weightError = null;

function todayIso() {
    const now = new Date();
    now.setMinutes(now.getMinutes() - now.getTimezoneOffset());
    return now.toISOString().slice(0, 10);
}

function shiftDate(iso, days) {
    const date = new Date(`${iso}T00:00:00`);
    date.setDate(date.getDate() + days);
    date.setMinutes(date.getMinutes() - date.getTimezoneOffset());
    return date.toISOString().slice(0, 10);
}

function fmtDate(iso) {
    if (!iso) return '–';
    const [y, m, d] = iso.split('-');
    return `${d}.${m}.${y.slice(2)}`;
}

function num(value, digits = 0) {
    if (value == null || Number.isNaN(value)) return '–';
    return value.toLocaleString('de-DE', {
        minimumFractionDigits: digits,
        maximumFractionDigits: digits,
    });
}

async function fetchJson(url, options) {
    const res = await fetch(url, options);
    if (!res.ok) {
        let message = `HTTP ${res.status}`;
        try {
            const body = await res.json();
            if (body && body.message) message = body.message;
        } catch (e) { /* Fehlerkoerper ist nicht immer JSON - dann bleibt der Status. */ }
        throw new Error(message);
    }
    return res.status === 204 ? null : res.json();
}

// --- Tachos -----------------------------------------------------------------

/**
 * Einfaerbung nach Verzehrtem, Ziel und Richtung des Ziels.
 *
 * <p>Bei einer Obergrenze faerbt erst das Ueberschreiten ein, und zwar knapp:
 * bis zur Toleranz gelb, darueber rot. Vorher passiert nichts - ein Tacho, der
 * schon bei 85 % warnt, warnt an jedem normalen Tag und wird dadurch bedeutungslos.
 *
 * <p>Bei einem Mindestwert (Eiweiß) gibt es keine Warnung: mehr ist dort mehr.
 */
function toneFor(consumed, target, direction, tolerance) {
    if (direction === 'floor') {
        if (consumed >= target) return 'good';
        return target > 0 && consumed / target >= 0.8 ? 'near' : 'neutral';
    }
    const over = consumed - target;
    if (over <= 0) return 'neutral';
    return over <= tolerance ? 'warn' : 'bad';
}

/**
 * Der Zielstrich: eine kurze radiale Marke da, wo das Tagesziel sitzt. Weil der
 * Bogen bis GAUGE_HEADROOM des Ziels reicht, liegt sie nicht am Ende, sondern
 * ein Stueck davor - dadurch ist ablesbar, ob und wie weit man darueber ist.
 */
function gaugeTick() {
    const deg = 135 + (1 / GAUGE_HEADROOM) * SWEEP * 360;
    const rad = deg * Math.PI / 180;
    const point = distance => [50 + distance * Math.cos(rad), 50 + distance * Math.sin(rad)];
    const [x1, y1] = point(R - 7.5);
    const [x2, y2] = point(R + 7.5);
    return `<line class="gauge-tick" x1="${x1.toFixed(2)}" y1="${y1.toFixed(2)}"
                  x2="${x2.toFixed(2)}" y2="${y2.toFixed(2)}"></line>`;
}

function gauge({ ratio, tone, main, sub, mainSize = 20, subSize = 8 }) {
    // Ueber 100 % laeuft der Bogen nicht weiter - er ist dann voll und rot; die
    // Zahl in der Mitte traegt die eigentliche Information.
    const filled = Math.max(0, Math.min(ratio, 1)) * SWEEP * C;
    // Bei 0 gar keinen Wertbogen zeichnen: stroke-linecap: round malt auch fuer
    // eine Strichlaenge von 0 noch beide Kappen und damit einen Punkt am
    // Bogenanfang - der sieht aus wie ein Messwert, wo keiner ist.
    const value = filled > 0
        ? `<circle class="gauge-value" cx="50" cy="50" r="${R}"
                    stroke-dasharray="${filled} ${C}" transform="rotate(135 50 50)"></circle>`
        : '';
    // Zahl und Beschriftung sitzen als Paar mittig: die Zahl etwas ueber der
    // Mitte, das Label darunter - beide auf y=50 saehen nach unten verrutscht aus.
    const mainY = 50 - subSize * 0.6;
    return `
        <svg class="gauge tone-${tone}" viewBox="0 0 100 100" role="img" aria-label="${main} ${sub}">
            <circle class="gauge-track" cx="50" cy="50" r="${R}"
                    stroke-dasharray="${SWEEP * C} ${C}" transform="rotate(135 50 50)"></circle>
            ${value}
            ${gaugeTick()}
            <text class="gauge-main" x="50" y="${mainY}" font-size="${mainSize}">${main}</text>
            <text class="gauge-sub" x="50" y="${mainY + mainSize * 0.62 + subSize * 0.6}"
                  font-size="${subSize}">${sub}</text>
        </svg>`;
}

function renderGauges() {
    const macroRow = document.getElementById('macro-row');
    const kcalBox = document.getElementById('kcal-gauge');
    if (!day) {
        macroRow.replaceChildren();
        kcalBox.replaceChildren();
        return;
    }

    macroRow.innerHTML = MACROS.map(macro => {
        const target = day.targets[macro.key] || 0;
        const consumed = day.consumed[macro.key] || 0;
        const ratio = target > 0 ? consumed / (target * GAUGE_HEADROOM) : 0;
        return `
            <div class="macro">
                ${gauge({
                    ratio,
                    tone: toneFor(consumed, target, macro.direction, TOLERANCE[macro.key]),
                    main: num(consumed),
                    sub: `von ${num(target)} ${macro.unit}`,
                    mainSize: 22,
                    subSize: 9,
                })}
                <span class="macro-label">${macro.label}</span>
            </div>`;
    }).join('');

    const target = day.targets.kcal || 0;
    const consumed = day.consumed.kcal || 0;
    const remaining = day.remaining.kcal;
    const ratio = target > 0 ? consumed / (target * GAUGE_HEADROOM) : 0;
    kcalBox.innerHTML = gauge({
        ratio,
        tone: toneFor(consumed, target, 'ceiling', TOLERANCE.kcal),
        main: num(Math.abs(remaining)),
        sub: remaining < 0 ? 'kcal drüber' : 'kcal übrig',
        mainSize: 19,
        subSize: 8,
    });

    document.getElementById('kcal-consumed').textContent = `${num(consumed)} kcal`;
    document.getElementById('kcal-target').textContent = `von ${num(target)} kcal`;
}

// --- Eintraege des Tages ----------------------------------------------------

// Wischen zum Loeschen gibt es nur auf Touch-Geraeten. Mit Maus tut es der
// x-Knopf; ein Drag-Handler dort wuerde sich nur mit der Textauswahl anlegen.
const TOUCH_QUERY = window.matchMedia('(pointer: coarse)');
// Ab hier loest Loslassen das Loeschen aus; weiter als SWIPE_MAX_PX geht die
// Zeile nicht, damit klar bleibt, dass es eine Geste und kein Scrollen ist.
const SWIPE_TRIGGER_PX = 70;
const SWIPE_MAX_PX = 96;

function renderEntries() {
    const container = document.getElementById('meals');
    container.replaceChildren();

    const heading = document.getElementById('entries-heading');
    heading.textContent = currentDate === todayIso()
        ? 'Heute gegessen'
        : `Gegessen am ${fmtDate(currentDate)}`;

    const entries = (day && day.entries) || [];
    document.getElementById('swipe-hint').hidden = !TOUCH_QUERY.matches || !entries.length;

    const sections = [...MEALS];
    // Der Restabschnitt nur, wenn er auch etwas enthaelt.
    if (entries.some(e => !e.meal)) {
        sections.push(UNASSIGNED);
    }

    sections.forEach(meal => {
        const own = entries.filter(e => (e.meal || null) === meal.key);
        container.appendChild(buildMealSection(meal, own));
    });
}

function buildMealSection(meal, entries) {
    const section = document.createElement('section');
    section.className = 'meal';

    const head = document.createElement('div');
    head.className = 'meal-head';

    const title = document.createElement('span');
    title.className = 'meal-title';
    title.textContent = meal.label;

    const sum = document.createElement('span');
    sum.className = 'meal-sum';
    // Teilsumme je Abschnitt: an einer Tagesgesamtsumme laesst sich nicht
    // ablesen, welche Mahlzeit aus dem Rahmen fiel.
    const kcal = entries.reduce((acc, e) => acc + e.per100g.kcal * e.grams / 100, 0);
    sum.textContent = entries.length ? `${num(kcal)} kcal` : '';

    head.append(title, sum);

    // Der Restabschnitt bekommt kein "+": dort landet nichts Neues mehr.
    if (meal.key) {
        const add = document.createElement('button');
        add.type = 'button';
        add.className = 'meal-add';
        add.textContent = '+';
        add.title = `Etwas zu ${meal.label} hinzufügen`;
        add.setAttribute('aria-label', `Etwas zu ${meal.label} hinzufügen`);
        add.addEventListener('click', () => openAddDialog(meal));
        head.appendChild(add);
    }

    section.appendChild(head);

    if (!entries.length) {
        const empty = document.createElement('p');
        empty.className = 'hint meal-empty';
        empty.textContent = 'Noch nichts eingetragen.';
        section.appendChild(empty);
        return section;
    }

    const list = document.createElement('div');
    list.className = 'entry-list';
    entries.forEach(entry => list.appendChild(buildEntryRow(entry)));
    section.appendChild(list);
    return section;
}

function buildEntryRow(entry) {
    const factor = entry.grams / 100;
    const row = document.createElement('div');
    row.className = 'entry-row';

    const backdrop = document.createElement('div');
    backdrop.className = 'entry-delete';
    backdrop.setAttribute('aria-hidden', 'true');
    backdrop.textContent = 'Löschen';

    const content = document.createElement('div');
    content.className = 'entry-content';
    content.innerHTML = `
        <span class="e-name">${escapeHtml(entry.name)}</span>
        <span class="e-amount n">${num(entry.grams)} g</span>
        <span class="e-kcal n">${num(entry.per100g.kcal * factor)}</span>
        <span class="e-protein n">${num(entry.per100g.proteinG * factor)}</span>
        <span class="e-carbs n">${num(entry.per100g.carbsG * factor)}</span>
        <span class="e-fat n">${num(entry.per100g.fatG * factor)}</span>`;

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'row-remove';
    remove.textContent = '×';
    remove.title = 'Eintrag löschen';
    remove.setAttribute('aria-label', `${entry.name} löschen`);
    remove.addEventListener('click', () => deleteEntry(entry.id));
    content.appendChild(remove);

    row.append(backdrop, content);
    enableSwipeToDelete(row, content, () => deleteEntry(entry.id));
    return row;
}

/**
 * Nach links wischen loescht den Eintrag. Die Zeile selbst bleibt stehen, nur
 * ihr Inhalt faehrt zur Seite und gibt den roten Grund darunter frei.
 */
function enableSwipeToDelete(row, content, onDelete) {
    if (!TOUCH_QUERY.matches) return;

    let startX = 0;
    let startY = 0;
    let offset = 0;
    let tracking = false;
    let decided = false;

    const settle = () => {
        content.style.transition = '';
        content.style.transform = '';
        row.classList.remove('will-delete');
    };

    content.addEventListener('touchstart', event => {
        startX = event.touches[0].clientX;
        startY = event.touches[0].clientY;
        offset = 0;
        tracking = true;
        decided = false;
        // Waehrend des Ziehens soll die Zeile dem Finger ohne Nachlauf folgen.
        content.style.transition = 'none';
    }, { passive: true });

    content.addEventListener('touchmove', event => {
        if (!tracking) return;
        const moveX = event.touches[0].clientX - startX;
        const moveY = event.touches[0].clientY - startY;

        if (!decided) {
            // Richtung erst festlegen, wenn der Finger sich eindeutig entschieden
            // hat - sonst bleibt die Liste beim Scrollen am Finger haengen.
            if (Math.abs(moveX) < 8 && Math.abs(moveY) < 8) return;
            decided = true;
            if (Math.abs(moveY) >= Math.abs(moveX)) {
                tracking = false;
                settle();
                return;
            }
        }

        // Nur nach links: nach rechts gibt es nichts freizulegen.
        offset = Math.max(-SWIPE_MAX_PX, Math.min(0, moveX));
        content.style.transform = `translateX(${offset}px)`;
        row.classList.toggle('will-delete', offset <= -SWIPE_TRIGGER_PX);
        event.preventDefault();
    }, { passive: false });

    content.addEventListener('touchend', () => {
        if (!tracking) {
            settle();
            return;
        }
        tracking = false;
        content.style.transition = '';
        if (offset <= -SWIPE_TRIGGER_PX) {
            // Zeile ganz rausfahren lassen und erst dann loeschen: das Neuladen
            // baut die Liste ohnehin neu auf, die Animation darf vorher laufen.
            row.classList.add('removing');
            onDelete();
        } else {
            settle();
        }
    });

    content.addEventListener('touchcancel', () => {
        tracking = false;
        settle();
    });
}

// Auch fuer Attributwerte gedacht (value="..."), deshalb werden die
// Anfuehrungszeichen mit ersetzt - textContent allein maskiert sie nicht.
function escapeHtml(value) {
    return String(value == null ? '' : value)
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
}

// --- Gerichte ---------------------------------------------------------------

const NEW_DISH = '__new__';

const NEW_DISH_REQUIRED = ['nd-name', 'nd-kcal', 'nd-protein', 'nd-carbs', 'nd-fat'];

/** Das gerade gewaehlte Gericht, oder null (nichts gewaehlt / neues Gericht). */
function selectedDish() {
    const id = document.getElementById('in-dish').value;
    return dishes.find(d => d.id === id) || null;
}

/**
 * Haelt die Anzeige an der Auswahl: Naehrwert-Hinweis, Portionsknoepfe und der
 * Block fuer ein neues Gericht haengen alle daran.
 */
function onDishChange() {
    const value = document.getElementById('in-dish').value;
    const isNew = value === NEW_DISH;
    document.getElementById('new-dish').hidden = !isNew;
    // Pflichtfelder nur, solange der Block sichtbar ist: ein leeres required-Feld
    // in einem versteckten fieldset blockiert das Absenden ohne sichtbaren Grund.
    NEW_DISH_REQUIRED.forEach(id => { document.getElementById(id).required = isNew; });

    const hint = document.getElementById('dish-hint');
    const shortcuts = document.getElementById('portion-shortcuts');
    const dish = selectedDish();

    if (!dish) {
        hint.hidden = true;
        shortcuts.hidden = true;
        shortcuts.replaceChildren();
        return;
    }

    const p = dish.per100g;
    hint.hidden = false;
    hint.textContent = `Je 100 g: ${num(p.kcal)} kcal · Eiweiß ${num(p.proteinG, 1)} g `
        + `· Kohlenhydrate ${num(p.carbsG, 1)} g · Fett ${num(p.fatG, 1)} g`;

    // Portionsgroesse ist optional. Gibt es eine, sind die haeufigen Mengen einen
    // Klick entfernt; ohne sie bleibt das Grammfeld der einzige Weg.
    shortcuts.replaceChildren();
    if (dish.portionG) {
        shortcuts.hidden = false;
        [['½ Portion', 0.5], ['1 Portion', 1], ['1½ Portionen', 1.5], ['2 Portionen', 2]]
            .forEach(([label, factor]) => {
                const button = document.createElement('button');
                button.type = 'button';
                button.className = 'ghost';
                button.textContent = label;
                button.addEventListener('click', () => {
                    document.getElementById('in-grams').value = Math.round(dish.portionG * factor);
                });
                shortcuts.appendChild(button);
            });
        if (!document.getElementById('in-grams').value) {
            document.getElementById('in-grams').value = Math.round(dish.portionG);
        }
    } else {
        shortcuts.hidden = true;
    }
}

// Der gerade hervorgehobene Eintrag in der Auswahlliste, fuer die Pfeiltasten.
let dishHighlight = -1;

/**
 * Die gefilterte Auswahlliste. Gesucht wird ueber Teilzeichenketten, damit
 * "bol" auch "Spaghetti Bolognese" findet - eine Liste, die nur den Anfang
 * vergleicht, zwingt zum Erraten der Schreibweise.
 */
function dishMatches(query) {
    const needle = query.trim().toLowerCase();
    if (!needle) return dishes;
    return dishes.filter(d => d.name.toLowerCase().includes(needle));
}

function renderDishOptions() {
    const box = document.getElementById('dish-options');
    const search = document.getElementById('dish-search');
    const matches = dishMatches(search.value);
    box.replaceChildren();
    dishHighlight = -1;

    matches.forEach((dish, index) => {
        const suffix = dish.portionG ? ` · Portion ${num(dish.portionG)} g` : '';
        const option = document.createElement('button');
        option.type = 'button';
        option.className = 'dish-option';
        option.role = 'option';
        option.dataset.index = String(index);
        option.innerHTML = `<span class="do-name">${escapeHtml(dish.name)}</span>`
            + `<span class="do-meta">${num(dish.per100g.kcal)} kcal/100 g${escapeHtml(suffix)}</span>`;
        // mousedown statt click: click kaeme erst nach dem blur des Suchfeldes,
        // und das schliesst die Liste, bevor die Auswahl ankommt.
        option.addEventListener('mousedown', event => {
            event.preventDefault();
            chooseDish(dish.id, dish.name);
        });
        box.appendChild(option);
    });

    if (!matches.length) {
        const empty = document.createElement('p');
        empty.className = 'hint dish-option-empty';
        empty.textContent = 'Kein Gericht gefunden.';
        box.appendChild(empty);
    }

    const create = document.createElement('button');
    create.type = 'button';
    create.className = 'dish-option dish-option-new';
    create.textContent = '＋ Neues Gericht …';
    create.addEventListener('mousedown', event => {
        event.preventDefault();
        chooseDish(NEW_DISH, search.value.trim());
    });
    box.appendChild(create);
}

function openDishOptions() {
    renderDishOptions();
    const box = document.getElementById('dish-options');
    box.hidden = false;
    document.getElementById('dish-search').setAttribute('aria-expanded', 'true');
}

function closeDishOptions() {
    document.getElementById('dish-options').hidden = true;
    document.getElementById('dish-search').setAttribute('aria-expanded', 'false');
    dishHighlight = -1;
}

/** Uebernimmt eine Auswahl ins versteckte Feld und schliesst die Liste. */
function chooseDish(id, label) {
    document.getElementById('in-dish').value = id;
    const search = document.getElementById('dish-search');
    // Bei "Neues Gericht" bleibt der getippte Text stehen - meist ist er schon
    // der Name, den das neue Gericht bekommen soll.
    search.value = id === NEW_DISH ? label : label;
    if (id === NEW_DISH && label) {
        document.getElementById('nd-name').value = label;
    }
    closeDishOptions();
    onDishChange();
}

function moveDishHighlight(step) {
    const options = [...document.querySelectorAll('#dish-options .dish-option')];
    if (!options.length) return;
    dishHighlight = (dishHighlight + step + options.length) % options.length;
    options.forEach((option, index) =>
        option.classList.toggle('highlighted', index === dishHighlight));
    options[dishHighlight].scrollIntoView({ block: 'nearest' });
}

function initDishSearch() {
    const search = document.getElementById('dish-search');

    search.addEventListener('focus', openDishOptions);
    search.addEventListener('input', () => {
        // Tippen verwirft eine frueher getroffene Auswahl - sonst zeigte das Feld
        // einen Suchtext, waehrend im Hintergrund noch ein anderes Gericht steht.
        document.getElementById('in-dish').value = '';
        openDishOptions();
        onDishChange();
    });
    search.addEventListener('blur', () => setTimeout(closeDishOptions, 0));

    search.addEventListener('keydown', event => {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            if (document.getElementById('dish-options').hidden) openDishOptions();
            moveDishHighlight(event.key === 'ArrowDown' ? 1 : -1);
            return;
        }
        if (event.key === 'Escape') {
            closeDishOptions();
            return;
        }
        if (event.key === 'Enter') {
            const options = [...document.querySelectorAll('#dish-options .dish-option')];
            if (dishHighlight >= 0 && options[dishHighlight]) {
                event.preventDefault();
                options[dishHighlight].dispatchEvent(new MouseEvent('mousedown'));
            }
        }
    });
}

/** Setzt Suchfeld und Auswahl zurueck - beim Oeffnen des Eingabefensters. */
function resetDishSearch() {
    document.getElementById('in-dish').value = '';
    document.getElementById('dish-search').value = '';
    closeDishOptions();
}

function renderDishList() {
    const container = document.getElementById('dish-list');
    container.replaceChildren();
    if (!dishes.length) {
        const empty = document.createElement('p');
        empty.className = 'hint';
        empty.textContent = 'Noch keine Gerichte gespeichert.';
        container.appendChild(empty);
        return;
    }
    dishes.forEach(dish => container.appendChild(buildDishRow(dish)));
}

function buildDishRow(dish) {
    const row = document.createElement('form');
    row.className = 'dish-row';
    row.innerHTML = `
        <label class="grow">Name <input type="text" value="${escapeHtml(dish.name)}" maxlength="80" required></label>
        <label>kcal <input type="number" step="0.1" min="0" max="1000" value="${dish.per100g.kcal}" required></label>
        <label>E <input type="number" step="0.1" min="0" max="100" value="${dish.per100g.proteinG}" required></label>
        <label>KH <input type="number" step="0.1" min="0" max="100" value="${dish.per100g.carbsG}" required></label>
        <label>F <input type="number" step="0.1" min="0" max="100" value="${dish.per100g.fatG}" required></label>
        <label>Portion <input type="number" step="1" min="1" max="20000" value="${dish.portionG ?? ''}"></label>`;

    const [name, kcal, protein, carbs, fat, portion] = row.querySelectorAll('input');
    const save = document.createElement('button');
    save.type = 'submit';
    save.textContent = 'Speichern';
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'danger';
    remove.textContent = 'Löschen';
    const msg = document.createElement('span');
    msg.className = 'form-msg';
    row.append(save, remove, msg);

    row.addEventListener('submit', async event => {
        event.preventDefault();
        await withMessage(msg, async () => {
            await fetchJson(`/api/food/dishes/${encodeURIComponent(dish.id)}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    name: name.value,
                    kcal: parseFloat(kcal.value),
                    proteinG: parseFloat(protein.value),
                    carbsG: parseFloat(carbs.value),
                    fatG: parseFloat(fat.value),
                    portionG: portion.value === '' ? null : parseFloat(portion.value),
                }),
            });
            await loadAll();
        });
    });

    remove.addEventListener('click', async () => {
        if (!confirm(`"${dish.name}" aus der Liste entfernen? Bereits eingetragene Portionen bleiben erhalten.`)) {
            return;
        }
        await withMessage(msg, async () => {
            await fetchJson(`/api/food/dishes/${encodeURIComponent(dish.id)}`, { method: 'DELETE' });
            await loadAll();
        });
    });

    return row;
}

/** Fuehrt eine Aktion aus und schreibt Erfolg/Fehler in das mitgegebene Feld. */
async function withMessage(element, action) {
    element.textContent = '';
    element.className = 'form-msg';
    try {
        await action();
        element.textContent = 'Gespeichert.';
        element.classList.add('ok');
    } catch (err) {
        element.textContent = `Fehler: ${err.message}`;
        element.classList.add('err');
    }
}

// --- Schnellerfassung -------------------------------------------------------

// Ob der Server dafuer eingerichtet ist. Wird beim Laden abgefragt; ohne
// Claude-Schluessel bleibt der Knopf ausgeblendet, statt einen Fehler anzubieten.
let quickCaptureAvailable = false;

function openAddDialog(meal) {
    addMeal = meal.key;
    document.getElementById('add-title').textContent = `${meal.label} – hinzufügen`;

    // Beim Oeffnen zurueck auf den Ausgangszustand: ein halb ausgefuelltes
    // Formular vom letzten Mal waere hier eine Falle.
    const quick = document.getElementById('quick-capture');
    quick.hidden = true;
    document.getElementById('quick-open').hidden = !quickCaptureAvailable;
    document.getElementById('quick-text').value = '';
    document.getElementById('quick-msg').textContent = '';
    document.getElementById('entry-msg').textContent = '';
    document.getElementById('in-grams').value = '';
    resetDishSearch();
    proposal = null;
    renderProposal();
    onDishChange();

    document.getElementById('add-dialog').showModal();
}

function initAddDialog() {
    const dialog = document.getElementById('add-dialog');
    document.getElementById('add-close').addEventListener('click', () => dialog.close());
    // Klick auf den Hintergrund schliesst ebenfalls. Die Pruefung geht ueber die
    // Koordinaten und NICHT ueber event.target === dialog: verschwindet das
    // angeklickte Element zwischen mousedown und mouseup - etwa ein Treffer der
    // Gerichtesuche, deren Liste beim Auswaehlen zuklappt -, stellt der Browser
    // den Klick dem naechsten ueberlebenden Vorfahren zu. Das ist dann der
    // dialog selbst, und die Zielpruefung haette ihn bei jeder Auswahl
    // geschlossen.
    dialog.addEventListener('click', event => {
        if (event.target !== dialog) return;
        const box = dialog.getBoundingClientRect();
        const inside = event.clientX >= box.left && event.clientX <= box.right
            && event.clientY >= box.top && event.clientY <= box.bottom;
        if (!inside) dialog.close();
    });
}

// Woher ein Wert stammt - der Kern des Vorschlags: eine geschaetzte Zahl von
// einer abgelesenen zu unterscheiden ist die eine Pruefung, die der Nutzer
// nicht selbst nachholen kann, wenn der Eintrag erst einmal steht.
const VALUE_SOURCES = {
    stored: { label: 'gespeichert', tone: 'stored' },
    read: { label: 'aus dem Text', tone: 'read' },
    lookedUp: { label: 'nachgeschlagen', tone: 'lookedup' },
    estimated: { label: 'geschätzt', tone: 'estimated' },
    // Sobald von Hand korrigiert wurde, stimmt die urspruengliche Herkunft nicht
    // mehr - dann steht das auch dran.
    edited: { label: 'geändert', tone: 'edited' },
};

const PROPOSAL_FIELDS = [
    { key: 'kcal', label: 'kcal', unit: '', digits: 0 },
    { key: 'proteinG', label: 'Eiweiß', unit: ' g', digits: 1 },
    { key: 'carbsG', label: 'Kohlenhydrate', unit: ' g', digits: 1 },
    { key: 'fatG', label: 'Fett', unit: ' g', digits: 1 },
];

// Der zuletzt geholte Vorschlag, bis er bestaetigt oder verworfen wird.
let proposal = null;

/**
 * Laesst das Textfeld mit seinem Inhalt wachsen.
 *
 * <p>Erst auf `auto` zuruecksetzen, dann auf die Scrollhoehe: ohne den ersten
 * Schritt kennt scrollHeight nur die aktuelle, schon vergroesserte Hoehe und das
 * Feld koennte nie wieder schrumpfen. Die Obergrenze steht im CSS (max-height),
 * ab dort scrollt das Feld wieder.
 */
function autoGrow(field) {
    field.style.height = 'auto';
    field.style.height = `${field.scrollHeight}px`;
}

function proposalFieldIds() {
    return ['proposal-name', 'proposal-portion', 'proposal-grams',
        ...PROPOSAL_FIELDS.map(f => `pf-${f.key}`)];
}

function renderProposal() {
    const box = document.getElementById('proposal');
    if (!proposal) {
        box.hidden = true;
        return;
    }
    box.hidden = false;

    document.getElementById('proposal-name').value = proposal.name;

    const badge = document.getElementById('proposal-badge');
    badge.textContent = proposal.known ? 'Bekanntes Gericht' : 'Neues Gericht';
    badge.className = `badge ${proposal.known ? 'badge-known' : 'badge-new'}`;

    document.getElementById('proposal-note').textContent = proposal.note || '';

    // Naehrwerte je 100 g als Eingabefelder, jeweils mit ihrer Herkunft daneben.
    const values = document.getElementById('proposal-values');
    values.replaceChildren();
    PROPOSAL_FIELDS.forEach(field => {
        const source = VALUE_SOURCES[proposal.valueSources[field.key]] || VALUE_SOURCES.estimated;
        const row = document.createElement('div');
        row.className = 'proposal-value';

        const label = document.createElement('label');
        label.className = 'pv-label';
        label.htmlFor = `pf-${field.key}`;
        label.textContent = field.label + (field.unit ? ` (${field.unit.trim()})` : '');

        const input = document.createElement('input');
        input.type = 'number';
        input.id = `pf-${field.key}`;
        input.step = field.digits ? '0.1' : '1';
        input.min = '0';
        input.max = field.key === 'kcal' ? '1000' : '100';
        input.value = round(proposal.per100g[field.key], field.digits);

        const mark = document.createElement('span');
        mark.className = `pv-source src-${source.tone}`;
        mark.textContent = source.label;
        markEditedOnInput(input, mark);

        row.append(label, input, mark);
        values.appendChild(row);
    });

    document.getElementById('proposal-unit').textContent =
        'Angaben je 100 g. Was der Agent geraten hat, lässt sich hier direkt korrigieren.';

    const grams = document.getElementById('proposal-grams');
    grams.value = Math.round(proposal.grams);
    const gramsMark = document.getElementById('proposal-grams-source');
    const gramsSource = VALUE_SOURCES[proposal.valueSources.grams] || VALUE_SOURCES.estimated;
    gramsMark.className = `pv-source src-${gramsSource.tone}`;
    gramsMark.textContent = gramsSource.label;
    markEditedOnInput(grams, gramsMark);

    document.getElementById('proposal-portion').value =
        proposal.portionG == null ? '' : Math.round(proposal.portionG);
}

/** Sobald jemand tippt, ist die Herkunft "geaendert" - und bleibt es. */
function markEditedOnInput(input, mark) {
    input.addEventListener('input', () => {
        mark.className = 'pv-source src-edited';
        mark.textContent = VALUE_SOURCES.edited.label;
    }, { once: true });
}

function round(value, digits) {
    if (value == null) return '';
    const factor = 10 ** (digits || 0);
    return Math.round(value * factor) / factor;
}

/**
 * Was beim Bestaetigen gesendet wird.
 *
 * <p>Ein bekanntes Gericht wird nur dann ueber seine Id gebucht, wenn Name und
 * Naehrwerte unveraendert sind - dann bleibt die gepflegte Fassung unangetastet.
 * Hat jemand etwas korrigiert, ist genau das die Aussage: die neuen Werte gehen
 * als Gericht mit und aktualisieren den Eintrag in der Liste.
 */
function proposalPayload() {
    const name = document.getElementById('proposal-name').value.trim();
    const per100g = {};
    PROPOSAL_FIELDS.forEach(field => {
        per100g[field.key] = parseFloat(document.getElementById(`pf-${field.key}`).value);
    });
    const portionRaw = document.getElementById('proposal-portion').value;
    const portionG = portionRaw === '' ? null : parseFloat(portionRaw);

    const unchanged = proposal.known
        && name.toLowerCase() === proposal.name.toLowerCase()
        && PROPOSAL_FIELDS.every(field =>
            Math.abs(per100g[field.key] - proposal.per100g[field.key]) < 0.05)
        && (portionG ?? null) === (proposal.portionG == null ? null : Math.round(proposal.portionG));

    if (unchanged) {
        return { dishId: proposal.dishId, name };
    }
    return {
        name,
        dish: {
            name,
            kcal: per100g.kcal,
            proteinG: per100g.proteinG,
            carbsG: per100g.carbsG,
            fatG: per100g.fatG,
            portionG,
        },
    };
}

// Eine Auswertung mit Nachschlagen dauert bis zu einer Minute. Ohne sichtbar
// laufende Uhr wirkt das wie ein haengendes Fenster, und man drueckt neu.
let progressTimer = null;

function startProgressClock() {
    const label = document.querySelector('#quick-progress .progress-label');
    const started = Date.now();
    const tick = () => {
        const seconds = Math.round((Date.now() - started) / 1000);
        label.textContent = seconds < 3
            ? 'Gericht wird verarbeitet …'
            : `Gericht wird verarbeitet … (${seconds} s)`;
    };
    tick();
    clearInterval(progressTimer);
    progressTimer = setInterval(tick, 1000);
}

function stopProgressClock() {
    clearInterval(progressTimer);
    progressTimer = null;
    document.querySelector('#quick-progress .progress-label').textContent =
        'Gericht wird verarbeitet …';
}

function initQuickCapture() {
    const open = document.getElementById('quick-open');
    const panel = document.getElementById('quick-capture');
    const text = document.getElementById('quick-text');
    const submit = document.getElementById('quick-submit');
    const cancel = document.getElementById('quick-cancel');
    const progress = document.getElementById('quick-progress');
    const msg = document.getElementById('quick-msg');

    const close = () => {
        panel.hidden = true;
        open.hidden = !quickCaptureAvailable;
        text.value = '';
        msg.textContent = '';
        msg.className = 'form-msg';
        proposal = null;
        renderProposal();
        autoGrow(text);
    };

    open.addEventListener('click', () => {
        panel.hidden = false;
        open.hidden = true;
        // Erst messen, wenn das Feld auch sichtbar ist - in einem
        // ausgeblendeten Element ist scrollHeight 0.
        autoGrow(text);
        text.focus();
    });

    text.addEventListener('input', () => autoGrow(text));
    cancel.addEventListener('click', close);

    const run = async () => {
        const value = text.value.trim();
        if (!value) {
            msg.textContent = 'Bitte etwas eintippen.';
            msg.className = 'form-msg err';
            return;
        }
        msg.textContent = '';
        msg.className = 'form-msg';
        proposal = null;
        renderProposal();
        progress.hidden = false;
        startProgressClock();
        // Waehrend der Auswertung nichts anfassbar lassen: der Aufruf dauert
        // Sekunden, und ein zweites Absenden startet eine zweite Session.
        submit.disabled = true;
        cancel.disabled = true;
        text.disabled = true;
        try {
            proposal = await fetchJson('/api/food/quick-capture', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ date: currentDate, text: value, meal: addMeal }),
            });
            renderProposal();
        } catch (err) {
            msg.textContent = `Fehler: ${err.message}`;
            msg.className = 'form-msg err';
        } finally {
            stopProgressClock();
            progress.hidden = true;
            submit.disabled = false;
            cancel.disabled = false;
            text.disabled = false;
        }
    };

    submit.addEventListener('click', run);
    // Enter wertet aus, Shift+Enter macht eine neue Zeile. Umgekehrt waere es
    // die Vorgabe eines Textfeldes - hier tippt aber niemand Absaetze, sondern
    // einen Satz, und der soll mit Enter losgehen. Strg/Cmd+Enter bleibt
    // zusaetzlich moeglich, weil es in Eingabefeldern verbreitet ist.
    text.addEventListener('keydown', event => {
        if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return;
        event.preventDefault();
        run();
    });

    document.getElementById('proposal-discard').addEventListener('click', () => {
        proposal = null;
        renderProposal();
        text.focus();
    });

    document.getElementById('proposal-confirm').addEventListener('click', async () => {
        if (!proposal) return;
        const msgEl = document.getElementById('proposal-msg');
        const grams = parseFloat(document.getElementById('proposal-grams').value);
        if (!(grams > 0)) {
            msgEl.textContent = 'Bitte eine Menge angeben.';
            msgEl.className = 'form-msg err';
            return;
        }
        const payload = proposalPayload();
        if (!payload.name) {
            msgEl.textContent = 'Bitte einen Namen angeben.';
            msgEl.className = 'form-msg err';
            return;
        }
        // Bestaetigt wird ueber den normalen Eintrags-Endpunkt: derselbe Weg und
        // dieselben Grenzen wie bei einer Eingabe von Hand.
        const body = { date: currentDate, grams, meal: proposal.meal };
        if (payload.dishId) {
            body.dishId = payload.dishId;
        } else {
            body.dish = payload.dish;
        }
        try {
            await fetchJson('/api/food/entries', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
            });
            const name = payload.name;
            close();
            document.getElementById('add-dialog').close();
            showDayMessage(`${name}, ${num(grams)} g eingetragen.`);
            await loadAll();
        } catch (err) {
            msgEl.textContent = `Fehler: ${err.message}`;
            msgEl.className = 'form-msg err';
        }
    });
}

async function loadFeatures() {
    try {
        const features = await fetchJson('/api/food/features');
        quickCaptureAvailable = !!(features && features.quickCapture);
    } catch (err) {
        quickCaptureAvailable = false;
    }
    document.getElementById('quick-open').hidden =
        !quickCaptureAvailable || !document.getElementById('quick-capture').hidden;
}

// --- Verlauf ----------------------------------------------------------------

/**
 * Holt die 7-Tage-Mittel aus der Weight-App. Bewusst das Mittel und nicht den
 * Tageswert: neben Tagessummen an Kalorien ist die geglaettete Linie die
 * Aussage, die man sehen will - das Tagesgewicht schwankt um mehrere hundert
 * Gramm aus Gruenden, die mit dem Essen nichts zu tun haben.
 */
async function loadWeightSeries() {
    try {
        const points = await fetchJson(`${WEIGHT_API}/api/weight/last90`, { credentials: 'include' });
        weightByDate = Object.fromEntries(
            (points || []).filter(p => p.avg7 != null).map(p => [p.date, p.avg7]));
        weightError = null;
    } catch (err) {
        // Kein harter Fehler: der kcal-Verlauf steht auch ohne Gewicht.
        weightByDate = {};
        weightError = err.message;
    }
}

async function loadHistory() {
    // Fenster endet immer heute, unabhaengig vom oben gewaehlten Tag: der
    // Verlauf ist ein Ueberblick, kein zweiter Blick auf denselben Tag.
    const to = todayIso();
    const from = shiftDate(to, -(historyDays - 1));
    const [totals] = await Promise.all([
        fetchJson(`/api/food/daily?from=${from}&to=${to}`),
        showWeight && !Object.keys(weightByDate).length ? loadWeightSeries() : Promise.resolve(),
    ]);
    dailyTotals = totals || [];
    renderHistory(from, to);
}

/** Steht an dieser Stelle ein Wert, dessen Nachbarn beide fehlen? */
function isolatedPoint(values, index) {
    if (values[index] == null) return false;
    return values[index - 1] == null && values[index + 1] == null;
}

function renderHistory(from, to) {
    document.getElementById('history-heading').textContent = `Verlauf – letzte ${historyDays} Tage`;

    // Jeden Kalendertag als Label, auch die ohne Eintrag: sonst ruecken Luecken
    // zusammen und der Verlauf sieht dichter aus, als er ist.
    const labels = [];
    for (let date = from; date <= to; date = shiftDate(date, 1)) {
        labels.push(date);
    }

    const byDate = Object.fromEntries(dailyTotals.map(t => [t.date, t.consumed]));
    const target = (day && day.targets.kcal) || 0;
    const kcal = labels.map(d => (d in byDate ? byDate[d].kcal : null));

    const datasets = [
        {
            type: 'line',
            label: 'kcal',
            data: kcal,
            borderColor: CHART_COLORS.kcal,
            backgroundColor: CHART_COLORS.kcal,
            borderWidth: 2.5,
            // Abschnitte oberhalb des Ziels rot: die Ziellinie allein sagt es
            // zwar auch, aber eine Kurve, die dort die Farbe wechselt, faellt
            // schneller auf als ein Schnittpunkt.
            segment: {
                borderColor: ctx => (ctx.p0.parsed.y > target || ctx.p1.parsed.y > target
                    ? CHART_COLORS.kcalOver
                    : CHART_COLORS.kcal),
            },
            // NICHT ueberbruecken: Tage ohne Eintrag sind unbekannt, nicht null.
            spanGaps: false,
            // Ein Tag, der allein zwischen zwei Luecken steht, hat kein
            // Liniensegment und waere sonst unsichtbar.
            pointRadius: ctx => (isolatedPoint(kcal, ctx.dataIndex) ? 3 : 0),
            pointBackgroundColor: ctx => (kcal[ctx.dataIndex] > target
                ? CHART_COLORS.kcalOver
                : CHART_COLORS.kcal),
            pointHoverRadius: 4,
            tension: 0.25,
            yAxisID: 'y',
            order: 10,
        },
        {
            type: 'line',
            label: 'Tagesziel',
            data: labels.map(() => target),
            borderColor: CHART_COLORS.target,
            borderDash: [6, 4],
            borderWidth: 1.5,
            pointRadius: 0,
            yAxisID: 'y',
            order: 5,
        },
    ];

    if (showWeight) {
        datasets.push({
            type: 'line',
            label: 'Gewicht (7-Tage-Mittel)',
            data: labels.map(d => (d in weightByDate ? weightByDate[d] : null)),
            borderColor: CHART_COLORS.weight,
            backgroundColor: CHART_COLORS.weight,
            borderWidth: 2.5,
            pointRadius: 0,
            spanGaps: true,
            tension: 0.3,
            yAxisID: 'yWeight',
            order: 1,
        });
    }

    const config = {
        data: { labels, datasets },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            scales: {
                y: { position: 'left', beginAtZero: true, title: { display: true, text: 'kcal' } },
                yWeight: {
                    position: 'right',
                    display: showWeight,
                    // Eigene Skalierung mit eigenem Gitternetz, das nicht in die
                    // Flaeche gezeichnet wird: die kcal-Achse behaelt so ihre
                    // Grenzen, und es liegen nicht zwei Raster uebereinander.
                    grid: { drawOnChartArea: false },
                    title: { display: true, text: 'kg' },
                },
                // maxRotation: 0 haelt die Datumsbeschriftung waagerecht, damit
                // das Einblenden des Gewichts nicht den ganzen Chart-Boden umbaut.
                x: { ticks: { maxTicksLimit: 10, autoSkip: true, maxRotation: 0 } },
            },
            plugins: { legend: { display: false } },
        },
    };

    if (historyChart) {
        historyChart.data = config.data;
        historyChart.options = config.options;
        historyChart.update();
    } else {
        historyChart = new Chart(document.getElementById('history-chart'), config);
    }

    // Eingeblendetes Gewicht ohne einen einzigen Punkt im Fenster sieht aus wie
    // ein Defekt - deshalb sagen, dass es an den Daten liegt und nicht am Abruf.
    const weightPointsInRange = showWeight && labels.some(d => d in weightByDate);
    const msg = document.getElementById('history-msg');
    if (weightError) {
        msg.textContent = `Gewicht nicht verfügbar: ${weightError} (Weight Tracker unter ${WEIGHT_API})`;
    } else if (showWeight && !weightPointsInRange) {
        msg.textContent = 'Für diesen Zeitraum liegen keine Gewichtsdaten vor.';
    } else {
        msg.textContent = '';
    }
}

function initHistoryControls() {
    const ranges = document.getElementById('range-select');
    HISTORY_RANGES.forEach(days => {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'ghost';
        button.textContent = `${days} Tage`;
        button.classList.toggle('active', days === historyDays);
        button.addEventListener('click', async () => {
            historyDays = days;
            ranges.querySelectorAll('button').forEach(b =>
                b.classList.toggle('active', b === button));
            await loadHistory();
        });
        ranges.appendChild(button);
    });

    const toggles = document.getElementById('history-toggles');
    const label = document.createElement('label');
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.checked = showWeight;
    checkbox.addEventListener('change', async () => {
        showWeight = checkbox.checked;
        await loadHistory();
    });
    const swatch = document.createElement('span');
    swatch.className = 'swatch';
    swatch.style.backgroundColor = CHART_COLORS.weight;
    label.append(checkbox, swatch, document.createTextNode('Körpergewicht'));
    toggles.appendChild(label);
}

// --- Laden und Formulare ----------------------------------------------------

async function loadAll() {
    const [dayData, dishData] = await Promise.all([
        fetchJson(`/api/food/day?date=${currentDate}`),
        fetchJson('/api/food/dishes'),
    ]);
    day = dayData;
    dishes = dishData || [];
    document.getElementById('day-date').value = currentDate;
    renderGauges();
    renderEntries();
    renderDishList();
    fillTargetsForm();
    await loadHistory();
}

/**
 * Kurze Rueckmeldung unter den Kacheln. Das Eingabefenster ist zu dem Zeitpunkt
 * schon zu, die Meldung braucht also einen Platz auf der Seite - und sie
 * verschwindet von selbst, damit sie nicht als Dauerzustand missverstanden wird.
 */
let dayMessageTimer = null;

function showDayMessage(text) {
    const el = document.getElementById('load-msg');
    el.textContent = text;
    el.classList.add('ok');
    clearTimeout(dayMessageTimer);
    dayMessageTimer = setTimeout(() => {
        el.textContent = '';
        el.classList.remove('ok');
    }, 8000);
}

async function reload() {
    try {
        await loadAll();
    } catch (err) {
        const el = document.getElementById('load-msg');
        el.classList.remove('ok');
        el.textContent = `Daten konnten nicht geladen werden: ${err.message}`;
    }
}

async function deleteEntry(id) {
    try {
        await fetchJson(`/api/food/entries/${encodeURIComponent(id)}`, { method: 'DELETE' });
        await loadAll();
    } catch (err) {
        document.getElementById('load-msg').textContent = `Fehler: ${err.message}`;
    }
}

function initDayNav() {
    const input = document.getElementById('day-date');
    const go = date => { currentDate = date; reload(); };
    document.getElementById('day-prev').addEventListener('click', () => go(shiftDate(currentDate, -1)));
    document.getElementById('day-next').addEventListener('click', () => go(shiftDate(currentDate, 1)));
    document.getElementById('day-today').addEventListener('click', () => go(todayIso()));
    input.addEventListener('change', () => { if (input.value) go(input.value); });
}

function initEntryForm() {
    const form = document.getElementById('entry-form');
    const select = document.getElementById('in-dish');
    const grams = document.getElementById('in-grams');
    const msg = document.getElementById('entry-msg');

    form.addEventListener('submit', async event => {
        event.preventDefault();
        msg.textContent = '';
        msg.className = 'form-msg';

        const body = { date: currentDate, grams: parseFloat(grams.value), meal: addMeal };
        if (select.value === NEW_DISH) {
            body.dish = {
                name: document.getElementById('nd-name').value,
                kcal: parseFloat(document.getElementById('nd-kcal').value),
                proteinG: parseFloat(document.getElementById('nd-protein').value),
                carbsG: parseFloat(document.getElementById('nd-carbs').value),
                fatG: parseFloat(document.getElementById('nd-fat').value),
                portionG: document.getElementById('nd-portion').value === ''
                    ? null
                    : parseFloat(document.getElementById('nd-portion').value),
            };
        } else if (select.value) {
            body.dishId = select.value;
        } else {
            msg.textContent = 'Bitte ein Gericht wählen.';
            msg.classList.add('err');
            return;
        }

        try {
            await fetchJson('/api/food/entries', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
            });
            grams.value = '';
            ['nd-name', 'nd-kcal', 'nd-protein', 'nd-carbs', 'nd-fat', 'nd-portion']
                .forEach(id => { document.getElementById(id).value = ''; });
            resetDishSearch();
            document.getElementById('add-dialog').close();
            showDayMessage('Eingetragen.');
            await loadAll();
        } catch (err) {
            msg.textContent = `Fehler: ${err.message}`;
            msg.classList.add('err');
        }
    });
}

function fillTargetsForm() {
    if (!day) return;
    const fields = {
        'tg-kcal': day.targets.kcal,
        'tg-protein': day.targets.proteinG,
        'tg-carbs': day.targets.carbsG,
        'tg-fat': day.targets.fatG,
    };
    Object.entries(fields).forEach(([id, value]) => {
        const input = document.getElementById(id);
        // Nicht ueberschreiben, waehrend jemand gerade tippt.
        if (document.activeElement !== input) input.value = value;
    });
    updateTargetsCheck();
}

/**
 * Rechnet die Makroziele in kcal um und zeigt die Differenz zum kcal-Ziel. Die
 * vier Zahlen sind unabhaengig voneinander eingebbar, koennen also auseinander
 * laufen - das hier macht sichtbar, ob sie noch zusammenpassen.
 */
function updateTargetsCheck() {
    const kcal = parseFloat(document.getElementById('tg-kcal').value);
    const protein = parseFloat(document.getElementById('tg-protein').value);
    const carbs = parseFloat(document.getElementById('tg-carbs').value);
    const fat = parseFloat(document.getElementById('tg-fat').value);
    const el = document.getElementById('targets-check');
    if ([kcal, protein, carbs, fat].some(v => Number.isNaN(v))) {
        el.textContent = '';
        return;
    }
    const fromMacros = protein * 4 + carbs * 4 + fat * 9;
    const diff = Math.round(fromMacros - kcal);
    el.textContent = `Aus den Makros gerechnet: ${num(Math.round(fromMacros))} kcal`
        + (diff === 0 ? ' – geht genau auf.' : ` (${diff > 0 ? '+' : ''}${num(diff)} kcal gegenüber dem kcal-Ziel).`);
}

function initTargetsForm() {
    const form = document.getElementById('targets-form');
    const msg = document.getElementById('targets-msg');
    ['tg-kcal', 'tg-protein', 'tg-carbs', 'tg-fat'].forEach(id => {
        document.getElementById(id).addEventListener('input', updateTargetsCheck);
    });

    form.addEventListener('submit', async event => {
        event.preventDefault();
        await withMessage(msg, async () => {
            await fetchJson('/api/food/targets', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    kcal: parseFloat(document.getElementById('tg-kcal').value),
                    proteinG: parseFloat(document.getElementById('tg-protein').value),
                    carbsG: parseFloat(document.getElementById('tg-carbs').value),
                    fatG: parseFloat(document.getElementById('tg-fat').value),
                }),
            });
            await loadAll();
        });
    });
}

initDayNav();
initEntryForm();
initTargetsForm();
initHistoryControls();
initAddDialog();
initDishSearch();
initQuickCapture();
loadFeatures();
reload();
