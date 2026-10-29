/* Whisper Lounge — client behaviour: submit lock, tab router, advancements tree. */
(function () {
  'use strict';

  var doc = document;
  var reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
  var STORAGE_KEY = 'stories.rank.seen';

  function byId(id) { return doc.getElementById(id); }
  function list(root, selector) { return Array.prototype.slice.call(root.querySelectorAll(selector)); }

  /* ---------- forms: lock the button while the post is sent ---------- */
  list(doc, 'form').forEach(function (form) {
    var button = form.querySelector('button[type="submit"]');
    if (!button) { return; }
    var label = button.innerHTML;
    form.addEventListener('submit', function () {
      button.disabled = true;
      button.textContent = 'Sharing...';
    });
    // Coming back with the browser's Back button restores the page frozen mid-submit.
    window.addEventListener('pageshow', function (event) {
      if (event.persisted) { button.disabled = false; button.innerHTML = label; }
    });
  });

  /* ---------- advancements tree ---------- */
  function createAdvancements(section) {
    var win = section.querySelector('.adv-window');
    var board = section.querySelector('.adv-board');
    var nodes = list(section, '.adv-node');
    if (!win || !board || !nodes.length) { return null; }
    win.classList.add('js-adv');

    var activeTip = null;
    var pinned = null;
    var enterTimer = 0;
    var drag = null;
    var dragged = false;
    var suppressClick = false;

    function scale() { return parseFloat(getComputedStyle(win).getPropertyValue('--s')) || 3; }
    function ringOf(node) { return node.querySelector('.adv-ring'); }
    function tipOf(node) { return node.querySelector('.adv-tip'); }
    function isUnlocked(node) { return node.classList.contains('unlocked'); }

    /* --- tooltip --- */
    function place(node) {
      var tip = tipOf(node);
      var rect = ringOf(node).getBoundingClientRect();
      tip.style.visibility = 'hidden';
      tip.classList.add('show');
      var w = tip.offsetWidth;
      var h = tip.offsetHeight;
      var vw = doc.documentElement.clientWidth;
      var vh = window.innerHeight;
      var gap = 12;
      var edge = 8;
      var left;
      var top = rect.top + rect.height / 2 - h / 2;
      if (rect.right + gap + w <= vw - edge) {
        left = rect.right + gap;
      } else if (rect.left - gap - w >= edge) {
        left = rect.left - gap - w;
      } else {
        left = rect.left + rect.width / 2 - w / 2;
        top = rect.bottom + gap;
        if (top + h > vh - edge) { top = rect.top - gap - h; }
      }
      left = Math.max(edge, Math.min(left, vw - w - edge));
      top = Math.max(edge, Math.min(top, vh - h - edge));
      tip.style.left = Math.round(left) + 'px';
      tip.style.top = Math.round(top) + 'px';
      tip.style.visibility = '';
    }
    function showTip(node) {
      if (activeTip && activeTip !== node) { hideTip(activeTip); }
      activeTip = node;
      place(node);
    }
    function hideTip(node) {
      var tip = tipOf(node);
      tip.classList.remove('show');
      tip.style.left = '';
      tip.style.top = '';
      if (activeTip === node) { activeTip = null; }
    }
    function hideAll() {
      pinned = null;
      nodes.forEach(hideTip);
    }
    function reposition() { if (activeTip) { place(activeTip); } }

    /* --- click feedback --- */
    function animate(ring, name) {
      ring.classList.remove('pop', 'nope');
      void ring.offsetWidth; // restart the animation if it is already running
      ring.classList.add(name);
    }
    function burst(node) {
      if (reduced.matches) { return; }
      var s = scale();
      var colours = ['#ffffff', '#ffff55', '#55ff55', '#fcdb05'];
      var count = 12;
      for (var i = 0; i < count; i++) {
        var angle = (Math.PI * 2 * i) / count + (Math.random() - 0.5) * 0.5;
        var reach = 14 + Math.random() * 12;
        var spark = doc.createElement('span');
        spark.className = 'adv-spark';
        spark.style.setProperty('--dx', Math.round(Math.cos(angle) * reach) * s + 'px');
        spark.style.setProperty('--dy', Math.round(Math.sin(angle) * reach - 4) * s + 'px');
        spark.style.background = colours[i % colours.length];
        spark.addEventListener('animationend', function () { this.remove(); });
        node.appendChild(spark);
      }
      setTimeout(function () { list(node, '.adv-spark').forEach(function (el) { el.remove(); }); }, 1200);
    }
    section.addEventListener('animationend', function (event) {
      var target = event.target;
      if (target.classList && target.classList.contains('adv-ring')) { target.classList.remove('pop', 'nope'); }
    });

    /* --- per-node events --- */
    nodes.forEach(function (node, index) {
      var ring = ringOf(node);

      node.addEventListener('pointerenter', function (event) {
        if (event.pointerType === 'mouse' && !drag) { showTip(node); }
      });
      node.addEventListener('pointerleave', function (event) {
        if (event.pointerType === 'mouse' && pinned !== node) { hideTip(node); }
      });
      ring.addEventListener('focus', function () {
        if (ring.matches(':focus-visible')) { showTip(node); }
      });
      ring.addEventListener('blur', function () {
        if (pinned !== node) { hideTip(node); }
      });

      ring.addEventListener('click', function () {
        if (suppressClick) { return; }
        var unlocked = isUnlocked(node);
        animate(ring, unlocked ? 'pop' : 'nope');
        if (unlocked) { burst(node); }
        if (pinned === node) {
          pinned = null;
          if (!node.matches(':hover') && !ring.matches(':focus-visible')) { hideTip(node); }
        } else {
          pinned = node;
          showTip(node);
        }
      });

      ring.addEventListener('keydown', function (event) {
        var next = -1;
        if (event.key === 'ArrowRight' || event.key === 'ArrowDown') { next = Math.min(index + 1, nodes.length - 1); }
        else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') { next = Math.max(index - 1, 0); }
        else if (event.key === 'Home') { next = 0; }
        else if (event.key === 'End') { next = nodes.length - 1; }
        if (next >= 0) {
          event.preventDefault();
          ringOf(nodes[next]).focus();
        }
      });
    });

    doc.addEventListener('pointerdown', function (event) {
      if (pinned && !pinned.contains(event.target)) {
        var node = pinned;
        pinned = null;
        if (!node.matches(':hover') && !ringOf(node).matches(':focus-visible')) { hideTip(node); }
      }
    });
    doc.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && (pinned || activeTip)) { hideAll(); }
    });
    board.addEventListener('scroll', reposition, { passive: true });
    window.addEventListener('scroll', reposition, { passive: true });
    window.addEventListener('resize', function () { updatePan(); reposition(); });

    /* --- drag to pan (mouse; touch uses native scrolling) --- */
    function updatePan() { board.classList.toggle('can-pan', board.scrollWidth > board.clientWidth + 1); }
    board.addEventListener('pointerdown', function (event) {
      if (event.pointerType !== 'mouse' || event.button !== 0 || !board.classList.contains('can-pan')) { return; }
      drag = { id: event.pointerId, x: event.clientX, left: board.scrollLeft };
      dragged = false;
    });
    board.addEventListener('pointermove', function (event) {
      if (!drag || event.pointerId !== drag.id) { return; }
      var dx = event.clientX - drag.x;
      if (!dragged && Math.abs(dx) > 4) {
        dragged = true;
        board.classList.add('dragging');
        try { board.setPointerCapture(drag.id); } catch (e) { /* pointer already gone */ }
        hideAll();
      }
      if (dragged) { board.scrollLeft = drag.left - dx; }
    });
    function endDrag(event) {
      if (!drag || (event && event.pointerId !== drag.id)) { return; }
      try { board.releasePointerCapture(drag.id); } catch (e) { /* not captured */ }
      drag = null;
      board.classList.remove('dragging');
      if (dragged) {
        suppressClick = true;
        setTimeout(function () { suppressClick = false; }, 0);
      }
      dragged = false;
    }
    board.addEventListener('pointerup', endDrag);
    board.addEventListener('pointercancel', endDrag);

    /* --- show / hide --- */
    function scrollToProgress() {
      var unlocked = nodes.filter(isUnlocked);
      var target = unlocked.length ? unlocked[unlocked.length - 1] : nodes[0];
      var b = board.getBoundingClientRect();
      var n = target.getBoundingClientRect();
      var left = board.scrollLeft + (n.left - b.left) + n.width / 2 - board.clientWidth / 2;
      board.scrollLeft = Math.max(0, left);
    }
    function opened() {
      win.classList.remove('adv-enter');
      void win.offsetWidth;
      if (!reduced.matches) {
        win.classList.add('adv-enter');
        clearTimeout(enterTimer);
        enterTimer = setTimeout(function () { win.classList.remove('adv-enter'); }, 2200);
      }
      updatePan();
      scrollToProgress();
    }
    function closed() { hideAll(); }

    /* --- "Advancement Made!" toast when a new rank was reached since the last visit --- */
    function toast(node) {
      var type = node.classList.contains('challenge') ? 'challenge' : node.classList.contains('goal') ? 'goal' : 'task';
      var heads = { task: 'Advancement Made!', goal: 'Goal Reached!', challenge: 'Challenge Complete!' };
      var el = doc.createElement('a');
      el.className = 'adv-toast ' + type;
      el.href = '#advancements';
      el.setAttribute('role', 'status');

      var icon = doc.createElement('span');
      icon.className = 'adv-toast-icon';
      icon.setAttribute('aria-hidden', 'true');
      icon.appendChild(node.querySelector('.adv-frame').cloneNode(true));

      var text = doc.createElement('span');
      text.className = 'adv-toast-text';
      var head = doc.createElement('span');
      head.className = 'adv-toast-head';
      head.textContent = heads[type];
      var name = doc.createElement('span');
      name.className = 'adv-toast-name';
      name.textContent = node.querySelector('.tip-title').textContent;
      text.appendChild(head);
      text.appendChild(name);

      el.appendChild(icon);
      el.appendChild(text);
      doc.body.appendChild(el);

      function dismiss() {
        el.classList.remove('show');
        setTimeout(function () { el.remove(); }, 600);
      }
      el.addEventListener('click', dismiss);
      requestAnimationFrame(function () { requestAnimationFrame(function () { el.classList.add('show'); }); });
      setTimeout(dismiss, 6500);
    }
    function celebrate() {
      var unlocked = nodes.filter(isUnlocked);
      var seen = 0;
      try { seen = parseInt(localStorage.getItem(STORAGE_KEY), 10) || 0; } catch (e) { /* storage blocked */ }
      try { localStorage.setItem(STORAGE_KEY, String(unlocked.length)); } catch (e) { /* storage blocked */ }
      if (unlocked.length > seen) {
        setTimeout(function () { toast(unlocked[unlocked.length - 1]); }, 600);
      }
    }

    return { opened: opened, closed: closed, celebrate: celebrate };
  }

  var advSection = byId('advancements');
  var adv = advSection ? createAdvancements(advSection) : null;

  /* ---------- tabs (Home / Achievement Rank) ---------- */
  var views = { stories: byId('view-stories'), advancements: advSection };
  var tabs = list(doc, '[data-tab]');
  var current = null;

  function show(name) {
    if (!views[name]) { name = 'stories'; }
    Object.keys(views).forEach(function (key) {
      if (views[key]) { views[key].hidden = key !== name; }
    });
    tabs.forEach(function (tab) {
      if (tab.dataset.tab === name) { tab.setAttribute('aria-current', 'true'); } else { tab.removeAttribute('aria-current'); }
    });
    if (adv && name !== current) {
      if (name === 'advancements') { adv.opened(); } else if (current) { adv.closed(); }
    }
    current = name;
  }

  function sync() {
    var id = location.hash.slice(1);
    var name = id === 'advancements' ? 'advancements' : 'stories';
    var target = id ? byId(id) : null;
    show(name);
    if (name === 'advancements') { window.scrollTo(0, 0); } else if (target) { target.scrollIntoView(); }
  }

  tabs.forEach(function (tab) {
    tab.addEventListener('click', function (event) {
      event.preventDefault();
      if (location.hash !== '#' + tab.dataset.tab) { history.pushState(null, '', '#' + tab.dataset.tab); }
      show(tab.dataset.tab);
      window.scrollTo(0, 0);
    });
  });
  window.addEventListener('hashchange', sync);
  sync();

  if (adv) { adv.celebrate(); }
})();
