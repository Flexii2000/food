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

let currentDate = todayIso();
let dishes = [];
let day = null;

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

function toneFor(ratio, direction) {
    if (direction === 'floor') {
        if (ratio >= 1) return 'good';
        return ratio >= 0.8 ? 'near' : 'neutral';
    }
    if (ratio > 1) return 'bad';
    return ratio >= 0.85 ? 'warn' : 'neutral';
}

function gauge({ ratio, tone, main, sub, mainSize = 20, subSize = 8 }) {
    // Ueber 100 % laeuft der Bogen nicht weiter - er ist dann voll und rot; die
    // Zahl in der Mitte traegt die eigentliche Information.
    const filled = Math.max(0, Math.min(ratio, 1)) * SWEEP * C;
    // Zahl und Beschriftung sitzen als Paar mittig: die Zahl etwas ueber der
    // Mitte, das Label darunter - beide auf y=50 saehen nach unten verrutscht aus.
    const mainY = 50 - subSize * 0.6;
    return `
        <svg class="gauge tone-${tone}" viewBox="0 0 100 100" role="img" aria-label="${main} ${sub}">
            <circle class="gauge-track" cx="50" cy="50" r="${R}"
                    stroke-dasharray="${SWEEP * C} ${C}" transform="rotate(135 50 50)"></circle>
            <circle class="gauge-value" cx="50" cy="50" r="${R}"
                    stroke-dasharray="${filled} ${C}" transform="rotate(135 50 50)"></circle>
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
        const ratio = target > 0 ? consumed / target : 0;
        return `
            <div class="macro">
                ${gauge({
                    ratio,
                    tone: toneFor(ratio, macro.direction),
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
    const ratio = target > 0 ? consumed / target : 0;
    kcalBox.innerHTML = gauge({
        ratio,
        tone: toneFor(ratio, 'ceiling'),
        main: num(Math.abs(remaining)),
        sub: remaining < 0 ? 'kcal drüber' : 'kcal übrig',
        mainSize: 19,
        subSize: 8,
    });

    document.getElementById('kcal-consumed').textContent = `${num(consumed)} kcal`;
    document.getElementById('kcal-target').textContent = `von ${num(target)} kcal`;
}

// --- Eintraege des Tages ----------------------------------------------------

function renderEntries() {
    const container = document.getElementById('entries');
    container.replaceChildren();

    const heading = document.getElementById('entries-heading');
    heading.textContent = currentDate === todayIso()
        ? 'Heute gegessen'
        : `Gegessen am ${fmtDate(currentDate)}`;

    const entries = (day && day.entries) || [];
    if (!entries.length) {
        const empty = document.createElement('p');
        empty.className = 'hint';
        empty.textContent = 'Noch nichts eingetragen.';
        container.appendChild(empty);
        return;
    }

    const table = document.createElement('table');
    table.className = 'entries';
    table.innerHTML = `
        <thead><tr>
            <th>Gericht</th><th class="n">Menge</th><th class="n">kcal</th>
            <th class="n">E</th><th class="n">KH</th><th class="n">F</th><th></th>
        </tr></thead><tbody></tbody>`;
    const body = table.querySelector('tbody');

    entries.forEach(entry => {
        const factor = entry.grams / 100;
        const row = document.createElement('tr');
        row.innerHTML = `
            <td>${escapeHtml(entry.name)}</td>
            <td class="n">${num(entry.grams)} g</td>
            <td class="n">${num(entry.per100g.kcal * factor)}</td>
            <td class="n">${num(entry.per100g.proteinG * factor)}</td>
            <td class="n">${num(entry.per100g.carbsG * factor)}</td>
            <td class="n">${num(entry.per100g.fatG * factor)}</td>`;
        const cell = document.createElement('td');
        const remove = document.createElement('button');
        remove.type = 'button';
        remove.className = 'row-remove';
        remove.textContent = '×';
        remove.title = 'Eintrag löschen';
        remove.setAttribute('aria-label', `${entry.name} löschen`);
        remove.addEventListener('click', () => deleteEntry(entry.id));
        cell.appendChild(remove);
        row.appendChild(cell);
        body.appendChild(row);
    });
    container.appendChild(table);
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

function renderDishSelect() {
    const select = document.getElementById('in-dish');
    const previous = select.value;
    select.replaceChildren();

    const placeholder = new Option('– bitte wählen –', '');
    placeholder.disabled = true;
    select.appendChild(placeholder);

    dishes.forEach(dish => {
        const suffix = dish.portionG ? ` · Portion ${num(dish.portionG)} g` : '';
        select.appendChild(new Option(
            `${dish.name} (${num(dish.per100g.kcal)} kcal/100 g${suffix})`, dish.id));
    });
    select.appendChild(new Option('＋ Neues Gericht …', NEW_DISH));

    select.value = dishes.some(d => d.id === previous) || previous === NEW_DISH ? previous : '';
    if (!select.value) placeholder.selected = true;
    onDishChange();
}

function selectedDish() {
    const id = document.getElementById('in-dish').value;
    return dishes.find(d => d.id === id) || null;
}

const NEW_DISH_REQUIRED = ['nd-name', 'nd-kcal', 'nd-protein', 'nd-carbs', 'nd-fat'];

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
                    document.getElementById('in-grams').value =
                        Math.round(dish.portionG * factor);
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
    renderDishSelect();
    renderDishList();
    fillTargetsForm();
}

async function reload() {
    try {
        await loadAll();
        document.getElementById('load-msg').textContent = '';
    } catch (err) {
        document.getElementById('load-msg').textContent =
            `Daten konnten nicht geladen werden: ${err.message}`;
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

    select.addEventListener('change', onDishChange);

    form.addEventListener('submit', async event => {
        event.preventDefault();
        msg.textContent = '';
        msg.className = 'form-msg';

        const body = { date: currentDate, grams: parseFloat(grams.value) };
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
            msg.textContent = 'Eingetragen.';
            msg.classList.add('ok');
            grams.value = '';
            ['nd-name', 'nd-kcal', 'nd-protein', 'nd-carbs', 'nd-fat', 'nd-portion']
                .forEach(id => { document.getElementById(id).value = ''; });
            select.value = '';
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
reload();
