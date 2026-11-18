/* Press Sheet: the home screen's whole interface. Talks to the phone through window.Android
   (MainActivity's Bridge). Opened in an ordinary browser it uses a stand-in with sample apps,
   which is how the layout gets tested off the phone. */
(function () {
  'use strict';

  var N = window.Android || mockBridge();
  var MODES = [{ id: 'shop', label: 'Shop' }, { id: 'home', label: 'Home' }, { id: 'show', label: 'Reselling' },
    { id: 'games', label: 'Games' }, { id: 'money', label: 'Financial' }];
  var COLORS = ['white', 'cyan', 'pink', 'yellow', 'black'];
  var SIZES = [{ id: 'small', label: 'Small' }, { id: 'wide', label: 'Wide' }, { id: 'big', label: 'Big' }];
  var PAGES = [
    { label: 'Dashboard', path: '/dashboard' }, { label: 'Orders board', path: '/orders' },
    { label: 'New order', path: '/orders/new' }, { label: 'Production', path: '/production' },
    { label: 'Printers', path: '/printers' }, { label: 'Customers', path: '/customers' },
    { label: 'Calendar', path: '/calendar' }, { label: 'Tasks', path: '/tasks' },
    { label: 'Inventory', path: '/inventory' }, { label: 'Mockup studio', path: '/studio' },
    { label: 'Scan a work order', path: '/scan' }
  ];
  var STATE_VERSION = 2;
  var TICKET = {
    shop: { no: '01', title: 'On the floor today' }, home: { no: '02', title: 'At the house' },
    show: { no: '03', title: 'List it, ship it' }, games: { no: '04', title: 'Game time' },
    money: { no: '05', title: 'Money matters' }
  };

  var apps = [];
  var byPkg = {};
  var state = null;
  var account = { url: '', email: '', name: '', signedIn: false };
  var live = { loading: false, data: null, error: '', at: 0, signedOut: false };
  var pickCb = null;
  var sheetOpen = false;
  var toastTimer = null;

  var $ = function (id) { return document.getElementById(id); };

  // ------------------------------------------------------------------ small helpers

  function h(tag, attrs, kids) {
    var el = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        var v = attrs[k];
        if (v === null || v === undefined || v === false) return;
        if (k === 'class') el.className = v;
        else if (k === 'text') el.textContent = v;
        else el.setAttribute(k, v === true ? '' : v);
      });
    }
    (kids || []).forEach(function (kid) {
      if (kid === null || kid === undefined || kid === false) return;
      el.appendChild(typeof kid === 'string' ? document.createTextNode(kid) : kid);
    });
    return el;
  }

  function uid() { return 't' + Math.random().toString(36).slice(2, 9); }
  /** adopt: once found on the phone, show the app's own name (for apps whose exact name was a guess). */
  function appT(label, want, adopt) { return { type: 'app', label: label, want: want || [label.toLowerCase()], pkg: '', act: '', adopt: !!adopt }; }
  function pageT(label, path) { return { type: 'inkflow', label: label, path: path }; }
  function linkT(label, url) { return { type: 'url', label: label, url: url }; }
  function tile(size, color, t, extra) {
    var o = { id: uid(), size: size, color: color, t: t };
    if (extra) Object.keys(extra).forEach(function (k) { o[k] = extra[k]; });
    return o;
  }

  function defaults() {
    return {
      v: STATE_VERSION,
      mode: 'shop',
      name: '',
      modes: {
        shop: {
          hero: {
            color: 'black', live: 'orders', sub: 'Orders, production and printers', t: pageT('InkFlow', '/dashboard'),
            btns: [{ t: pageT('New order', '/orders/new') }, { t: pageT('Orders board', '/orders') }]
          },
          tiles: [
            tile('big', 'cyan', pageT('Printers', '/printers'), { live: 'printers' }),
            tile('wide', 'yellow', appT('InkStop Mail', ['inkstop mail'])),
            tile('wide', 'pink', pageT('Production', '/production')),
            tile('small', 'white', appT('Nextcloud')),
            tile('small', 'white', appT('Claude')),
            tile('small', 'white', appT('Canva')),
            tile('small', 'white', appT('Calculator'))
          ]
        },
        home: {
          hero: {
            color: 'pink', sub: 'Heat, TVs and cameras', t: appT('Family Hub', ['family hub']),
            btns: [{ name: 'Open Family Hub', t: appT('Family Hub', ['family hub']) }, { t: appT('Home Assistant', ['home assistant']) }]
          },
          tiles: [
            tile('big', 'black', appT('Jellyfin')),
            tile('wide', 'cyan', appT('Immich')),
            tile('wide', 'yellow', appT('FretQuest')),
            tile('small', 'white', appT('JuniCare')),
            tile('small', 'white', appT('Roku', ['roku', 'the roku app'])),
            tile('small', 'white', appT('Paperless', ['paperless'])),
            tile('small', 'white', appT('YouTube'))
          ]
        },
        show: {
          hero: {
            color: 'yellow', sub: 'Snap it once, list it everywhere', t: appT('Lister', ['lister', 'snaplist']),
            btns: [{ name: 'Snap and list', t: appT('Lister', ['lister', 'snaplist']) }, { t: appT('Whatnot') }]
          },
          tiles: [
            tile('big', 'black', appT('Whatnot')),
            tile('wide', 'cyan', appT('eBay')),
            tile('wide', 'pink', appT('PayPal POS', ['paypal pos', 'paypal zettle', 'paypal'])),
            tile('small', 'white', appT('Poshmark')),
            tile('small', 'white', appT('Mercari')),
            tile('small', 'white', appT('Depop')),
            tile('small', 'white', appT('Etsy', ['etsy', 'etsy seller']))
          ]
        },
        games: gamesMode(),
        money: moneyMode()
      },
      clock: { t: appT('Clock', ['clock']) },
      dock: [
        { t: appT('Phone') }, { t: appT('Messages') }, { t: appT('InkStop Mail', ['inkstop mail']) },
        { t: appT('Chrome') }, { t: appT('Starbucks') }
      ]
    };
  }

  function gamesMode() {
    return {
      hero: {
        color: 'cyan', sub: 'Pick up where you left off', t: appT('Disney Solitaire', ['disney solitaire', 'solitaire'], true),
        btns: [{ name: 'Video Poker', t: appT('High Roller Video Poker', ['high roller video poker', 'high roller', 'video poker'], true) },
          { name: 'Tasty Travels', t: appT('Tasty Travels', ['tasty travels', 'tasty'], true) }]
      },
      tiles: [
        tile('big', 'black', appT('Coin Master', ['coin master'])),
        tile('wide', 'yellow', appT('DeckTok', ['decktok', 'deck tok'], true)),
        tile('wide', 'pink', appT('Nintendo', ['nintendo switch', 'nintendo'], true)),
        tile('small', 'white', appT('Play Store', ['play store', 'google play store']))
      ]
    };
  }

  function moneyMode() {
    return {
      hero: {
        color: 'yellow', sub: 'Payments, balances and benefits', t: appT('PayPal', ['paypal']),
        btns: [{ t: appT('Money', ['moneylion', 'money lion', 'mone'], true) }, { t: appT('PayPal POS', ['paypal pos', 'paypal zettle']) }]
      },
      tiles: [
        tile('big', 'black', appT('Progressive', ['progressive'], true)),
        tile('wide', 'cyan', appT('Aetna', ['aetna'], true)),
        tile('wide', 'pink', appT('CVS OTC', ['cvs flex', 'otc', 'cvs'], true)),
        tile('small', 'white', appT('AtoB', ['atob'], true)),
        tile('small', 'white', appT('Murphy USA', ['murphy usa', 'murphy'], true)),
        tile('small', 'white', appT('Rewards', ['microsoft rewards', 'rewards'], true)),
        tile('small', 'white', appT('Calculator', ['calculator']))
      ]
    };
  }

  // ------------------------------------------------------------------ Home Base layout

  var BASE_PAGES = [
    { id: 'shop', label: 'Shop' }, { id: 'show', label: 'Reselling' }, { id: 'home', label: 'Home' },
    { id: 'games', label: 'Games' }, { id: 'money', label: 'Financial' }
  ];
  var BASE_MAX = 30;

  function baseExtras() {
    return {
      shop: [pageT('Production', '/production'), pageT('Printers', '/printers'), pageT('Scan a work order', '/scan'),
        appT('Nextcloud'), appT('Canva'), appT('Fiverr'), appT('Brother', ['brother iprint', 'iprint&scan', 'brother'], true),
        appT('Faire'), appT('Dropbox'), appT('Tailscale'), appT('ntfy'), appT('Claude')],
      show: [appT('Vinted', ['vinted'], true), appT('Camera'), appT('Canva'), appT('PayPal POS', ['paypal pos', 'paypal zettle'])],
      home: [appT('Home Assistant', ['home assistant']), appT('Ring'), appT('Blink'), appT('Alexa', ['amazon alexa', 'alexa']),
        appT('Tapo'), appT('Roku', ['roku', 'the roku app'], true), appT('Family Link', ['family link', 'google family link'], true),
        appT('Calendar'), appT('Hallow')],
      games: [appT('Play Store', ['play store', 'google play store'])],
      money: [appT('Calculator')]
    };
  }

  function targetKey(t) {
    if (!t) return '';
    if (t.type === 'app') return 'a:' + (t.pkg || (t.want && t.want[0]) || t.label).toLowerCase();
    if (t.type === 'inkflow') return 'i:' + t.path;
    if (t.type === 'url') return 'u:' + t.url;
    return 'x:' + t.label;
  }

  function clone(o) { return JSON.parse(JSON.stringify(o)); }

  function seedBase(s) {
    var extras = baseExtras();
    var pages = {};
    BASE_PAGES.forEach(function (p) {
      var mode = s.modes[p.id];
      var list = [], seen = {};
      function add(t) {
        if (!t) return;
        var k = targetKey(t);
        if (seen[k] || list.length >= BASE_MAX) return;
        seen[k] = true;
        list.push({ t: clone(t) });
      }
      if (mode) {
        add(mode.hero.t);
        mode.hero.btns.forEach(function (b) { add(b.t); });
        mode.tiles.forEach(function (x) { add(x.t); });
      }
      (extras[p.id] || []).forEach(add);
      pages[p.id] = list;
    });
    var main = BASE_PAGES.map(function (p) { return { folder: p.id }; });
    [pageT('InkFlow', '/dashboard'), appT('Whatnot'), appT('Lister', ['lister', 'snaplist'], true), appT('Family Hub', ['family hub']),
      appT('Claude'), appT('Photos', ['photos', 'google photos', 'gallery'], true), appT('Immich'), appT('Jellyfin'),
      appT('Calendar'), appT('Maps'), appT('Nextcloud'), appT('YouTube'), appT('Starbucks'), appT('PayPal', ['paypal']),
      appT('Settings')].forEach(function (t) { main.push({ t: t }); });
    return {
      page: 'main',
      main: main,
      pages: pages,
      dock: [{ t: appT('Phone') }, { t: appT('Messages') }, { t: appT('Camera') }, { t: appT('InkStop Mail', ['inkstop mail']) }, { t: appT('Chrome') }],
      photos: { t: appT('Photos', ['photos', 'google photos', 'gallery'], true) }
    };
  }

  function loadState() {
    var s = null;
    var upgraded = false;
    try { s = JSON.parse(N.getState() || 'null'); } catch (e) { s = null; }
    // Version 1 had three modes: keep everything set up there and add Games, Financial and the clock.
    if (s && s.v === 1 && s.modes && s.modes.shop && s.modes.home && s.modes.show && Array.isArray(s.dock)) {
      s.modes.games = gamesMode();
      s.modes.money = moneyMode();
      s.clock = { t: appT('Clock', ['clock']) };
      s.v = 2;
      upgraded = true;
    }
    var complete = s && s.v === STATE_VERSION && s.modes && Array.isArray(s.dock) && s.clock &&
      MODES.every(function (m) { return !!s.modes[m.id]; });
    if (!complete) s = defaults();
    if (upgraded) { state = s; save(); }
    if (!s.base) s.base = seedBase(s);
    if (!s.baseIntroduced) { s.baseIntroduced = true; s.style = 'base'; state = s; save(); }
    if (!s.modes[s.mode]) s.mode = 'shop';
    return s;
  }

  function save() {
    try { N.setState(JSON.stringify(state)); } catch (e) { /* layout stays for this session */ }
  }

  function eachTarget(fn) {
    MODES.forEach(function (m) {
      var mode = state.modes[m.id];
      fn(mode.hero.t);
      mode.hero.btns.forEach(function (b) { fn(b.t); });
      mode.tiles.forEach(function (t) { fn(t.t); });
    });
    state.dock.forEach(function (d) { if (d.t) fn(d.t); });
    if (state.clock && state.clock.t) fn(state.clock.t);
    if (state.base) {
      state.base.main.forEach(function (it) { if (it.t) fn(it.t); });
      Object.keys(state.base.pages).forEach(function (k) { state.base.pages[k].forEach(function (it) { if (it.t) fn(it.t); }); });
      state.base.dock.forEach(function (d) { if (d.t) fn(d.t); });
      if (state.base.photos && state.base.photos.t) fn(state.base.photos.t);
    }
  }

  function findApp(want) {
    var i, j, label;
    for (j = 0; j < want.length; j++) for (i = 0; i < apps.length; i++) if (apps[i].label.toLowerCase() === want[j]) return apps[i];
    for (j = 0; j < want.length; j++) for (i = 0; i < apps.length; i++) if (apps[i].label.toLowerCase().indexOf(want[j]) === 0) return apps[i];
    for (j = 0; j < want.length; j++) for (i = 0; i < apps.length; i++) {
      label = apps[i].label.toLowerCase();
      if (want[j].length >= 4 && label.indexOf(want[j]) >= 0) return apps[i];
    }
    return null;
  }

  /** Fills in any app tile that has no app yet by looking for its name among the installed apps. */
  function matchApps() {
    var changed = false;
    eachTarget(function (t) {
      if (!t || t.type !== 'app' || t.pkg) return;
      var a = findApp(t.want && t.want.length ? t.want : [String(t.label || '').toLowerCase()]);
      if (a) { t.pkg = a.pkg; t.act = a.act; if (t.adopt) t.label = a.label; changed = true; }
    });
    if (changed) save();
  }

  function loadApps() {
    try { apps = JSON.parse(N.getApps() || '[]'); } catch (e) { apps = []; }
    byPkg = {};
    apps.forEach(function (a) { if (!byPkg[a.pkg]) byPkg[a.pkg] = a; });
  }

  function installed(t) { return !!(t && t.type === 'app' && t.pkg && byPkg[t.pkg]); }
  function isEmpty(t) { return !t || (t.type === 'app' && !installed(t)); }
  function labelOf(holder) { return holder.name || (holder.t && holder.t.label) || 'Empty'; }

  function initials(text) {
    var words = String(text || '').trim().split(/\s+/).filter(Boolean);
    if (!words.length) return '+';
    if (words.length === 1) return words[0].slice(0, 2);
    return (words[0][0] + words[1][0]).toUpperCase();
  }

  function iconEl(holder) {
    var t = holder.t;
    if (installed(t)) {
      return h('img', { class: 'ico', alt: '', src: N.iconUrl ? N.iconUrl(t.pkg, t.act) : '/icon/' + encodeURIComponent(t.pkg) + '?a=' + encodeURIComponent(t.act || '') });
    }
    return h('span', { class: 'mono', 'aria-hidden': 'true', text: isEmpty(t) ? '+' : initials(labelOf(holder)) });
  }

  function toast(msg) {
    var el = $('toast');
    el.textContent = msg;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.hidden = true; }, 2600);
  }

  /** Tap and press-and-hold on one element, without the hold also counting as a tap. */
  function press(el, onTap, onLong) {
    var timer = null, fired = false, sx = 0, sy = 0;
    function cancel() { clearTimeout(timer); timer = null; }
    el.addEventListener('pointerdown', function (e) {
      fired = false; sx = e.clientX; sy = e.clientY;
      cancel();
      if (onLong) timer = setTimeout(function () { fired = true; timer = null; onLong(); }, 480);
    });
    el.addEventListener('pointermove', function (e) {
      if (Math.abs(e.clientX - sx) > 10 || Math.abs(e.clientY - sy) > 10) cancel();
    });
    el.addEventListener('pointerup', cancel);
    el.addEventListener('pointercancel', cancel);
    el.addEventListener('pointerleave', cancel);
    el.addEventListener('contextmenu', function (e) { e.preventDefault(); });
    el.addEventListener('click', function (e) {
      e.stopPropagation();
      if (fired) { fired = false; e.preventDefault(); return; }
      if (onTap) onTap();
    });
  }

  // ------------------------------------------------------------------ opening things

  function openTarget(holder, what) {
    var t = holder.t;
    if (!t || t.type === 'app') {
      if (!installed(t)) {
        pickApp('Pick the app for "' + labelOf(holder) + '"', function (a) {
          holder.t = { type: 'app', label: a.label, want: [a.label.toLowerCase()], pkg: a.pkg, act: a.act };
          save(); render();
        });
        return;
      }
      if (!N.launch(t.pkg, t.act || '')) toast("Couldn't open " + labelOf(holder));
      return;
    }
    if (t.type === 'inkflow') {
      if (!N.openUrl(account.url + t.path)) toast("Couldn't open InkFlow");
      return;
    }
    if (t.type === 'url') {
      if (!N.openUrl(t.url)) toast("Couldn't open that link");
    }
    void what;
  }

  // ------------------------------------------------------------------ home screen

  function greeting() {
    var hr = new Date().getHours();
    var word = hr < 12 ? 'Morning' : hr < 17 ? 'Afternoon' : 'Evening';
    var name = (state.name || account.name || '').trim().split(/\s+/)[0] || '';
    return name ? 'Good ' + word.toLowerCase() + ', ' + name : 'Good ' + word.toLowerCase();
  }

  var lastClock = '';
  function tick() {
    var now = new Date();
    var text = now.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
    if (text === lastClock) return;
    lastClock = text;
    var m = text.match(/^(.*?)\s*([AaPp]\.?\s?[Mm]\.?)$/);
    $('time').textContent = m ? m[1] : text;
    $('ampm').textContent = m ? m[2].replace(/[.\s]/g, '').toUpperCase() : '';
    $('greeting').textContent = greeting();
    $('dateline').textContent = now.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' });
    $('t-time').textContent = $('time').textContent;
    $('t-ampm').textContent = $('ampm').textContent;
    $('t-hline').textContent = $('dateline').textContent + ' \u00b7 ' + greeting();
    $('b-time').textContent = $('time').textContent;
    $('b-ampm').textContent = $('ampm').textContent;
    $('b-hline').textContent = $('dateline').textContent + ' \u00b7 ' + greeting();
  }

  function renderModes() {
    var nav = $('modes');
    nav.textContent = '';
    MODES.forEach(function (m) {
      var b = h('button', { type: 'button', 'aria-pressed': state.mode === m.id ? 'true' : 'false', text: m.label });
      b.addEventListener('click', function () { setMode(m.id); });
      nav.appendChild(b);
    });
    var on = nav.querySelector('[aria-pressed="true"]');
    if (on) {
      var left = on.offsetLeft - 20, right = on.offsetLeft + on.offsetWidth + 20 - nav.clientWidth;
      if (nav.scrollLeft > left) nav.scrollLeft = left;
      else if (nav.scrollLeft < right) nav.scrollLeft = right;
    }
  }

  function setMode(id) {
    if (state.mode === id) return;
    state.mode = id;
    save();
    render();
    $('home').scrollTop = 0;
    maybeRefreshLive(false);
  }

  function ordersLines() {
    if (!account.signedIn || live.signedOut) return { sub: 'Sign in to InkFlow to see today’s orders', sub2: '', rows: [], needSignIn: true };
    if (!live.data) return { sub: live.error || 'Checking today’s orders…', sub2: '', rows: [] };
    var c = live.data.counts || {};
    var sub = (c.dueToday || 0) + ' due today';
    return {
      sub: sub,
      lateText: c.late ? c.late + ' late' : '',
      sub2: (c.inProduction || 0) + ' in production · ' + (c.readyForPickup || 0) + ' ready for pickup' + (live.error ? ' · not updated' : ''),
      rows: (live.data.due || []).slice(0, 1)
    };
  }

  function renderHero(mode) {
    var hero = mode.hero;
    var el = h('section', { class: 'tile hero ' + hero.color });
    var txt = h('div', { class: 'txt' }, [h('div', { class: 'ttl', text: labelOf(hero) })]);
    var top = h('button', { type: 'button', class: 'hero-top' }, [txt, iconEl(hero)]);
    top.style.width = '100%';

    if (hero.live === 'orders') {
      var o = ordersLines();
      var line = h('div', { class: 'sub' }, [o.sub]);
      if (o.lateText) { line.appendChild(document.createTextNode(' · ')); line.appendChild(h('span', { class: hero.color === 'black' ? 'late' : '', text: o.lateText })); }
      txt.appendChild(line);
      if (o.sub2) txt.appendChild(h('div', { class: 'sub', text: o.sub2 }));
      press(top, function () { if (o.needSignIn) openSettings(); else openTarget(hero); }, function () { editHolder({ kind: 'hero', holder: hero }); });
      el.appendChild(top);
      if (o.rows.length) {
        var rows = h('div', { class: 'rows' });
        o.rows.forEach(function (r) {
          var b = h('button', { type: 'button', class: 'rowbtn' }, [h('b', { text: r.number + ' ' }), (r.customer || r.title || '') + ' — ' + r.label]);
          b.addEventListener('click', function (e) { e.stopPropagation(); if (!N.openUrl(account.url + r.path)) toast("Couldn't open InkFlow"); });
          rows.appendChild(b);
        });
        el.appendChild(rows);
      }
    } else {
      txt.appendChild(h('div', { class: 'sub', text: isEmpty(hero.t) ? 'Tap to pick the app' : (hero.sub || '') }));
      press(top, function () { openTarget(hero); }, function () { editHolder({ kind: 'hero', holder: hero }); });
      el.appendChild(top);
    }

    var btns = h('div', { class: 'hero-btns' });
    hero.btns.forEach(function (b, i) {
      var be = h('button', { type: 'button', class: i === 0 ? 'primary' : '', text: labelOf(b) });
      press(be, function () { openTarget(b); }, function () { editHolder({ kind: 'herobtn', holder: b }); });
      btns.appendChild(be);
    });
    el.appendChild(btns);
    return el;
  }

  function printerLines() {
    var box = h('div', { class: 'plines' });
    if (!account.signedIn || live.signedOut) { box.appendChild(h('div', { text: 'Sign in to InkFlow to see them live' })); return box; }
    if (!live.data) { box.appendChild(h('div', { text: live.error || 'Checking…' })); return box; }
    var list = live.data.printers || [];
    if (!list.length) { box.appendChild(h('div', { text: 'No printers reporting right now' })); return box; }
    list.slice(0, 4).forEach(function (p) {
      var status = !p.online ? 'offline' : p.busy ? 'printing' + (typeof p.pct === 'number' ? ' ' + Math.round(p.pct) + '%' : '') : p.paused ? 'paused' : p.lastOk === false ? 'last print failed' : 'idle';
      if (p.online && p.rollLow) status += ' · roll low';
      box.appendChild(h('div', null, [h('b', { text: p.name }), h('span', { text: status })]));
    });
    if (list.length > 4) box.appendChild(h('div', { text: '+' + (list.length - 4) + ' more' }));
    return box;
  }

  function renderTile(t, mode) {
    var empty = isEmpty(t.t);
    var el = h('button', { type: 'button', class: 'tile ' + t.size + ' ' + t.color + (empty ? ' empty' : '') + (t.live ? ' live' : '') });
    var name = labelOf(t);
    if (t.size === 'small') {
      el.appendChild(iconEl(t));
      el.appendChild(h('span', { class: 'lbl', text: name }));
    } else if (t.size === 'wide') {
      el.appendChild(iconEl(t));
      el.appendChild(h('span', { class: 'txt' }, [h('span', { class: 'ttl', text: name }), empty ? h('span', { class: 'sub', text: 'Tap to pick the app' }) : null]));
    } else {
      el.appendChild(h('span', { class: 'hero-top' }, [h('span', { class: 'txt' }, [h('span', { class: 'ttl', text: name })]), iconEl(t)]));
      if (t.live === 'printers') el.appendChild(printerLines());
      else if (empty) el.appendChild(h('span', { class: 'sub', text: 'Tap to pick the app' }));
    }
    press(el, function () { openTarget(t); }, function () { editHolder({ kind: 'tile', holder: t, list: mode.tiles }); });
    return el;
  }

  function renderDock() {
    var dock = $('dock');
    dock.textContent = '';
    state.dock.forEach(function (d) {
      var b = h('button', { type: 'button', 'aria-label': labelOf(d) });
      if (isEmpty(d.t)) b.appendChild(h('span', { class: 'emptyslot', 'aria-hidden': 'true', text: '+' }));
      else b.appendChild(iconEl(d));
      press(b, function () { openTarget(d); }, function () { editHolder({ kind: 'dock', holder: d }); });
      dock.appendChild(b);
    });
  }

  function isTicket() { return state.style === 'ticket'; }
  function isBase() { return state.style === 'base'; }
  function isDark() {
    var t = state.theme || 'phone';
    if (t === 'dark') return true;
    if (t === 'light') return false;
    try { return !!(N.isNightMode && N.isNightMode()); } catch (e) { return false; }
  }

  function render() {
    var mode = state.modes[state.mode];
    var ticket = isTicket();
    var base = isBase();
    document.body.classList.toggle('ticket', ticket);
    document.body.classList.toggle('base', base);
    document.documentElement.classList.toggle('base', base);
    var dark = base && isDark(), wall = base && !!state.wall;
    document.body.classList.toggle('dark', dark);
    document.documentElement.classList.toggle('dark', dark);
    document.body.classList.toggle('wall', wall);
    document.documentElement.classList.toggle('wall', wall);
    $('home').hidden = ticket || base;
    $('dock').hidden = ticket || base;
    $('ticket').hidden = !ticket;
    $('base').hidden = !base;
    if (!base) $('b-all').hidden = true;
    var bar = base ? (wall ? '#00000000' : dark ? '#0E1015' : '#EEF1F6') : ticket ? '#2440A8' : '#EEF1F6';
    var darkIcons = base ? (!dark && !wall) : !ticket;
    try { if (N.setBars) N.setBars(bar, darkIcons); } catch (e) { /* older app */ }
    lastClock = '';
    tick();
    if (base) { renderBase(); return; }
    if (ticket) { renderTicket(mode); return; }
    renderModes();
    var grid = $('grid');
    grid.textContent = '';
    grid.appendChild(renderHero(mode));
    mode.tiles.forEach(function (t) { grid.appendChild(renderTile(t, mode)); });
    renderDock();
  }

  // ------------------------------------------------------------------ Job Ticket look

  function printersText() {
    if (!account.signedIn || live.signedOut) return 'Sign in to InkFlow to see them live';
    if (!live.data) return live.error || 'Checking…';
    var list = live.data.printers || [];
    if (!list.length) return 'No printers reporting right now';
    return list.map(function (p) {
      var st = !p.online ? 'offline' : p.busy ? (typeof p.pct === 'number' ? Math.round(p.pct) + '%' : 'printing') : p.paused ? 'paused' : p.lastOk === false ? 'failed' : 'idle';
      return p.name.replace(/^EZ-JET PRO /i, 'EZ-JET ') + ' ' + st;
    }).join(' · ');
  }

  function ordersText() {
    if (!account.signedIn || live.signedOut) return 'Tap to sign in and see today’s orders';
    if (!live.data) return live.error || 'Checking today’s orders…';
    var r = (live.data.due || [])[0];
    return r ? 'Next: ' + r.number + ' ' + (r.customer || r.title || '') + ' — ' + r.label : 'Nothing due today';
  }

  function subFor(holder, isHero) {
    if (isEmpty(holder.t)) return { text: 'Tap to pick the app' };
    if (isHero && holder.live === 'orders') return { text: ordersText(), live: true };
    if (holder.live === 'printers') return { text: printersText(), live: true };
    if (isHero && holder.sub) return { text: holder.sub };
    var t = holder.t;
    if (t.type === 'inkflow') return { text: 'InkFlow' };
    if (t.type === 'url') return { text: String(t.url).replace(/^https?:\/\//i, '').split('/')[0] };
    return null;
  }

  function ticketRow(holder, sub, onTap, onLong) {
    var empty = isEmpty(holder.t);
    var txt = h('span', { class: 'txt' }, [h('span', { class: 'nm', text: labelOf(holder) })]);
    if (sub && sub.text) txt.appendChild(h('span', { class: 'sb' + (sub.live ? ' live' : '') }, [h('span', { text: sub.text })]));
    var chev = h('span', { 'aria-hidden': 'true' });
    chev.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6"></path></svg>';
    var b = h('button', { type: 'button', class: 't-row' + (empty ? ' empty' : '') }, [iconEl(holder), txt, chev.firstChild]);
    press(b, onTap, onLong);
    return b;
  }

  function renderTicket(mode) {
    var tabs = $('t-tabs');
    tabs.textContent = '';
    MODES.forEach(function (m) {
      var b = h('button', { type: 'button', 'aria-pressed': state.mode === m.id ? 'true' : 'false', text: m.label });
      b.addEventListener('click', function () { setMode(m.id); });
      tabs.appendChild(b);
    });
    var on = tabs.querySelector('[aria-pressed="true"]');
    if (on) {
      var left = on.offsetLeft - 8, right = on.offsetLeft + on.offsetWidth + 8 - tabs.clientWidth;
      if (tabs.scrollLeft > left) tabs.scrollLeft = left; else if (tabs.scrollLeft < right) tabs.scrollLeft = right;
    }

    var dock = $('t-dock');
    dock.textContent = '';
    state.dock.forEach(function (d) {
      var b = h('button', { type: 'button', 'aria-label': labelOf(d) });
      if (isEmpty(d.t)) b.appendChild(h('span', { class: 'emptyslot', 'aria-hidden': 'true', text: '+' }));
      else b.appendChild(iconEl(d));
      press(b, function () { openTarget(d); }, function () { editHolder({ kind: 'dock', holder: d }); });
      dock.appendChild(b);
    });

    var q = $('t-q').value.trim();
    var searching = q.length > 0 || ticketSearchOpen;
    $('ticket').classList.toggle('searching', searching);
    if (searching) { renderTicketSearch(q); return; }

    var info = TICKET[state.mode] || { no: '00', title: '' };
    var mlabel = MODES.filter(function (m) { return m.id === state.mode; })[0];
    $('t-no').textContent = 'Ticket ' + info.no + ' · ' + (mlabel ? mlabel.label : '');
    $('t-title').textContent = info.title;

    var banner = $('t-banner');
    banner.textContent = '';
    if (mode.hero.live === 'orders' && account.signedIn && !live.signedOut && live.data) {
      var c = live.data.counts || {};
      var l1 = h('span', null, [h('b', { text: (c.dueToday || 0) + ' due today' })]);
      if (c.late) { l1.appendChild(document.createTextNode(' · ')); l1.appendChild(h('span', { class: 'late', text: c.late + ' late' })); }
      var box = h('button', { type: 'button', class: 't-live' }, [
        h('span', { class: 'stamp', text: live.error ? 'Old' : 'Live' }),
        h('span', { class: 'lines' }, [l1, h('span', { text: (c.inProduction || 0) + ' in production · ' + (c.readyForPickup || 0) + ' ready for pickup' })])
      ]);
      box.addEventListener('click', function () { openTarget(mode.hero); });
      banner.appendChild(box);
    }

    var rows = $('t-rows');
    rows.textContent = '';
    rows.appendChild(ticketRow(mode.hero, subFor(mode.hero, true), function () {
      if (mode.hero.live === 'orders' && (!account.signedIn || live.signedOut)) openSettings(); else openTarget(mode.hero);
    }, function () { editHolder({ kind: 'hero', holder: mode.hero }); }));
    mode.tiles.forEach(function (t) {
      rows.appendChild(ticketRow(t, subFor(t, false), function () { openTarget(t); }, function () { editHolder({ kind: 'tile', holder: t, list: mode.tiles }); }));
    });

    var chips = $('t-chips');
    chips.textContent = '';
    mode.hero.btns.forEach(function (b, i) {
      var be = h('button', { type: 'button', class: i === 0 ? 'first' : '', text: (i === 0 && mode.hero.live === 'orders' ? '+ ' : '') + labelOf(b) });
      press(be, function () { openTarget(b); }, function () { editHolder({ kind: 'herobtn', holder: b }); });
      chips.appendChild(be);
    });
    var add = h('button', { type: 'button', text: '+ Add' });
    add.addEventListener('click', function () {
      pickApp('Pick the app to add to this ticket', function (a) {
        mode.tiles.push(tile('small', 'white', { type: 'app', label: a.label, want: [a.label.toLowerCase()], pkg: a.pkg, act: a.act }));
        save(); render();
      });
    });
    chips.appendChild(add);
  }

  var ticketSearchOpen = false;

  function renderTicketSearch(q) {
    var ql = q.toLowerCase();
    $('t-no').textContent = q ? 'Search · “' + q + '”' : 'All apps';
    $('t-title').textContent = q ? 'Results' : 'Every app, A to Z';
    $('t-banner').textContent = '';
    var rows = $('t-rows');
    rows.textContent = '';
    rows.scrollTop = 0;
    function done() { $('t-q').value = ''; ticketSearchOpen = false; $('t-q').blur(); }

    if (q) {
      var acts = PAGES.filter(function (p) { return p.label.toLowerCase().indexOf(ql) >= 0; });
      MODES.forEach(function (m) {
        if (m.label.toLowerCase().indexOf(ql) === 0 && m.id !== state.mode) acts.push({ label: 'Go to ' + m.label, mode: m.id });
      });
      if (acts.length) {
        rows.appendChild(h('div', { class: 't-group', text: 'Do something' }));
        acts.forEach(function (p) {
          var holder = { t: p.mode ? { type: 'mode', label: p.label } : pageT(p.label, p.path) };
          rows.appendChild(ticketRow(holder, { text: p.mode ? 'Switch tickets' : 'InkFlow' }, function () {
            done();
            if (p.mode) { setMode(p.mode); render(); return; }
            if (!N.openUrl(account.url + p.path)) toast("Couldn't open InkFlow");
            render();
          }, null));
        });
      }
    }
    var found = apps.filter(function (a) { return !ql || a.label.toLowerCase().indexOf(ql) >= 0; });
    if (q) rows.appendChild(h('div', { class: 't-group', text: 'Apps' }));
    if (!found.length) rows.appendChild(h('div', { class: 't-none', text: q ? 'No app matches “' + q + '”' : 'No apps found' }));
    found.slice(0, q ? 40 : 1000).forEach(function (a) {
      var holder = { t: { type: 'app', label: a.label, pkg: a.pkg, act: a.act } };
      rows.appendChild(ticketRow(holder, null, function () {
        if (N.launch(a.pkg, a.act)) { done(); render(); } else toast("Couldn't open " + a.label);
      }, function () { appMenu(a); }));
    });

    var chips = $('t-chips');
    chips.textContent = '';
    if (q) {
      var web = h('button', { type: 'button', class: 'first', text: 'Search the web' });
      web.addEventListener('click', function () { N.openUrl('https://www.google.com/search?q=' + encodeURIComponent(q)); done(); render(); });
      chips.appendChild(web);
    }
    var close = h('button', { type: 'button', text: 'Close' });
    close.addEventListener('click', function () { done(); render(); });
    chips.appendChild(close);
  }

  // ------------------------------------------------------------------ Home Base look

  var photoIds = [];
  var photoState = 'ask';
  var allFilter = 'all';

  function loadPhotos() {
    try { photoState = N.photoAccessState ? N.photoAccessState() : 'ask'; } catch (e) { photoState = 'ask'; }
    try { photoIds = (photoState === 'granted' || photoState === 'partial') ? JSON.parse(N.recentPhotos(24) || '[]') : []; } catch (e) { photoIds = []; }
  }

  function thumbUrl(id) { return N.thumbUrl ? N.thumbUrl(id) : '/thumb/' + encodeURIComponent(id); }

  function basePageLabel(id) {
    if (id === 'main') return 'Main';
    var p = BASE_PAGES.filter(function (x) { return x.id === id; })[0];
    return p ? p.label : id;
  }

  function baseList(id) { return id === 'main' ? state.base.main : state.base.pages[id]; }

  function setBasePage(id) {
    if (state.base.page === id) return;
    state.base.page = id;
    save();
    renderBase();
    maybeRefreshLive(false);
  }

  function renderStrip() {
    var strip = $('b-strip');
    strip.textContent = '';
    var cam = h('button', { type: 'button', class: 'cam', 'aria-label': 'Open the camera' });
    cam.innerHTML = '<svg aria-hidden="true" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 8h3l2-3h6l2 3h3v11H4z"></path><circle cx="12" cy="13" r="3.5"></circle></svg><span>CAMERA</span>';
    cam.addEventListener('click', function () {
      if (N.openCamera && N.openCamera()) return;
      openTarget({ t: appT('Camera') });
    });
    strip.appendChild(cam);
    if (photoState === 'ask' || photoState === 'blocked') {
      var ask = h('button', { type: 'button', class: 'ask', text: photoState === 'ask' ? 'Show my recent photos here' : 'Photos are off — tap to turn on in Settings' });
      ask.addEventListener('click', function () {
        if (photoState === 'ask') N.requestPhotoAccess(); else N.openOwnSettings();
      });
      strip.appendChild(ask);
      return;
    }
    if (!photoIds.length) {
      strip.appendChild(h('button', { type: 'button', class: 'ask', text: 'No photos yet' }));
      return;
    }
    photoIds.forEach(function (id, i) {
      var b = h('button', { type: 'button', 'aria-label': 'Photo ' + (i + 1) }, [h('img', { alt: '', loading: 'lazy', src: thumbUrl(id) })]);
      b.addEventListener('click', function () { if (!N.openPhoto(id)) toast("Couldn't open that photo"); });
      strip.appendChild(b);
    });
    if (photoState === 'partial') {
      var more = h('button', { type: 'button', class: 'ask', text: 'Show more photos' });
      more.addEventListener('click', function () { N.requestPhotoAccess(); });
      strip.appendChild(more);
    }
  }

  function renderBaseWidget() {
    var box = $('b-widget');
    box.textContent = '';
    var page = state.base.page;
    if (page !== 'main' && page !== 'shop') return;
    var holder = { t: pageT('InkFlow', '/dashboard') };
    var t2, t3;
    if (!account.signedIn || live.signedOut) { t2 = 'Tap to sign in and see today’s orders'; t3 = ''; }
    else if (!live.data) { t2 = live.error || 'Checking today’s orders…'; t3 = ''; }
    else {
      var c = live.data.counts || {};
      t2 = h('span', null, [(c.dueToday || 0) + ' due today' + (c.late ? ' · ' : '')]);
      if (c.late) t2.appendChild(h('span', { class: 'late', text: c.late + ' late' }));
      t2.appendChild(document.createTextNode(' · ' + (c.inProduction || 0) + ' in production'));
      t3 = printersText();
    }
    var txt = h('span', { class: 'txt' }, [h('span', { class: 't1', text: 'InkFlow' }), h('span', { class: 't2' }, [t2])]);
    if (t3) txt.appendChild(h('span', { class: 't2', text: t3 }));
    var go = h('span', { class: 'go', text: 'New order' });
    var card = h('button', { type: 'button', class: 'b-card' }, [iconEl(holder), txt, go]);
    card.addEventListener('click', function (e) {
      if (!account.signedIn || live.signedOut) { openSettings(); return; }
      if (e.target === go) openTarget({ t: pageT('New order', '/orders/new') });
      else openTarget(holder);
    });
    box.appendChild(card);
  }

  function folderIcon(id) {
    var list = state.base.pages[id] || [];
    var box = h('span', { class: 'b-fold f-' + id, 'aria-hidden': 'true' });
    list.slice(0, 4).forEach(function (it) { box.appendChild(iconEl(it)); });
    for (var i = list.length; i < 4; i++) box.appendChild(h('span', null));
    return box;
  }

  function baseItem(list, it, pageId, afterTap) {
    if (it.folder) {
      var name = it.name || basePageLabel(it.folder);
      var fb = h('button', { type: 'button', class: 'b-app' }, [folderIcon(it.folder), h('span', { class: 'lbl', text: name })]);
      press(fb, function () { if (afterTap) afterTap(); openFolder(it.folder); }, function () { baseItemMenu(list, it, pageId); });
      return fb;
    }
    var empty = isEmpty(it.t);
    var b = h('button', { type: 'button', class: 'b-app' + (empty ? ' empty' : '') }, [iconEl(it), h('span', { class: 'lbl', text: labelOf(it) })]);
    press(b, function () { if (afterTap) afterTap(); openTarget(it); }, function () { baseItemMenu(list, it, pageId); });
    return b;
  }

  function renderBase() {
    if (!state.base) { state.base = seedBase(state); save(); }
    renderStrip();
    renderBaseWidget();

    var tabs = $('b-tabs');
    tabs.textContent = '';
    ['main'].concat(BASE_PAGES.map(function (p) { return p.id; })).forEach(function (id) {
      var b = h('button', { type: 'button', 'aria-pressed': state.base.page === id ? 'true' : 'false', text: basePageLabel(id) });
      b.addEventListener('click', function () { setBasePage(id); });
      tabs.appendChild(b);
    });
    var on = tabs.querySelector('[aria-pressed="true"]');
    if (on) {
      var left = on.offsetLeft - 16, right = on.offsetLeft + on.offsetWidth + 16 - tabs.clientWidth;
      if (tabs.scrollLeft > left) tabs.scrollLeft = left; else if (tabs.scrollLeft < right) tabs.scrollLeft = right;
    }

    var grid = $('b-grid');
    grid.textContent = '';
    var pageId = state.base.page;
    var list = baseList(pageId) || [];
    var keep = grid.getAttribute('data-page') === pageId ? grid.scrollTop : 0;
    grid.setAttribute('data-page', pageId);
    list.forEach(function (it) { grid.appendChild(baseItem(list, it, pageId)); });
    if (list.length < 60) {
      var add = h('button', { type: 'button', class: 'b-app empty' }, [h('span', { class: 'mono', 'aria-hidden': 'true', text: '+' }), h('span', { class: 'lbl', text: 'Add app' })]);
      add.addEventListener('click', function () { addToBase(pageId); });
      grid.appendChild(add);
    }
    grid.scrollTop = keep;

    var dock = $('b-dock');
    dock.textContent = '';
    state.base.dock.forEach(function (d) {
      var b = h('button', { type: 'button', 'aria-label': labelOf(d) });
      if (isEmpty(d.t)) b.appendChild(h('span', { class: 'emptyslot', 'aria-hidden': 'true', text: '+' }));
      else b.appendChild(iconEl(d));
      press(b, function () { openTarget(d); }, function () { editHolder({ kind: 'clock', holder: d }); });
      dock.appendChild(b);
    });
    if (!$('b-all').hidden) renderAll();
  }

  function addToBase(pageId) {
    pickApp('Pick the app to add to ' + basePageLabel(pageId), function (a) {
      var list = baseList(pageId);
      list.push({ t: { type: 'app', label: a.label, want: [a.label.toLowerCase()], pkg: a.pkg, act: a.act } });
      save(); renderBase(); toast('Added to ' + basePageLabel(pageId));
    });
  }

  function openFolder(id) {
    var list = state.base.pages[id];
    openSheet(function (s) {
      s.appendChild(h('h2', { text: basePageLabel(id) }));
      var g = h('div', { class: 'b-sgrid' });
      g.style.display = 'grid';
      g.style.gridTemplateColumns = 'repeat(4, minmax(0, 1fr))';
      g.style.gap = '14px 6px';
      list.forEach(function (it) {
        var b = baseItem(list, it, id, closeSheet);
        g.appendChild(b);
      });
      if (!list.length) s.appendChild(h('p', { text: 'Nothing here yet.' }));
      s.appendChild(g);
      var row = h('div', { class: 'row' });
      row.appendChild(opt('+ Add apps', function () { addToBase(id); }, 'dark'));
      row.appendChild(opt('Open as page', function () { closeSheet(); setBasePage(id); }));
      s.appendChild(row);
    });
  }

  function baseItemMenu(list, it, pageId) {
    openSheet(function (s) {
      s.appendChild(h('h2', { text: it.folder ? (it.name || basePageLabel(it.folder)) : labelOf(it) }));
      if (it.folder) {
        s.appendChild(opt('Open as page', function () { closeSheet(); setBasePage(it.folder); }));
      } else {
        s.appendChild(opt('Choose an app', function () {
          pickApp('Pick the app for this spot', function (a) {
            it.t = { type: 'app', label: a.label, want: [a.label.toLowerCase()], pkg: a.pkg, act: a.act };
            delete it.name; save(); renderBase();
          });
        }));
        s.appendChild(opt('Open an InkFlow page', function () {
          openSheet(function (s2) {
            s2.appendChild(h('h2', { text: 'InkFlow page' }));
            PAGES.forEach(function (p) {
              s2.appendChild(opt(p.label, function () { it.t = pageT(p.label, p.path); delete it.name; save(); closeSheet(); renderBase(); }));
            });
          });
        }));
        s.appendChild(opt('Rename', function () {
          openSheet(function (s2) {
            var f = field('Name under the icon', labelOf(it));
            s2.appendChild(h('h2', { text: 'Rename' }));
            s2.appendChild(f.el);
            s2.appendChild(opt('Save', function () {
              var v = f.input.value.trim();
              if (v) it.name = v; else delete it.name;
              save(); closeSheet(); renderBase();
            }, 'dark'));
          });
        }));
        if (it.t && it.t.type === 'app' && it.t.pkg) s.appendChild(opt('App info', function () { closeSheet(); N.appInfo(it.t.pkg); }));
      }
      var move = h('div', { class: 'row' });
      move.appendChild(opt('Move earlier', function () {
        var at = list.indexOf(it);
        if (at > 0) { list.splice(at, 1); list.splice(at - 1, 0, it); save(); renderBase(); }
      }));
      move.appendChild(opt('Move later', function () {
        var at = list.indexOf(it);
        if (at >= 0 && at < list.length - 1) { list.splice(at, 1); list.splice(at + 1, 0, it); save(); renderBase(); }
      }));
      s.appendChild(move);
      var others = ['main'].concat(BASE_PAGES.map(function (p) { return p.id; })).filter(function (x) { return x !== pageId; });
      if (!it.folder) {
        s.appendChild(opt('Copy to another page', function () {
          openSheet(function (s2) {
            s2.appendChild(h('h2', { text: 'Copy to…' }));
            others.forEach(function (pid) {
              s2.appendChild(opt(basePageLabel(pid), function () {
                baseList(pid).push(clone(it)); save(); closeSheet(); renderBase(); toast('Copied to ' + basePageLabel(pid));
              }));
            });
          });
        }));
      }
      s.appendChild(opt(it.folder ? 'Remove folder from Main' : 'Remove from ' + basePageLabel(pageId), function () {
        var at = list.indexOf(it);
        if (at >= 0) list.splice(at, 1);
        save(); closeSheet(); renderBase();
      }, 'danger'));
    });
  }

  // ---- all apps: search, category chips, A to Z strip

  function openAll(focus) {
    $('b-all').hidden = false;
    $('b-q').value = '';
    allFilter = 'all';
    renderAll();
    $('b-list').scrollTop = 0;
    if (focus) setTimeout(function () { $('b-q').focus(); }, 60);
  }

  function closeAll() {
    $('b-all').hidden = true;
    $('b-q').blur();
  }

  function appButton(a) {
    var holder = { t: { type: 'app', label: a.label, pkg: a.pkg, act: a.act } };
    var b = h('button', { type: 'button', class: 'b-app' }, [iconEl(holder), h('span', { class: 'lbl', text: a.label })]);
    press(b, function () { if (N.launch(a.pkg, a.act)) closeAll(); else toast("Couldn't open " + a.label); }, function () { baseAppMenu(a); });
    return b;
  }

  function baseAppMenu(a) {
    openSheet(function (s) {
      s.appendChild(h('h2', { text: a.label }));
      ['main'].concat(BASE_PAGES.map(function (p) { return p.id; })).forEach(function (pid) {
        s.appendChild(opt('Add to ' + basePageLabel(pid), function () {
          baseList(pid).push({ t: { type: 'app', label: a.label, want: [a.label.toLowerCase()], pkg: a.pkg, act: a.act } });
          save(); closeSheet(); renderBase(); toast('Added to ' + basePageLabel(pid));
        }));
      });
      s.appendChild(opt('App info', function () { closeSheet(); N.appInfo(a.pkg); }));
    });
  }

  function renderAll() {
    var chips = $('b-chips');
    chips.textContent = '';
    [{ id: 'all', label: 'All' }].concat(BASE_PAGES).forEach(function (c) {
      var b = h('button', { type: 'button', 'aria-pressed': allFilter === c.id ? 'true' : 'false', text: c.label });
      b.addEventListener('click', function () { allFilter = c.id; renderAll(); $('b-list').scrollTop = 0; });
      chips.appendChild(b);
    });

    var q = $('b-q').value.trim().toLowerCase();
    var listEl = $('b-list');
    listEl.textContent = '';
    var rail = $('b-rail');
    rail.textContent = '';
    rail.hidden = !!q || allFilter !== 'all';

    if (q) {
      var acts = [];
      if ('camera'.indexOf(q) === 0 || 'photo'.indexOf(q) === 0 || q.indexOf('pic') === 0) {
        acts.push({ label: 'Take a photo', note: 'Camera', run: function () { if (!(N.openCamera && N.openCamera())) openTarget({ t: appT('Camera') }); } });
        acts.push({ label: 'Open your photos', note: 'Photos', run: function () { openTarget(state.base.photos); } });
      }
      PAGES.filter(function (p) { return p.label.toLowerCase().indexOf(q) >= 0; }).forEach(function (p) {
        acts.push({ label: p.label, note: 'InkFlow', run: function () { if (!N.openUrl(account.url + p.path)) toast("Couldn't open InkFlow"); } });
      });
      ['main'].concat(BASE_PAGES.map(function (p) { return p.id; })).forEach(function (pid) {
        if (basePageLabel(pid).toLowerCase().indexOf(q) === 0) acts.push({ label: 'Go to ' + basePageLabel(pid), note: 'Page', run: function () { setBasePage(pid); } });
      });
      if (acts.length) {
        var sec = h('div', { class: 'b-sec' }, [h('h3', { text: 'Do something' })]);
        acts.forEach(function (a) {
          var b = h('button', { type: 'button', class: 'b-act' }, [a.label, h('small', { text: a.note })]);
          b.addEventListener('click', function () { closeAll(); a.run(); });
          sec.appendChild(b);
        });
        listEl.appendChild(sec);
      }
      var found = apps.filter(function (a) { return a.label.toLowerCase().indexOf(q) >= 0; });
      found.sort(function (x, y) { return (x.label.toLowerCase().indexOf(q) === 0 ? 0 : 1) - (y.label.toLowerCase().indexOf(q) === 0 ? 0 : 1); });
      var g = h('div', { class: 'b-sgrid' });
      found.slice(0, 40).forEach(function (a) { g.appendChild(appButton(a)); });
      listEl.appendChild(h('div', { class: 'b-sec' }, [h('h3', { text: 'Apps' }), found.length ? g : h('div', { class: 'b-none', text: 'No app matches “' + q + '”' })]));
      var web = h('button', { type: 'button', class: 'b-act' }, ['Search the web for “' + q + '”', h('small', { text: 'Chrome' })]);
      web.addEventListener('click', function () { closeAll(); N.openUrl('https://www.google.com/search?q=' + encodeURIComponent(q)); });
      listEl.appendChild(web);
      return;
    }

    if (allFilter !== 'all') {
      var plist = state.base.pages[allFilter] || [];
      var pg = h('div', { class: 'b-sgrid' });
      plist.forEach(function (it) {
        var b = baseItem(plist, it, allFilter, closeAll);
        pg.appendChild(b);
      });
      listEl.appendChild(h('div', { class: 'b-sec' }, [h('h3', { text: basePageLabel(allFilter) + ' — hold an app to change it' }), plist.length ? pg : h('div', { class: 'b-none', text: 'Nothing here yet.' })]));
      return;
    }

    var groups = {};
    apps.forEach(function (a) {
      var c = (a.label.trim()[0] || '#').toUpperCase();
      if (!/[A-Z]/.test(c)) c = '#';
      (groups[c] = groups[c] || []).push(a);
    });
    var letters = '#ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');
    letters.forEach(function (c) {
      if (!groups[c]) return;
      var g = h('div', { class: 'b-sgrid' });
      groups[c].forEach(function (a) { g.appendChild(appButton(a)); });
      listEl.appendChild(h('div', { class: 'b-sec', id: 'b-sec-' + (c === '#' ? 'num' : c) }, [h('h3', { text: c }), g]));
    });
    if (!apps.length) listEl.appendChild(h('div', { class: 'b-none', text: 'No apps found' }));
    letters.forEach(function (c) { rail.appendChild(h('span', { class: groups[c] ? '' : 'off', text: c, 'data-l': c })); });
  }

  function wireBase() {
    loadPhotos();
    var base = $('base'), sx = 0, sy = 0, at = 0, fromStrip = false, gridMore = false;
    base.addEventListener('touchstart', function (e) {
      var t = e.touches[0]; sx = t.clientX; sy = t.clientY; at = Date.now();
      fromStrip = !!(e.target.closest && (e.target.closest('#b-strip') || e.target.closest('#b-tabs')));
      var g = $('b-grid');
      gridMore = !!(e.target.closest && e.target.closest('#b-grid')) && g.scrollTop + g.clientHeight < g.scrollHeight - 2;
    }, { passive: true });
    base.addEventListener('touchend', function (e) {
      var t = e.changedTouches[0], dx = t.clientX - sx, dy = t.clientY - sy;
      if (Date.now() - at > 600 || sheetOpen || fromStrip) return;
      if (dy < -70 && Math.abs(dx) < 60 && !gridMore) { openAll(false); return; }
      if (Math.abs(dx) > 80 && Math.abs(dy) < 50) {
        var ids = ['main'].concat(BASE_PAGES.map(function (p) { return p.id; }));
        var i = ids.indexOf(state.base.page);
        var next = ids[i + (dx < 0 ? 1 : -1)];
        if (next) setBasePage(next);
      }
    }, { passive: true });
    var all = $('b-all'), ay = 0, atTop = false;
    all.addEventListener('touchstart', function (e) { ay = e.touches[0].clientY; atTop = $('b-list').scrollTop <= 0 && !(e.target.closest && e.target.closest('#b-rail')); }, { passive: true });
    all.addEventListener('touchend', function (e) { if (atTop && e.changedTouches[0].clientY - ay > 90 && !sheetOpen) closeAll(); }, { passive: true });

    function railJump(y) {
      var rail = $('b-rail');
      var spans = rail.querySelectorAll('span');
      if (!spans.length) return;
      var r = rail.getBoundingClientRect();
      var i = Math.max(0, Math.min(spans.length - 1, Math.floor((y - r.top) / (r.height / spans.length))));
      for (var k = i; k < spans.length; k++) {
        var l = spans[k].getAttribute('data-l');
        var sec = $('b-sec-' + (l === '#' ? 'num' : l));
        if (sec) {
          $('b-list').scrollTop = sec.offsetTop - $('b-list').offsetTop;
          Array.prototype.forEach.call(spans, function (s) { s.classList.remove('on'); });
          spans[k].classList.add('on');
          return;
        }
      }
    }
    var rail = $('b-rail');
    rail.addEventListener('touchstart', function (e) { railJump(e.touches[0].clientY); }, { passive: true });
    rail.addEventListener('touchmove', function (e) { railJump(e.touches[0].clientY); }, { passive: true });
    rail.addEventListener('click', function (e) { railJump(e.clientY); });

    $('b-searchbtn').addEventListener('click', function () { openAll(true); });
    $('b-q').addEventListener('input', renderAll);
    $('b-gear').addEventListener('click', function () { openSettings(); });
    $('b-photosapp').addEventListener('click', function () { openTarget(state.base.photos); });
    press($('b-clock'), function () { openTarget(state.clock); }, function () { editHolder({ kind: 'clock', holder: state.clock }); });

    // the first time Home Base shows, ask once for the photos (Android shows its own question)
    if (isBase() && photoState === 'ask' && !state.photosAskedOnce && N.requestPhotoAccess) {
      state.photosAskedOnce = true; save();
      setTimeout(function () { N.requestPhotoAccess(); }, 800);
    }
  }

  // ------------------------------------------------------------------ live numbers from InkFlow

  function loadAccount() {
    try { account = JSON.parse(N.inkflowAccount()); } catch (e) { /* keep the last one */ }
  }

  function maybeRefreshLive(force) {
    var wanted = isBase() ? (state.base.page === 'main' || state.base.page === 'shop') : state.mode === 'shop';
    if (!wanted || !account.signedIn || live.loading) return;
    if (!force && Date.now() - live.at < 45000) return;
    live.loading = true;
    N.inkflowRefresh();
  }

  // ------------------------------------------------------------------ all apps

  function drawerIsOpen() { return !$('drawer').hidden; }

  function openDrawer() {
    $('drawer').hidden = false;
    $('search').value = '';
    $('pickhint').hidden = !pickCb;
    renderAppList();
    $('applist').scrollTop = 0;
  }

  function closeDrawer() {
    $('drawer').hidden = true;
    $('search').blur();
    pickCb = null;
  }

  function pickApp(hint, cb) {
    closeSheet();
    pickCb = cb;
    $('pickhint').textContent = hint;
    openDrawer();
  }

  function renderAppList() {
    var q = $('search').value.trim().toLowerCase();
    var list = $('applist');
    list.textContent = '';
    var shown = apps.filter(function (a) { return !q || a.label.toLowerCase().indexOf(q) >= 0; });
    if (!shown.length) { list.appendChild(h('div', { class: 'none', text: q ? 'No app matches “' + q + '”' : 'No apps found' })); return; }
    shown.forEach(function (a) {
      var holder = { t: { type: 'app', label: a.label, pkg: a.pkg, act: a.act } };
      var b = h('button', { type: 'button' }, [iconEl(holder), h('span', { class: 'lbl', text: a.label })]);
      press(b, function () {
        if (pickCb) { var cb = pickCb; closeDrawer(); cb(a); return; }
        if (N.launch(a.pkg, a.act)) closeDrawer(); else toast("Couldn't open " + a.label);
      }, pickCb ? null : function () { appMenu(a); });
      list.appendChild(b);
    });
  }

  function appMenu(a) {
    openSheet(function (s) {
      s.appendChild(h('h2', { text: a.label }));
      MODES.forEach(function (m) {
        s.appendChild(opt('Add to ' + m.label, function () {
          state.modes[m.id].tiles.push(tile('small', 'white', { type: 'app', label: a.label, want: [a.label.toLowerCase()], pkg: a.pkg, act: a.act }));
          save(); closeSheet(); render(); toast('Added to ' + m.label);
        }));
      });
      s.appendChild(opt('App info', function () { closeSheet(); N.appInfo(a.pkg); }));
    });
  }

  // ------------------------------------------------------------------ sheets (edit, settings)

  function openSheet(build) {
    var s = $('sheet');
    s.textContent = '';
    build(s);
    s.hidden = false;
    $('scrim').hidden = false;
    sheetOpen = true;
    s.scrollTop = 0;
  }

  function closeSheet() {
    if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
    $('sheet').hidden = true;
    $('scrim').hidden = true;
    sheetOpen = false;
  }

  function opt(text, fn, cls) {
    var b = h('button', { type: 'button', class: 'opt' + (cls ? ' ' + cls : ''), text: text });
    b.addEventListener('click', fn);
    return b;
  }

  function field(labelText, value, type, placeholder) {
    var input = h('input', { type: type || 'text', value: value || '', placeholder: placeholder || null, autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false' });
    return { el: h('label', null, [labelText, input]), input: input };
  }

  function editHolder(ctx) {
    var holder = ctx.holder;
    openSheet(function (s) {
      s.appendChild(h('h2', { text: labelOf(holder) }));
      s.appendChild(opt('Choose an app', function () {
        pickApp('Pick the app for this spot', function (a) {
          holder.t = { type: 'app', label: a.label, want: [a.label.toLowerCase()], pkg: a.pkg, act: a.act };
          delete holder.name; delete holder.live;
          save(); render();
        });
      }));
      s.appendChild(opt('Open an InkFlow page', function () {
        openSheet(function (s2) {
          s2.appendChild(h('h2', { text: 'InkFlow page' }));
          PAGES.forEach(function (p) {
            s2.appendChild(opt(p.label, function () {
              holder.t = pageT(p.label, p.path);
              delete holder.name;
              if (ctx.kind === 'tile') { if (p.path === '/printers' && holder.size === 'big') holder.live = 'printers'; else delete holder.live; }
              save(); closeSheet(); render();
            }));
          });
        });
      }));
      s.appendChild(opt('Open a web link', function () {
        openSheet(function (s2) {
          var cur = holder.t && holder.t.type === 'url' ? holder.t : null;
          var name = field('Name', cur ? cur.label : '', 'text', 'Shop start page');
          var url = field('Web address', cur ? cur.url : 'https://', 'url');
          var err = h('p', { class: 'err' });
          s2.appendChild(h('h2', { text: 'Web link' }));
          s2.appendChild(name.el); s2.appendChild(url.el); s2.appendChild(err);
          s2.appendChild(opt('Save', function () {
            var u = url.input.value.trim();
            if (!/^https?:\/\/[^\s.]+\.?[^\s]*$/i.test(u) || u.length < 10) { err.textContent = 'Type the full address, starting with https://'; return; }
            holder.t = linkT(name.input.value.trim() || u.replace(/^https?:\/\//i, '').split('/')[0], u);
            delete holder.name; delete holder.live;
            save(); closeSheet(); render();
          }, 'dark'));
        });
      }));
      s.appendChild(opt('Rename', function () {
        openSheet(function (s2) {
          var name = field('Name shown on the tile', labelOf(holder));
          s2.appendChild(h('h2', { text: 'Rename' }));
          s2.appendChild(name.el);
          s2.appendChild(opt('Save', function () {
            var v = name.input.value.trim();
            if (v) holder.name = v; else delete holder.name;
            save(); closeSheet(); render();
          }, 'dark'));
        });
      }));

      if (ctx.kind === 'tile' || ctx.kind === 'hero') {
        var row = h('div', { class: 'row' });
        COLORS.forEach(function (c) {
          var sw = h('button', { type: 'button', class: 'sw', 'aria-label': c + ' tile', 'aria-pressed': holder.color === c ? 'true' : 'false' });
          sw.style.background = c === 'white' ? '#FFFFFF' : c === 'black' ? '#14161C' : 'var(--' + c + ')';
          sw.addEventListener('click', function () { holder.color = c; save(); render(); editHolder(ctx); });
          row.appendChild(sw);
        });
        s.appendChild(row);
      }

      if (ctx.kind === 'tile') {
        var sizes = h('div', { class: 'row' });
        SIZES.forEach(function (z) {
          var b = opt(z.label, function () {
            holder.size = z.id;
            if (z.id !== 'big') delete holder.live;
            else if (holder.t && holder.t.type === 'inkflow' && holder.t.path === '/printers') holder.live = 'printers';
            save(); render(); editHolder(ctx);
          });
          b.setAttribute('aria-pressed', holder.size === z.id ? 'true' : 'false');
          sizes.appendChild(b);
        });
        s.appendChild(sizes);
        var i = ctx.list.indexOf(holder);
        var move = h('div', { class: 'row' });
        move.appendChild(opt('Move earlier', function () {
          var at = ctx.list.indexOf(holder);
          if (at > 0) { ctx.list.splice(at, 1); ctx.list.splice(at - 1, 0, holder); save(); render(); }
        }));
        move.appendChild(opt('Move later', function () {
          var at = ctx.list.indexOf(holder);
          if (at >= 0 && at < ctx.list.length - 1) { ctx.list.splice(at, 1); ctx.list.splice(at + 1, 0, holder); save(); render(); }
        }));
        s.appendChild(move);
        s.appendChild(opt('Remove this tile', function () {
          var at = ctx.list.indexOf(holder);
          if (at >= 0) ctx.list.splice(at, 1);
          save(); closeSheet(); render();
        }, 'danger'));
        void i;
      }

      if (ctx.kind === 'clock') {
        // the clock only needs its app
      } else if (ctx.kind === 'dock') {
        s.appendChild(opt('Empty this spot', function () { holder.t = null; delete holder.name; save(); closeSheet(); render(); }, 'danger'));
      } else {
        s.appendChild(opt('Add a tile to this mode', function () {
          pickApp('Pick the app for the new tile', function (a) {
            state.modes[state.mode].tiles.push(tile('small', 'white', { type: 'app', label: a.label, want: [a.label.toLowerCase()], pkg: a.pkg, act: a.act }));
            save(); render();
          });
        }));
      }
    });
  }

  function openSettings(message) {
    loadAccount();
    openSheet(function (s) {
      s.appendChild(h('h2', { text: 'Settings' }));

      var name = field('Your first name (for the greeting)', state.name || '', 'text', account.name || '');
      name.input.addEventListener('change', function () { state.name = name.input.value.trim(); save(); render(); });
      s.appendChild(name.el);

      s.appendChild(h('h2', { text: 'InkFlow' }));
      if (account.signedIn) {
        s.appendChild(h('p', { class: 'ok', text: 'Signed in as ' + (account.email || 'your InkFlow login') }));
        s.appendChild(h('p', { text: 'The Shop tiles show today’s orders and your printers. Tap a tile to open that page in InkFlow.' }));
        s.appendChild(opt('Sign out of InkFlow', function () {
          N.inkflowSignOut(); loadAccount(); live.data = null; live.at = 0; live.signedOut = false;
          render(); openSettings();
        }));
      } else {
        var url = field('InkFlow address', account.url || 'https://inkflow.up.railway.app', 'url');
        var email = field('InkFlow login email', account.email || '', 'email');
        var pw = field('Password', '', 'password');
        var status = h('p', { class: 'err', text: message || '' });
        s.appendChild(h('p', { text: 'Sign in with a full-access InkFlow login to see today’s orders and live printer status. The password is used once to sign in and is not kept on the phone.' }));
        s.appendChild(url.el); s.appendChild(email.el); s.appendChild(pw.el); s.appendChild(status);
        var go = opt('Sign in', function () {
          if (!email.input.value.trim() || !pw.input.value) { status.textContent = 'Type your InkFlow email and password.'; return; }
          status.className = ''; status.textContent = 'Signing in…';
          go.disabled = true;
          signInDone = function (r) {
            go.disabled = false;
            if (r && r.ok) { loadAccount(); live.signedOut = false; live.at = 0; render(); maybeRefreshLive(true); openSettings(); toast('Signed in to InkFlow'); }
            else { status.className = 'err'; status.textContent = (r && r.error) || 'Sign-in failed.'; }
          };
          N.inkflowSignIn(url.input.value.trim(), email.input.value.trim(), pw.input.value);
        }, 'dark');
        s.appendChild(go);
      }

      s.appendChild(h('h2', { text: 'Look' }));
      var looks = h('div', { class: 'row' });
      [{ id: 'base', label: 'Home Base' }, { id: 'tiles', label: 'Press Sheet' }, { id: 'ticket', label: 'Job Ticket' }].forEach(function (lk) {
        var b = opt(lk.label, function () {
          state.style = lk.id; save(); closeSheet(); closeDrawer(); render();
          toast((lk.id === 'ticket' ? 'Job Ticket' : lk.id === 'base' ? 'Home Base' : 'Press Sheet') + ' look on');
        });
        b.setAttribute('aria-pressed', (state.style || 'tiles') === lk.id ? 'true' : 'false');
        looks.appendChild(b);
      });
      s.appendChild(looks);
      s.appendChild(h('p', { text: 'Press Sheet and Job Ticket share the same modes. Home Base keeps its own pages and folders. All three share the clock and your InkFlow sign-in.' }));

      s.appendChild(h('h2', { text: 'Home Base colors' }));
      var themes = h('div', { class: 'row' });
      [{ id: 'light', label: 'Light' }, { id: 'dark', label: 'Dark' }, { id: 'phone', label: 'Same as phone' }].forEach(function (th) {
        var b = opt(th.label, function () { state.theme = th.id; save(); render(); openSettings(); });
        b.setAttribute('aria-pressed', (state.theme || 'phone') === th.id ? 'true' : 'false');
        themes.appendChild(b);
      });
      s.appendChild(themes);
      var walls = h('div', { class: 'row' });
      [{ on: false, label: 'Plain background' }, { on: true, label: 'My wallpaper' }].forEach(function (wv) {
        var b = opt(wv.label, function () { state.wall = wv.on; save(); render(); openSettings(); });
        b.setAttribute('aria-pressed', !!state.wall === wv.on ? 'true' : 'false');
        walls.appendChild(b);
      });
      s.appendChild(walls);
      s.appendChild(h('p', { text: '\u201cSame as phone\u201d follows your phone\u2019s dark mode setting. \u201cMy wallpaper\u201d shows your phone\u2019s wallpaper behind the apps.' }));

      s.appendChild(h('h2', { text: 'Home screen' }));
      var isHome = false;
      try { isHome = !!N.isDefaultHome(); } catch (e) { isHome = false; }
      s.appendChild(h('p', { text: isHome ? 'Press Sheet is your home screen. To go back to the old one, pick it in Android’s Default home app list.' : 'Press Sheet isn’t your home screen yet. Pick it in Android’s Default home app list.' }));
      s.appendChild(opt('Open Default home app', function () { N.homeSettings(); }));
      s.appendChild(opt('Start the layout over', function () {
        openSheet(function (s2) {
          s2.appendChild(h('h2', { text: 'Start the layout over?' }));
          s2.appendChild(h('p', { text: 'All three modes and the dock go back to how they were when you installed Press Sheet. Your InkFlow sign-in stays.' }));
          s2.appendChild(opt('Yes, start over', function () {
            var keepName = state.name, keepStyle = state.style;
            state = defaults(); state.name = keepName; state.style = keepStyle; state.base = seedBase(state); state.baseIntroduced = true;
            matchApps(); save(); closeSheet(); render();
          }, 'danger'));
          s2.appendChild(opt('Keep my layout', function () { closeSheet(); }));
        });
      }, 'danger'));
    });
  }
  var signInDone = null;

  // ------------------------------------------------------------------ gestures and phone events

  function closeTop() {
    if (sheetOpen) { closeSheet(); return true; }
    if (drawerIsOpen()) { closeDrawer(); return true; }
    if (isBase() && !$('b-all').hidden) { closeAll(); return true; }
    if (isTicket() && (ticketSearchOpen || $('t-q').value)) { $('t-q').value = ''; ticketSearchOpen = false; $('t-q').blur(); render(); return true; }
    return false;
  }

  function wireGestures() {
    var sx = 0, sy = 0, at = 0;
    var home = $('home');
    function start(e) {
      var t = e.touches[0]; sx = t.clientX; sy = t.clientY;
      at = e.target.closest && e.target.closest('#modes') ? 0 : Date.now();
    }
    home.addEventListener('touchstart', start, { passive: true });
    $('dock').addEventListener('touchstart', start, { passive: true });
    function end(e) {
      var t = e.changedTouches[0];
      var dx = t.clientX - sx, dy = t.clientY - sy;
      if (Date.now() - at > 600 || sheetOpen) return;
      var atBottom = home.scrollTop + home.clientHeight >= home.scrollHeight - 2;
      if (dy < -70 && Math.abs(dx) < 60 && atBottom) { openDrawer(); return; }
      if (Math.abs(dx) > 80 && Math.abs(dy) < 50) {
        var i = MODES.map(function (m) { return m.id; }).indexOf(state.mode);
        var next = MODES[i + (dx < 0 ? 1 : -1)];
        if (next) setMode(next.id);
      }
    }
    home.addEventListener('touchend', end, { passive: true });
    $('dock').addEventListener('touchend', end, { passive: true });

    var drawer = $('drawer'), dy0 = 0, top0 = false;
    drawer.addEventListener('touchstart', function (e) { dy0 = e.touches[0].clientY; top0 = $('applist').scrollTop <= 0; }, { passive: true });
    drawer.addEventListener('touchend', function (e) {
      if (top0 && e.changedTouches[0].clientY - dy0 > 90 && !sheetOpen) closeDrawer();
    }, { passive: true });

    $('handle').addEventListener('click', openDrawer);
    var paper = $('t-paper'), tx = 0, ty = 0, tat = 0;
    paper.addEventListener('touchstart', function (e) { var t = e.touches[0]; tx = t.clientX; ty = t.clientY; tat = Date.now(); }, { passive: true });
    paper.addEventListener('touchend', function (e) {
      var t = e.changedTouches[0], dx = t.clientX - tx, dy = t.clientY - ty;
      if (Date.now() - tat > 600 || sheetOpen || ticketSearchOpen || $('t-q').value) return;
      if (e.target.closest && e.target.closest('#t-chips')) return;
      if (Math.abs(dx) > 80 && Math.abs(dy) < 50) {
        var i = MODES.map(function (m) { return m.id; }).indexOf(state.mode);
        var next = MODES[i + (dx < 0 ? 1 : -1)];
        if (next) setMode(next.id);
      }
    }, { passive: true });
    $('t-q').addEventListener('focus', function () { if (!ticketSearchOpen) { ticketSearchOpen = true; render(); } });
    $('t-q').addEventListener('input', function () { render(); });
    $('t-gear').addEventListener('click', function () { openSettings(); });
    press($('t-clock'), function () { openTarget(state.clock); }, function () { editHolder({ kind: 'clock', holder: state.clock }); });
    press($('clock'), function () { openTarget(state.clock); }, function () { editHolder({ kind: 'clock', holder: state.clock }); });
    $('gear').addEventListener('click', function () { openSettings(); });
    $('search').addEventListener('input', renderAppList);
    $('scrim').addEventListener('click', closeSheet);
    document.addEventListener('contextmenu', function (e) { e.preventDefault(); });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeTop(); });
  }

  window.onNative = function (name, data) {
    if (name === 'back') { closeTop(); return; }
    if (name === 'theme') { if (isBase()) render(); return; }
    if (name === 'photos') { loadPhotos(); if (isBase()) renderStrip(); return; }
    if (name === 'home') { closeSheet(); closeDrawer(); $('home').scrollTop = 0; if (isBase()) { closeAll(); if (state.base.page !== 'main') setBasePage('main'); } if (isTicket()) { $('t-q').value = ''; ticketSearchOpen = false; $('t-q').blur(); render(); $('t-rows').scrollTop = 0; } return; }
    if (name === 'apps') { loadApps(); matchApps(); render(); if (drawerIsOpen()) renderAppList(); return; }
    if (name === 'resume') { loadAccount(); loadPhotos(); lastClock = ''; render(); maybeRefreshLive(false); return; }
    if (name === 'signin') { if (signInDone) { var fn = signInDone; signInDone = null; fn(data); } return; }
    if (name === 'live') {
      live.loading = false;
      live.at = Date.now();
      if (data && data.ok) { live.data = data.data; live.error = ''; live.signedOut = false; }
      else {
        live.error = (data && data.error) || 'Couldn’t reach InkFlow';
        if (data && data.signedOut) { live.signedOut = true; live.data = null; loadAccount(); }
      }
      if (!sheetOpen && !drawerIsOpen()) { if (isBase()) renderBaseWidget(); else render(); }
    }
  };

  // ------------------------------------------------------------------ start

  loadApps();
  loadAccount();
  state = loadState();
  matchApps();
  wireGestures();
  wireBase();
  render();
  maybeRefreshLive(true);
  setInterval(function () {
    if (document.visibilityState !== 'visible') return;
    maybeRefreshLive(false);
  }, 30000);
  setInterval(function () { if (document.visibilityState === 'visible') tick(); }, 1000);

  // ------------------------------------------------------------------ stand-in for testing in a browser

  function mockBridge() {
    var wall = document.createElement('style');
    wall.textContent = 'html.base{background:repeating-linear-gradient(135deg,#7C8798 0,#7C8798 22px,#848FA0 22px,#848FA0 44px)!important}';
    document.head.appendChild(wall);
    var names = ['Calculator', 'Calendar', 'Camera', 'Canva', 'Chrome', 'Claude', 'Clock', 'Depop', 'eBay', 'Family Hub', 'FretQuest', 'Immich',
      'InkStop Mail', 'Jellyfin', 'JuniCare', 'Maps', 'Mercari', 'Messages', 'Nextcloud', 'PayPal POS', 'Phone', 'Photos', 'Poshmark',
      'Starbucks', 'The Roku App', 'Whatnot', 'YouTube', 'YT Music', 'Disney Solitaire', 'High Roller Video Poker', 'Tasty Travels: Merge Game',
      'Coin Master', 'DeckTok', 'Nintendo Switch App', 'Play Store', 'PayPal', 'MoneyLion', 'Progressive', 'Aetna Health', 'CVS Flex Benefits OTC', 'AtoB', 'Murphy USA', 'Microsoft Rewards'];
    var hues = [200, 330, 48, 150, 265, 20];
    var signedIn = /signedin/.test(location.search);
    return {
      getApps: function () { return JSON.stringify(names.map(function (n) { return { label: n, pkg: 'mock.' + n.toLowerCase().replace(/\W+/g, ''), act: 'Main' }; })); },
      iconUrl: function (pkg) {
        var n = names.filter(function (x) { return 'mock.' + x.toLowerCase().replace(/\W+/g, '') === pkg; })[0] || '?';
        var hue = hues[n.length % hues.length];
        return 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><rect width="96" height="96" rx="24" fill="hsl(' + hue + ',70%,45%)"/><text x="48" y="60" font-family="sans-serif" font-size="34" font-weight="700" text-anchor="middle" fill="#fff">' + n.slice(0, 2) + '</text></svg>');
      },
      launch: function (pkg) { window.__launched = pkg; return true; },
      openUrl: function (u) { window.__opened = u; return true; },
      appInfo: function () {}, homeSettings: function () {}, isDefaultHome: function () { return false; },
      getState: function () { try { return localStorage.getItem('ps_state') || ''; } catch (e) { return ''; } },
      setState: function (s) { try { localStorage.setItem('ps_state', s); } catch (e) { /* ignore */ } },
      inkflowAccount: function () { return JSON.stringify({ url: 'https://inkflow.example', email: signedIn ? 'john@example.com' : '', name: signedIn ? 'John Sample' : '', signedIn: signedIn }); },
      inkflowSignIn: function () { setTimeout(function () { signedIn = true; window.onNative('signin', { ok: true }); }, 200); },
      inkflowSignOut: function () { signedIn = false; },
      setBars: function () {},
      photoAccessState: function () { return /nophotos/.test(location.search) ? 'ask' : 'granted'; },
      requestPhotoAccess: function () {},
      recentPhotos: function () { return JSON.stringify(['1', '2', '3', '4', '5', '6', '7', '8']); },
      thumbUrl: function (id) {
        var hues = [28, 205, 95, 330, 170, 45, 260, 0];
        return 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10" fill="hsl(' + hues[+id % 8] + ',45%,62%)"/></svg>');
      },
      openPhoto: function (id) { window.__photo = id; return true; },
      openCamera: function () { window.__camera = true; return true; },
      openOwnSettings: function () {},
      isNightMode: function () { return /night/.test(location.search); },
      inkflowRefresh: function () {
        setTimeout(function () {
          window.onNative('live', { ok: true, data: {
            counts: { dueToday: 3, late: 1, inProduction: 7, readyForPickup: 2 },
            due: [{ number: '#1042', customer: 'Sample Customer With A Long Name', title: 'Hoodies', label: '1 day late', tone: 'late', path: '/orders/abc' }],
            printers: [
              { name: 'EZ-JET PRO 24', online: true, busy: true, pct: 42, paused: false, lastOk: true, rollLow: false },
              { name: 'EZ-JET PRO 17', online: true, busy: false, pct: null, paused: false, lastOk: true, rollLow: true },
              { name: 'Epson 7900 A', online: false, busy: false, pct: null, paused: false, lastOk: null, rollLow: false }
            ] } });
        }, 150);
      }
    };
  }
})();
