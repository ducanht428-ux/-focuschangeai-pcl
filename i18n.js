/* Lớp dịch Việt ⇄ Anh dùng chung cho dashboard, khung đo và trang kiểm thử.
   Cách làm: giao diện vẫn viết bằng tiếng Việt trong mã; khi chọn English, mọi chữ hiển thị được thay theo
   từ điển (window.FC_I18N_EN) ngay trên DOM, kể cả chuỗi động ghép từ nhiều mảnh. Chữ nào chưa có bản dịch
   thì giữ nguyên tiếng Việt (không vỡ giao diện). Chuyển lại tiếng Việt khôi phục đúng chữ gốc. */
(function () {
  'use strict';
  var KEY = 'fc_lang';
  var VI = /[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]/i;
  var dict = window.FC_I18N_EN || {};
  var lang = 'vi';
  try { var s = localStorage.getItem(KEY); if (s === 'en' || s === 'vi') lang = s; } catch (e) { /* storage bị chặn */ }

  var re = null, cache = Object.create(null);
  function build() {
    var keys = Object.keys(dict).filter(function (k) { return k.length > 0; })
      .sort(function (a, b) { return b.length - a.length; });   // dài trước: câu đầy đủ thắng mảnh ngắn
    var W = '[\\p{L}\\p{N}_]', isW = /[\p{L}\p{N}_]/u;
    var esc = keys.map(function (k) {
      var body = k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      // Chỉ ép "nguyên từ" ở đầu/cuối khi ký tự đó là chữ hoặc số: "và" không ăn vào "vàng",
      // còn khóa bắt đầu bằng dấu câu (", gắn tên…") vẫn khớp được ngay sau một chữ.
      return (isW.test(k.charAt(0)) ? '(?<!' + W + ')' : '') + body + (isW.test(k.charAt(k.length - 1)) ? '(?!' + W + ')' : '');
    });
    re = new RegExp('(?:' + esc.join('|') + ')', 'gu');
  }
  function tr(str) {
    if (cache[str] !== undefined) return cache[str];
    if (!re) build();
    var out = str.replace(re, function (m) { return dict[m] !== undefined ? dict[m] : m; });
    out = out.replace(/(\d+)\s*em(?![\p{L}])/gu, function (m, n) { return n + (n === '1' ? ' student' : ' students'); });
    if (Object.keys(cache).length > 4000) cache = Object.create(null);
    cache[str] = out;
    return out;
  }
  // Khóa tiếng Việt không có dấu (ví dụ "PERCLOS cao") cũng phải được dịch, nên cần một phép thử riêng cho chúng.
  var asciiRe = null;
  function buildAscii() {
    var ks = Object.keys(dict).filter(function (k) { return !VI.test(k) && /[A-Za-z]/.test(k) && dict[k] !== k; });
    asciiRe = ks.length ? new RegExp(ks.map(function (k) { return k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }).join('|')) : /(?!)/;
  }
  function needs(v) {
    if (!v) return false;
    if (VI.test(v) || /\d\s*em(?![\p{L}])/u.test(v)) return true;
    if (!asciiRe) buildAscii();
    return asciiRe.test(v);
  }

  var origT = new WeakMap(), outT = new WeakMap();
  function textNode(n) {
    var v = n.nodeValue, o = origT.get(n), out = outT.get(n);
    if (lang === 'en') {
      if (o !== undefined && v === out) return;                 // đã dịch, chưa bị app ghi đè
      if (needs(v)) {
        var t = tr(v);
        if (t !== v) { origT.set(n, v); outT.set(n, t); n.nodeValue = t; return; }
      }
      origT.delete(n); outT.delete(n);
    } else if (o !== undefined) {
      if (v === out) n.nodeValue = o;
      origT.delete(n); outT.delete(n);
    }
  }
  var ATTRS = ['title', 'placeholder', 'aria-label', 'alt'];
  function attrs(el) {
    if (!el.getAttribute) return;
    var rec = el.__fcA || (el.__fcA = {});
    for (var i = 0; i < ATTRS.length; i++) {
      var a = ATTRS[i], v = el.getAttribute(a);
      if (v === null) { delete rec[a]; continue; }
      var r = rec[a];
      if (lang === 'en') {
        if (r && v === r.out) continue;
        if (needs(v)) { var t = tr(v); if (t !== v) { rec[a] = { o: v, out: t }; el.setAttribute(a, t); continue; } }
        delete rec[a];
      } else if (r) {
        if (v === r.out) el.setAttribute(a, r.o);
        delete rec[a];
      }
    }
  }
  function walk(root) {
    if (!root) return;
    if (root.nodeType === 3) { textNode(root); return; }
    if (root.nodeType !== 1 && root.nodeType !== 9) return;
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        var p = n.parentNode, t = p && p.nodeName;
        return (t === 'SCRIPT' || t === 'STYLE' || t === 'TEXTAREA') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
      }
    });
    var n; while ((n = w.nextNode())) textNode(n);
    if (root.nodeType === 1) attrs(root);
    var els = (root.nodeType === 9 ? root.documentElement : root).querySelectorAll('[title],[placeholder],[aria-label],[alt]');
    for (var i = 0; i < els.length; i++) attrs(els[i]);
  }

  var origTitle = null;
  function applyAll() {
    document.documentElement.lang = lang;
    if (origTitle === null) origTitle = document.title;
    document.title = lang === 'en' ? tr(origTitle) : origTitle;
    walk(document.body);
    var btn = document.getElementById('lang-toggle');
    if (btn) btn.innerHTML = lang === 'vi' ? '<b>VI</b> · EN' : 'VI · <b>EN</b>';
  }

  var observer = null;
  function observe() {
    if (observer || !document.body) return;
    observer = new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var m = muts[i];
        if (m.type === 'characterData') textNode(m.target);
        else if (m.type === 'attributes') attrs(m.target);
        else for (var j = 0; j < m.addedNodes.length; j++) walk(m.addedNodes[j]);
      }
    });
    observer.observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ATTRS });
  }

  function syncFrames(l) {
    var fr = document.getElementsByTagName('iframe');
    for (var i = 0; i < fr.length; i++) {
      try { fr[i].contentWindow.postMessage({ fcLang: l }, location.origin); } catch (e) { /* khung chưa sẵn sàng */ }
    }
  }
  function set(l, fromParent) {
    if (l !== 'en' && l !== 'vi') return;
    var changed = l !== lang;
    lang = l;
    try { localStorage.setItem(KEY, l); } catch (e) { /* bỏ qua */ }
    applyAll();
    if (!fromParent) syncFrames(l);
    if (changed) { try { window.dispatchEvent(new CustomEvent('fc-lang', { detail: l })); } catch (e) { /* bỏ qua */ } }
  }

  window.addEventListener('message', function (e) {
    if (e.origin === location.origin && e.data && e.data.fcLang) set(e.data.fcLang, true);
  });
  window.addEventListener('storage', function (e) { if (e.key === KEY && e.newValue && e.newValue !== lang) set(e.newValue, false); });

  function boot() {
    if (lang === 'en') applyAll(); else { applyAll(); }
    observe();
    var btn = document.getElementById('lang-toggle');
    if (btn) btn.addEventListener('click', function () { set(lang === 'vi' ? 'en' : 'vi'); });
    document.addEventListener('change', function (e) { if (e.target && e.target.id === 'set-lang') set(e.target.value); });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();

  window.FC_I18N = {
    get lang() { return lang; },
    set: set,
    tr: tr,
    L: function (vi, en) { return lang === 'en' ? en : vi; },   // chuỗi viết sẵn hai ngôn ngữ (tính năng mới)
    // Liệt kê chữ Việt còn sót trên màn hình hiện tại (để bổ sung từ điển)
    missing: function () {
      var out = {}, w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT), n;
      while ((n = w.nextNode())) { var p = n.parentNode.nodeName; if (p === 'SCRIPT' || p === 'STYLE') continue; if (VI.test(n.nodeValue)) out[n.nodeValue.trim()] = 1; }
      return Object.keys(out);
    }
  };
})();
