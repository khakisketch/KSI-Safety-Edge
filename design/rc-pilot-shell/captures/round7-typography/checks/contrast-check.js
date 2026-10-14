(() => {
  const oklchToRgb = (css) => {
    const m = css.match(/oklch\(\s*([\d.]+%?)\s+([\d.]+%?)\s+([\d.]+)(?:deg)?\s*(?:\/\s*([\d.]+%?))?\s*\)/);
    if (!m) return null;
    const L = m[1].endsWith('%') ? parseFloat(m[1]) / 100 : parseFloat(m[1]);
    const C = m[2].endsWith('%') ? parseFloat(m[2]) * 0.004 : parseFloat(m[2]);
    const H = parseFloat(m[3]) * Math.PI / 180;
    const a = C * Math.cos(H), b = C * Math.sin(H);
    const l_ = L + 0.3963377774 * a + 0.2158037573 * b;
    const m_ = L - 0.1055613458 * a - 0.0638541728 * b;
    const s_ = L - 0.0894841775 * a - 1.2914855480 * b;
    const l = l_ ** 3, mm = m_ ** 3, ss = s_ ** 3;
    const r = 4.0767416621 * l - 3.3077115913 * mm + 0.2309699292 * ss;
    const g = -1.2684380046 * l + 2.6097574011 * mm - 0.3413193965 * ss;
    const bl = -0.0041960863 * l - 0.7034186147 * mm + 1.7076147010 * ss;
    const gamma = v => { v = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055; return Math.max(0, Math.min(255, Math.round(v * 255))); };
    const alpha = m[4] ? (m[4].endsWith('%') ? parseFloat(m[4]) / 100 : parseFloat(m[4])) : 1;
    return {rgb: [gamma(r), gamma(g), gamma(bl)], a: alpha};
  };
  const parseColor = (css) => {
    if (!css) return null;
    if (css.startsWith('oklch') || css.startsWith('oklab')) return oklchToRgb(css);
    const ctx = parseColor.ctx || (parseColor.ctx = document.createElement('canvas').getContext('2d'));
    ctx.fillStyle = '#010203';
    ctx.fillStyle = css;
    const s = ctx.fillStyle;
    if (typeof s === 'string' && s.startsWith('#') && s.length >= 7) {
      return {rgb: [parseInt(s.slice(1,3),16), parseInt(s.slice(3,5),16), parseInt(s.slice(5,7),16)], a: s.length === 9 ? parseInt(s.slice(7,9),16)/255 : 1};
    }
    const m = typeof s === 'string' ? s.match(/rgba?\(([^)]+)\)/) : null;
    if (m) {
      const p = m[1].split(',').map(x => parseFloat(x));
      return {rgb: p.slice(0,3), a: p.length > 3 ? p[3] : 1};
    }
    return null;
  };
  const lum = (rgb) => {
    const f = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
    return 0.2126 * f(rgb[0]) + 0.7152 * f(rgb[1]) + 0.0722 * f(rgb[2]);
  };
  const ratio = (a, b) => { const L1 = Math.max(lum(a), lum(b)), L2 = Math.min(lum(a), lum(b)); return (L1 + 0.05) / (L2 + 0.05); };
  const bgOf = (el) => {
    let node = el;
    while (node && node !== document.documentElement) {
      const st = getComputedStyle(node);
      const c = parseColor(st.backgroundColor);
      if (c && c.a >= 0.9) return {rgb: c.rgb, from: (node.className || node.tagName).toString().trim().split(' ')[0].slice(0, 40) || node.tagName};
      if (st.backgroundImage && st.backgroundImage !== 'none' && parseColor(st.backgroundColor) == null) return {gradientOnly: true, from: node.tagName};
      node = node.parentElement;
    }
    const c = parseColor(getComputedStyle(document.body).backgroundColor) || {rgb: [255,255,255]};
    return {rgb: c.rgb, from: 'body'};
  };
  const results = {checked: 0, failures: [], gradientExcluded: 0, samples: []};
  const els = [...document.querySelectorAll('body *')].filter(el => {
    const hasText = el.children.length === 0 && el.textContent && el.textContent.trim();
    if (!hasText) return false;
    if (el.getClientRects().length === 0) return false;
    if (typeof el.checkVisibility === 'function' && !el.checkVisibility({checkOpacity: true, checkVisibilityCSS: true})) return false;
    return true;
  });
  for (const el of els.slice(0, 1500)) {
    const st = getComputedStyle(el);
    const fg = parseColor(st.color); if (!fg) continue;
    const bg = bgOf(el);
    if (bg.gradientOnly) { results.gradientExcluded++; continue; }
    const size = parseFloat(st.fontSize); const bold = (parseInt(st.fontWeight) || 400) >= 700;
    const large = size >= 24 || (size >= 18.66 && bold);
    const r = ratio(fg.rgb, bg.rgb);
    results.checked++;
    const need = large ? 3 : 4.5;
    const rec = {sel: (el.id ? '#' + el.id : '.' + (el.className || el.tagName).toString().split(' ')[0]).slice(0, 50), text: el.textContent.trim().slice(0, 28), color: st.color, bg: 'rgb(' + bg.rgb.join(',') + ')', bgFrom: bg.from, ratio: Math.round(r * 100) / 100, need: need};
    if (r < need) results.failures.push(rec);
    else if (results.samples.length < 6) results.samples.push(rec);
  }
  return JSON.stringify({
    url: location.pathname, scheme: matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',
    viewport: innerWidth + 'x' + innerHeight,
    summary: {checked: results.checked, failures: results.failures.length, gradientExcluded: results.gradientExcluded},
    failures: results.failures.slice(0, 40), samples: results.samples
  }, null, 1);
})()
