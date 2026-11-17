/* Press Sheet: the home screen's whole interface. Talks to the phone through window.Android
   (MainActivity's Bridge). Opened in an ordinary browser it uses a stand-in with sample apps,
   which is how the layout gets tested off the phone. */
(function () {
  'use strict';

  var N = window.Android || mockBridge();
  var MODES = [{ id: 'shop', label: 'Shop' }, { id: 'home', label: 'Home' }, { id: 'show', label: 'Show time' }];
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
  var STATE_VERSION = 1;

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
  function appT(label, want) { return { type: 'app', label: label, want: want || [label.toLowerCase()], pkg: '', act: '' }; }
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
        }
      },
      dock: [
        { t: appT('Phone') }, { t: appT('Messages') }, { t: appT('InkStop Mail', ['inkstop mail']) },
        { t: appT('Chrome') }, { t: appT('Starbucks') }
      ]
    };
  }

  function loadState() {
    var s = null;
    try { s = JSON.parse(N.getState() || 'null'); } catch (e) { s = null; }
    if (!s || s.v !== STATE_VERSION || !s.modes || !s.modes.shop || !s.modes.home || !s.modes.show || !Array.isArray(s.dock)) {
      s = defaults();
    }
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
      if (a) { t.pkg = a.pkg; t.act = a.act; changed = true; }
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
    if (state.mode === 'show') return 'Show time.';
    var hr = new Date().getHours();
    var word = hr < 12 ? 'Morning' : hr < 17 ? 'Afternoon' : 'Evening';
    var name = (state.name || account.name || '').trim().split(/\s+/)[0] || '';
    return name ? word + ', ' + name + '.' : 'Good ' + word.toLowerCase() + '.';
  }

  function renderModes() {
    var nav = $('modes');
    nav.textContent = '';
    MODES.forEach(function (m) {
      var b = h('button', { type: 'button', 'aria-pressed': state.mode === m.id ? 'true' : 'false', text: m.label });
      b.addEventListener('click', function () { setMode(m.id); });
      nav.appendChild(b);
    });
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
    var el = h('button', { type: 'button', class: 'tile ' + t.size + ' ' + t.color + (empty ? ' empty' : '') });
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

  function render() {
    var mode = state.modes[state.mode];
    renderModes();
    $('greeting').textContent = greeting();
    $('dateline').textContent = new Date().toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' });
    var grid = $('grid');
    grid.textContent = '';
    grid.appendChild(renderHero(mode));
    mode.tiles.forEach(function (t) { grid.appendChild(renderTile(t, mode)); });
    renderDock();
  }

  // ------------------------------------------------------------------ live numbers from InkFlow

  function loadAccount() {
    try { account = JSON.parse(N.inkflowAccount()); } catch (e) { /* keep the last one */ }
  }

  function maybeRefreshLive(force) {
    if (state.mode !== 'shop' || !account.signedIn || live.loading) return;
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

      if (ctx.kind === 'dock') {
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
            var keepName = state.name;
            state = defaults(); state.name = keepName;
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
    return false;
  }

  function wireGestures() {
    var sx = 0, sy = 0, at = 0;
    var home = $('home');
    function start(e) { var t = e.touches[0]; sx = t.clientX; sy = t.clientY; at = Date.now(); }
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
    $('gear').addEventListener('click', function () { openSettings(); });
    $('search').addEventListener('input', renderAppList);
    $('scrim').addEventListener('click', closeSheet);
    document.addEventListener('contextmenu', function (e) { e.preventDefault(); });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeTop(); });
  }

  window.onNative = function (name, data) {
    if (name === 'back') { closeTop(); return; }
    if (name === 'home') { closeSheet(); closeDrawer(); $('home').scrollTop = 0; return; }
    if (name === 'apps') { loadApps(); matchApps(); render(); if (drawerIsOpen()) renderAppList(); return; }
    if (name === 'resume') { loadAccount(); render(); maybeRefreshLive(false); return; }
    if (name === 'signin') { if (signInDone) { var fn = signInDone; signInDone = null; fn(data); } return; }
    if (name === 'live') {
      live.loading = false;
      live.at = Date.now();
      if (data && data.ok) { live.data = data.data; live.error = ''; live.signedOut = false; }
      else {
        live.error = (data && data.error) || 'Couldn’t reach InkFlow';
        if (data && data.signedOut) { live.signedOut = true; live.data = null; loadAccount(); }
      }
      if (!sheetOpen && !drawerIsOpen()) render();
    }
  };

  // ------------------------------------------------------------------ start

  loadApps();
  loadAccount();
  state = loadState();
  matchApps();
  wireGestures();
  render();
  maybeRefreshLive(true);
  setInterval(function () {
    if (document.visibilityState !== 'visible') return;
    $('greeting').textContent = greeting();
    maybeRefreshLive(false);
  }, 30000);

  // ------------------------------------------------------------------ stand-in for testing in a browser

  function mockBridge() {
    var names = ['Calculator', 'Calendar', 'Camera', 'Canva', 'Chrome', 'Claude', 'Clock', 'Depop', 'eBay', 'Family Hub', 'FretQuest', 'Immich',
      'InkStop Mail', 'Jellyfin', 'JuniCare', 'Maps', 'Mercari', 'Messages', 'Nextcloud', 'PayPal POS', 'Phone', 'Photos', 'Poshmark',
      'Starbucks', 'The Roku App', 'Whatnot', 'YouTube', 'YT Music'];
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
