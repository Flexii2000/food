// Feature Requests: Liste der eigenen Wuensche, ein neuer Wunsch (Claude entwirft
// die Story Card, die Person gibt sie frei) und die Kartenseite, auf die die
// Unteraufgabe in Felix' To-Do zeigt. Eine Seite; welche Ansicht gilt, steht in
// der Adresse - so fuehrt der Link aus dem To-Do direkt zur Karte, und Zurueck im
// Browser tut, was man erwartet.

const BASE = '/feature-requests/';
const API = '/feature-requests/api';
const NEW_PATH = `${BASE}neu`;

const MAX_CRITERIA = 10;

// Wie oft nach dem Entwurf gefragt wird, und wann aufgegeben. Die Obergrenze
// liegt ueber dem Zeitlimit der Session (food.story-agent.timeout-seconds) plus
// Warteschlange: im Zweifel soll dessen Meldung ankommen, nicht diese.
const POLL_INTERVAL_MS = 1500;
const POLL_LIMIT_MS = 200000;

let features = { me: '', owner: false, drafting: false };

// Die gerade gezeigte Karte - fuer den Knopf "Loeschen".
let shownCard = null;

// Der angefangene Wunsch. Er bleibt stehen, wenn man zwischendurch zur Liste
// geht: ein Entwurf ist eine Claude-Session, und die soll nicht an einem
// versehentlichen Zurueck verloren gehen. Weg ist er erst nach dem Absenden.
const wish = {
    text: '',
    step: 'wish',   // 'wish' = eigener Text, 'card' = Karte bearbeiten
    card: null,     // { title, story, acceptanceCriteria[] }
    error: '',
    drafting: false,
    run: 0,         // zaehlt Entwuerfe - ein veralteter darf keinen neueren ueberschreiben
    startedAt: 0,
};

const $ = id => document.getElementById(id);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function fetchJson(url, options) {
    const res = await fetch(url, options);
    if (!res.ok) {
        let message = `HTTP ${res.status}`;
        try {
            const body = await res.json();
            if (body && body.message) message = body.message;
        } catch (e) { /* Fehlerkoerper ist nicht immer JSON - dann bleibt der Status. */ }
        const error = new Error(message);
        error.status = res.status;
        throw error;
    }
    return res.status === 204 ? null : res.json();
}

function postJson(url, body) {
    return fetchJson(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
}

function displayName(name) {
    return name ? name.charAt(0).toUpperCase() + name.slice(1) : '';
}

function formatDate(iso) {
    return new Date(iso).toLocaleDateString('de-DE', { day: 'numeric', month: 'short', year: 'numeric' });
}

function setStatus(element, status) {
    const done = status === 'done';
    element.textContent = done ? 'erledigt' : 'offen';
    element.className = `status ${done ? 'status-done' : 'status-open'}`;
}

function setMessage(id, text, isError) {
    const element = $(id);
    element.textContent = text;
    element.className = `form-msg${isError ? ' err' : ''}`;
}

/**
 * Laesst ein Textfeld mit seinem Inhalt wachsen. Erst auf `auto`, dann auf die
 * Scrollhoehe: ohne den ersten Schritt kennt scrollHeight nur die schon
 * vergroesserte Hoehe, und das Feld schrumpfte nie wieder.
 */
function autoGrow(field) {
    field.style.height = 'auto';
    field.style.height = `${field.scrollHeight + 2}px`;
}

let toastTimer = null;

function toast(text, ok) {
    const element = $('toast');
    element.textContent = text;
    element.className = `toast${ok ? ' ok' : ''}`;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { element.textContent = ''; }, ok ? 3500 : 6000);
}

// --- Ansichten ---------------------------------------------------------------

function currentRoute() {
    const path = location.pathname;
    const rest = path.startsWith(BASE) ? decodeURIComponent(path.slice(BASE.length)) : '';
    if (!rest) return { view: 'list' };
    if (`${BASE}${rest}` === NEW_PATH) return { view: 'new' };
    return { view: 'card', id: rest };
}

/** Wechselt die Ansicht; der Eintrag im Verlauf merkt sich, dass er von hier stammt. */
function navigate(path, replace) {
    if (location.pathname !== path) {
        history[replace ? 'replaceState' : 'pushState']({ app: true }, '', path);
    }
    render();
}

/**
 * Zurueck: innerhalb der Seite wie der Zurueck-Knopf des Browsers. Kam man von
 * aussen (der Link aus dem To-Do), gibt es kein "davor" auf dieser Seite - dann
 * zur Liste, statt die Seite zu verlassen.
 */
function back() {
    if (history.state && history.state.app) {
        history.back();
    } else {
        navigate(BASE);
    }
}

