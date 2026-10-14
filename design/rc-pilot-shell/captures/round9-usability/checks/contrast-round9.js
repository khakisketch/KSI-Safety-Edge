(() => {
  const parse = (value) => {
    if (!value || value === "transparent") return { rgb: [0, 0, 0], a: 0 };
    const rgb = value.match(/rgba?\(([^)]+)\)/);
    if (rgb) {
      const p = rgb[1].split(/[ ,/]+/).filter(Boolean).map(Number);
      return { rgb: p.slice(0, 3), a: p[3] ?? 1 };
    }
    const lch = value.match(/oklch\(\s*([\d.]+%?)\s+([\d.]+%?)\s+([\d.]+)(?:deg)?\s*(?:\/\s*([\d.]+%?))?\s*\)/);
    const lab = value.match(/oklab\(\s*([\d.]+%?)\s+([+-]?[\d.]+%?)\s+([+-]?[\d.]+%?)\s*(?:\/\s*([\d.]+%?))?\s*\)/);
    if (!lch && !lab) return null;
    const pct = (v) => v.endsWith("%") ? parseFloat(v) / 100 : parseFloat(v);
    const L = pct((lch || lab)[1]);
    let a, b;
    if (lch) {
      const C = lch[2].endsWith("%") ? parseFloat(lch[2]) * 0.004 : parseFloat(lch[2]);
      const H = parseFloat(lch[3]) * Math.PI / 180;
      a = C * Math.cos(H); b = C * Math.sin(H);
    } else {
      a = pct(lab[2]); b = pct(lab[3]);
    }
    const l_ = L + 0.3963377774 * a + 0.2158037573 * b;
    const m_ = L - 0.1055613458 * a - 0.0638541728 * b;
    const s_ = L - 0.0894841775 * a - 1.2914855480 * b;
    const l = l_ ** 3, m = m_ ** 3, s = s_ ** 3;
    const gamma = (v) => {
      v = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
      return Math.max(0, Math.min(255, Math.round(v * 255)));
    };
    return { rgb: [
      gamma(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
      gamma(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
      gamma(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s),
    ], a: ((lch || lab)[4] ? pct((lch || lab)[4]) : 1) };
  };
  const over = (front, back) => {
    const a = front.a + back.a * (1 - front.a);
    return { rgb: front.rgb.map((v, i) => a ? Math.round((v * front.a + back.rgb[i] * back.a * (1 - front.a)) / a) : 0), a };
  };
  const background = (el) => {
    let result = { rgb: [255, 255, 255], a: 1 };
    const nodes = [];
    for (let n = el; n; n = n.parentElement) nodes.push(n);
    for (const n of nodes.reverse()) {
      const color = parse(getComputedStyle(n).backgroundColor);
      if (color && color.a > 0) result = over(color, result);
    }
    return result;
  };
  const linear = (v) => {
    v /= 255;
    return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  };
  const luminance = (rgb) => 0.2126 * linear(rgb[0]) + 0.7152 * linear(rgb[1]) + 0.0722 * linear(rgb[2]);
  const ratio = (a, b) => {
    const x = luminance(a), y = luminance(b);
    return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
  };
  const selectors = [
    ".topbar .wordmark-copy strong", ".topbar .wordmark-copy small", ".topbar .time-note",
    ".topbar .rc-online-tag", ".topbar .simulated-tag", ".nav-group-label", ".nav-name",
    ".nav-sub", ".nav-close", ".mission-readiness h4", ".mission-readiness-row dt",
    ".mission-readiness-row dd", ".mission-sync-chip", "#mission-sync-feedback",
    ".mission-flow-heading h3", ".mission-flow-heading span", ".mission-flow button",
    ".mission-primary-note", ".mission-additional-action span", ".mission-additional-action button",
  ];
  const navOpen = document.body.classList.contains("nav-open");
  const items = selectors.flatMap((selector) => [...document.querySelectorAll(selector)].filter((el) => {
    const style = getComputedStyle(el), r = el.getBoundingClientRect();
    if (navOpen && selector.startsWith(".topbar")) return false;
    return style.display !== "none" && style.visibility === "visible" && r.width > 0 && r.height > 0;
  }).map((el) => {
    const fg = parse(getComputedStyle(el).color), bg = background(el);
    const style = getComputedStyle(el), size = parseFloat(style.fontSize);
    const large = size >= 24 || (size >= 18.66 && (parseInt(style.fontWeight, 10) || 400) >= 700);
    const value = fg ? ratio(fg.rgb, bg.rgb) : null;
    return { selector, text: el.textContent.trim().replace(/\s+/g, " "), fontSizePx: size,
      foreground: style.color, background: `rgb(${bg.rgb.join(",")})`, ratio: value === null ? null : +value.toFixed(2),
      required: large ? 3 : 4.5, pass: value !== null && value >= (large ? 3 : 4.5) };
  }));
  return { scheme: matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light",
    viewport: `${innerWidth}x${innerHeight}`, activePage: document.body.dataset.activePage,
    navOpen, measured: items.length,
    failures: items.filter((x) => !x.pass).length, minRatio: items.length ? Math.min(...items.map((x) => x.ratio ?? 100)) : null, items };
})()
