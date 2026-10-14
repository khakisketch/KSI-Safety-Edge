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
    const g = -1.2684380046 * l + 2.6097574010 * mm - 0.3413193965 * ss;
    const bl = -0.0041960863 * l - 0.7034186147 * mm + 1.7076147010 * ss;
    const gamma = (v) => {
      v = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
      return Math.max(0, Math.min(255, Math.round(v * 255)));
    };
    return { rgb: [gamma(r), gamma(g), gamma(bl)], a: m[4] ? parseFloat(m[4]) : 1 };
  };
  const parseColor = (css) => {
    if (!css) return null;
    if (css.startsWith('oklch')) return oklchToRgb(css);
    const m = css.match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    const parts = m[1].split(',').map((x) => parseFloat(x));
    return { rgb: parts.slice(0, 3), a: parts.length > 3 ? parts[3] : 1 };
  };
  const luminance = (rgb) => {
    const f = (v) => {
      v /= 255;
      return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    };
    return 0.2126 * f(rgb[0]) + 0.7152 * f(rgb[1]) + 0.0722 * f(rgb[2]);
  };
  const ratio = (a, b) => {
    const x = luminance(a), y = luminance(b);
    return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
  };
  const background = (el) => {
    for (let node = el; node && node !== document.documentElement; node = node.parentElement) {
      const color = parseColor(getComputedStyle(node).backgroundColor);
      if (color && color.a >= 0.99) return { rgb: color.rgb, source: node.id || node.className || node.tagName };
    }
    const color = parseColor(getComputedStyle(document.body).backgroundColor) || { rgb: [255, 255, 255] };
    return { rgb: color.rgb, source: 'body' };
  };
  const selectors = [
    '.nav-sub', '.mission-readiness h4', '.mission-readiness-row dt', '.mission-readiness-row dd',
    '.mission-sync-chip', '#mission-sync-feedback', '.mission-flow-heading h3',
    '.mission-flow-heading span', '.mission-flow button', '.mission-primary-note',
    '.mission-additional-action span', '.mission-additional-action button',
  ];
  const items = selectors.flatMap((selector) => [...document.querySelectorAll(selector)].map((el) => {
    const fg = parseColor(getComputedStyle(el).color);
    const bg = background(el);
    const fontSize = parseFloat(getComputedStyle(el).fontSize);
    const bold = (parseInt(getComputedStyle(el).fontWeight, 10) || 400) >= 700;
    const large = fontSize >= 24 || (fontSize >= 18.66 && bold);
    const contrast = fg ? ratio(fg.rgb, bg.rgb) : null;
    return {
      selector,
      text: el.textContent.trim().replace(/\s+/g, ' '),
      foreground: getComputedStyle(el).color,
      background: 'rgb(' + bg.rgb.join(',') + ')',
      backgroundSource: String(bg.source).split(' ')[0],
      fontSizePx: fontSize,
      ratio: contrast === null ? null : Math.round(contrast * 100) / 100,
      required: large ? 3 : 4.5,
      pass: contrast === null ? false : contrast >= (large ? 3 : 4.5),
    };
  }));
  const flowPseudo = [...document.querySelectorAll('.mission-flow li')].map((el) => {
    const pseudo = getComputedStyle(el, '::before');
    const fg = parseColor(pseudo.color);
    const bg = background(el);
    const contrast = fg ? ratio(fg.rgb, bg.rgb) : null;
    return {
      selector: '.mission-flow li::before',
      text: pseudo.content,
      foreground: pseudo.color,
      background: 'rgb(' + bg.rgb.join(',') + ')',
      ratio: contrast === null ? null : Math.round(contrast * 100) / 100,
      required: 4.5,
      pass: contrast !== null && contrast >= 4.5,
    };
  });
  return JSON.stringify({
    scheme: matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',
    viewport: innerWidth + 'x' + innerHeight,
    summary: { measured: items.length + flowPseudo.length, failures: [...items, ...flowPseudo].filter((x) => !x.pass).length },
    items: [...items, ...flowPseudo],
  }, null, 2);
})()