function render() {
    const route = currentRoute();
    $('list-view').hidden = route.view !== 'list';
    $('new-view').hidden = route.view !== 'new';
    $('card-view').hidden = route.view !== 'card';
    $('new-button').hidden = route.view === 'new';
    document.title = 'Feature Requests';
    window.scrollTo(0, 0);
    if (route.view === 'list') loadList();
    else if (route.view === 'new') showNew();
    else loadCard(route.id);
}

// --- Liste -------------------------------------------------------------------

async function loadList() {
    try {
        renderList(await fetchJson(`${API}/requests`));
    } catch (err) {
        toast(`Liste nicht geladen: ${err.message}`);
    }
}

function renderList(requests) {
    const list = $('request-list');
    list.replaceChildren(...requests.map(request => {
        const title = document.createElement('span');
        title.className = 'request-title';
        title.textContent = request.title;

        // Das Datum reicht fuer die eigenen; nur wer alle sieht, braucht den Namen.
        const meta = document.createElement('span');
        meta.className = 'request-meta';
        meta.textContent = features.owner
            ? `${formatDate(request.createdAt)} · ${displayName(request.author)}`
            : formatDate(request.createdAt);

        const main = document.createElement('span');
        main.className = 'request-main';
        main.append(title, meta);

        const status = document.createElement('span');
        setStatus(status, request.status);

        const link = document.createElement('a');
        link.className = 'request';
        link.href = `${BASE}${encodeURIComponent(request.id)}`;
        link.dataset.nav = '';
        link.append(main, status);

        const item = document.createElement('li');
        item.append(link);
        return item;
    }));
    list.hidden = requests.length === 0;
    $('list-empty').hidden = requests.length > 0;
}

// --- Kartenseite -------------------------------------------------------------

async function loadCard(id) {
    $('cv-card').hidden = true;
    $('cv-status').hidden = true;
    $('cv-missing').hidden = true;
    try {
        renderCard(await fetchJson(`${API}/requests/${encodeURIComponent(id)}`));
    } catch (err) {
        if (err.status === 404) {
            $('cv-missing').hidden = false;
        } else {
            toast(`Karte nicht geladen: ${err.message}`);
        }
    }
}

function renderCard(request) {
    document.title = `${request.title} – Feature Requests`;
    $('cv-title').textContent = request.title;
    $('cv-meta').textContent = features.owner
        ? `${displayName(request.author)} · ${formatDate(request.createdAt)}`
        : formatDate(request.createdAt);
    $('cv-story').textContent = request.story;

    const criteria = request.acceptanceCriteria || [];
    $('cv-criteria').replaceChildren(...criteria.map(text => {
        const item = document.createElement('li');
        item.textContent = text;
        return item;
    }));
    $('cv-criteria-box').hidden = criteria.length === 0;

    $('cv-original').textContent = request.originalText || '';
    $('cv-original-box').hidden = !request.originalText;

    setStatus($('cv-status'), request.status);
    $('cv-status').hidden = false;
    shownCard = request;
    $('cv-actions').hidden = !features.owner;
    $('cv-card').hidden = false;
}

/**
 * Loescht die gezeigte Karte samt Unteraufgabe im To-Do - nach Rueckfrage wie
 * beim Loeschen eines Gerichts im Kalorienzaehler: geloescht ist geloescht.
 */
async function deleteCard() {
    if (!shownCard || !confirm(`„${shownCard.title}“ löschen?`)) {
        return;
    }
    const button = $('cv-delete');
    button.disabled = true;
    try {
        await fetchJson(`${API}/requests/${encodeURIComponent(shownCard.id)}`, { method: 'DELETE' });
        shownCard = null;
        navigate(BASE, true);
        toast('Gelöscht.', true);
    } catch (err) {
        toast(`Nicht gelöscht: ${err.message}`);
    } finally {
        button.disabled = false;
    }
}

// --- Neuer Wunsch ------------------------------------------------------------

function showNew() {
    $('draft-button').textContent = features.drafting ? 'Entwurf erstellen' : 'Weiter';
    $('wish-text').value = wish.text;
    $('wish-step').hidden = wish.step !== 'wish';
    $('card-form').hidden = wish.step !== 'card';
    showDrafting();
    if (wish.step === 'card') {
        renderEditor();
    } else {
        autoGrow($('wish-text'));
        if (!wish.drafting) $('wish-text').focus();
    }
}

let progressTimer = null;

function showDrafting() {
    const on = wish.drafting;
    $('wish-text').disabled = on;
    $('draft-button').disabled = on;
    $('draft-progress').hidden = !on;
    clearInterval(progressTimer);
    if (!on) return;
    const tick = () => {
        const seconds = Math.round((Date.now() - wish.startedAt) / 1000);
        $('draft-progress-label').textContent = seconds < 3
            ? 'Entwurf wird erstellt …'
            : `Entwurf wird erstellt … (${seconds} s)`;
    };
    tick();
    progressTimer = setInterval(tick, 1000);
}

/**
 * Fragt den Stand eines Entwurfs ab, bis er fertig ist. Ein 404 heisst, dass es den
 * Auftrag nicht mehr gibt (Neustart des Dienstes) - das wird gesagt, statt weiter
 * ins Leere zu fragen.
 */
async function awaitDraft(jobId) {
    const deadline = Date.now() + POLL_LIMIT_MS;
    while (Date.now() < deadline) {
        await sleep(POLL_INTERVAL_MS);
        const job = await fetchJson(`${API}/drafts/${encodeURIComponent(jobId)}`);
        if (job.status === 'done') return job.card;
        if (job.status === 'failed') throw new Error(job.error || 'Der Entwurf ist fehlgeschlagen.');
    }
    throw new Error('Der Entwurf dauert ungewöhnlich lange.');
}

function emptyCard() {
    return { title: '', story: '', acceptanceCriteria: [] };
}

async function startDraft() {
    wish.text = $('wish-text').value;
    const text = wish.text.trim();
    if (!text) {
        setMessage('wish-msg', 'Bitte den Wunsch beschreiben.', true);
        $('wish-text').focus();
        return;
    }
    setMessage('wish-msg', '', false);

    // Ohne Claude schreibt die Person die Karte selbst. Was sie dort schon
    // geschrieben hatte, bleibt - "Text aendern" soll keine Arbeit kosten.
    if (!features.drafting) {
        openEditor(wish.card || emptyCard(), '');
        return;
    }

    const run = ++wish.run;
    wish.drafting = true;
    wish.startedAt = Date.now();
    showDrafting();
    try {
        // Der Start antwortet sofort mit einer Auftragsnummer, das Ergebnis wird
        // abgefragt - keine Anfrage haengt eine halbe Minute am Draht.
        const started = await postJson(`${API}/drafts`, { text });
        const card = await awaitDraft(started.jobId);
        if (run === wish.run) openEditor(card, '');
    } catch (err) {
        if (run !== wish.run) return;
        if (err.status === 400) {
            setMessage('wish-msg', err.message, true);
        } else {
            // Kein Entwurf, aber kein Ende: der Editor oeffnet leer, der eigene
            // Text steht darunter, und die Person schreibt die Karte selbst.
            openEditor(emptyCard(), err.message);
        }
    } finally {
        if (run === wish.run) {
            wish.drafting = false;
            if (currentRoute().view === 'new') showDrafting();
        }
    }
}

function openEditor(card, error) {
    const criteria = [...(card.acceptanceCriteria || [])];
    wish.card = {
        title: card.title || '',
        story: card.story || '',
        // Eine leere Zeile, damit man ohne Entwurf nicht erst "hinzufuegen" muss.
        acceptanceCriteria: criteria.length ? criteria : [''],
    };
    wish.error = error || '';
    wish.step = 'card';
    // Kam der Entwurf, waehrend man woanders war, steht er beim naechsten Oeffnen bereit.
    if (currentRoute().view === 'new') showNew();
}

function renderEditor() {
    $('draft-error').textContent = wish.error;
    $('card-title').value = wish.card.title;
    $('card-story').value = wish.card.story;
    renderCriteria();
    const original = wish.text.trim();
    $('card-original').textContent = original;
    $('card-original-box').hidden = !original;
    setMessage('card-msg', '', false);
    autoGrow($('card-story'));
}

function renderCriteria(focusIndex) {
    const criteria = wish.card.acceptanceCriteria;
    const list = $('criteria-edit');
    list.replaceChildren(...criteria.map((text, index) => {
        // Ein Textfeld mit einer Zeile statt eines Eingabefelds: ein Kriterium darf
        // 300 Zeichen haben, und abgeschnitten liesse es sich nicht pruefen.
        const input = document.createElement('textarea');
        input.rows = 1;
        input.maxLength = 300;
        input.value = text;
        input.setAttribute('aria-label', `Kriterium ${index + 1}`);
        input.addEventListener('input', () => {
            // Ein Kriterium ist eine Zeile - eingefuegte Umbrueche werden Leerzeichen.
            if (input.value.includes('\n')) input.value = input.value.replace(/\s*\n\s*/g, ' ');
            criteria[index] = input.value;
            autoGrow(input);
        });
        input.addEventListener('keydown', event => {
            // Enter: naechstes Kriterium darunter - statt das Formular abzuschicken.
            if (event.key === 'Enter' && !event.isComposing) {
                event.preventDefault();
                if (criteria.length < MAX_CRITERIA) {
                    criteria.splice(index + 1, 0, '');
                    renderCriteria(index + 1);
                }
            } else if (event.key === 'Backspace' && input.value === '' && criteria.length > 1) {
                event.preventDefault();
                criteria.splice(index, 1);
                renderCriteria(Math.max(0, index - 1));
            }
        });

        const remove = document.createElement('button');
        remove.type = 'button';
        remove.className = 'icon-button';
        remove.setAttribute('aria-label', `Kriterium ${index + 1} entfernen`);
        remove.innerHTML = '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M5.5 5.5l9 9M14.5 5.5l-9 9"/></svg>';
        remove.addEventListener('click', () => {
            criteria.splice(index, 1);
            renderCriteria(Math.min(index, criteria.length - 1));
        });

        const item = document.createElement('li');
        item.className = 'criterion';
        item.append(input, remove);
        return item;
    }));
    $('add-criterion').hidden = criteria.length >= MAX_CRITERIA;
    const inputs = list.querySelectorAll('textarea');
    inputs.forEach(autoGrow);
    if (focusIndex != null && focusIndex >= 0 && inputs[focusIndex]) {
        inputs[focusIndex].focus();
    }
}

async function submitCard(event) {
    event.preventDefault();
    const card = {
        title: wish.card.title.trim(),
        story: wish.card.story.trim(),
        acceptanceCriteria: wish.card.acceptanceCriteria.map(c => c.trim()).filter(Boolean),
    };
    if (!card.title) {
        setMessage('card-msg', 'Bitte einen Titel angeben.', true);
        $('card-title').focus();
        return;
    }
    if (!card.story) {
        setMessage('card-msg', 'Bitte die User Story ausfüllen.', true);
        $('card-story').focus();
        return;
    }
    const button = $('submit-button');
    button.disabled = true;
    try {
        await postJson(`${API}/requests`, { originalText: wish.text.trim(), ...card });
        Object.assign(wish, { text: '', step: 'wish', card: null, error: '' });
        navigate(BASE, true);
        toast('Wunsch abgeschickt.', true);
    } catch (err) {
        setMessage('card-msg', `Fehler: ${err.message}`, true);
    } finally {
        button.disabled = false;
    }
}

// --- Start -------------------------------------------------------------------

function bind() {
    // Links innerhalb der Seite ohne Neuladen - ausser mit Zusatztaste (neuer Tab).
    document.addEventListener('click', event => {
        const link = event.target.closest('a[data-nav]');
        if (!link || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        navigate(new URL(link.href).pathname);
    });
    document.querySelectorAll('[data-back]').forEach(button => button.addEventListener('click', back));
    window.addEventListener('popstate', render);

    $('new-button').addEventListener('click', () => navigate(NEW_PATH));

    const text = $('wish-text');
    text.addEventListener('input', () => {
        wish.text = text.value;
        autoGrow(text);
    });
    // Enter macht hier eine neue Zeile - ein Wunsch darf Absaetze haben.
    // Abgeschickt wird mit dem Knopf oder mit Strg/Cmd+Enter.
    text.addEventListener('keydown', event => {
        if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
            event.preventDefault();
            startDraft();
        }
    });
    $('draft-button').addEventListener('click', startDraft);

    $('card-title').addEventListener('input', event => { wish.card.title = event.target.value; });
    // Enter im Titel springt zur Story, statt die halbe Karte abzuschicken.
    $('card-title').addEventListener('keydown', event => {
        if (event.key === 'Enter' && !event.isComposing) {
            event.preventDefault();
            $('card-story').focus();
        }
    });
    $('card-story').addEventListener('input', event => {
        wish.card.story = event.target.value;
        autoGrow(event.target);
    });
    $('add-criterion').addEventListener('click', () => {
        wish.card.acceptanceCriteria.push('');
        renderCriteria(wish.card.acceptanceCriteria.length - 1);
    });
    $('edit-wish').addEventListener('click', () => {
        wish.step = 'wish';
        wish.error = '';
        showNew();
    });
    $('card-form').addEventListener('submit', submitCard);
    $('cv-delete').addEventListener('click', deleteCard);
}

async function init() {
    bind();
    try {
        features = await fetchJson(`${API}/features`);
    } catch (err) {
        toast(`Nicht geladen: ${err.message}`);
    }
    $('list-heading').textContent = features.owner ? 'Alle Wünsche' : 'Deine Wünsche';
    render();
}

init();
