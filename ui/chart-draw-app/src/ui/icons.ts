// Minimal inline SVG icons encoded as data URLs.
// These are lightweight placeholders; you can replace with richer assets later.

function svgToData(svg: string) {
  return `data:image/svg+xml;utf8,${encodeURIComponent(svg)}`;
}

const base = (content: string) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">${content}</svg>`;

export const Icons = {
  cursor: svgToData(base(`<path d="M3 2l8 8-3 1 3 7-2 1-3-7-3 1z" fill="#555"/>`)),
  line: svgToData(base(`<line x1="3" y1="20" x2="21" y2="4" stroke="#1e88e5" stroke-width="2"/>`)),
  ray: svgToData(base(`<circle cx="5" cy="19" r="2" fill="#1e88e5"/><line x1="5" y1="19" x2="24" y2="0" stroke="#1e88e5" stroke-width="2"/>`)),
  extLine: svgToData(base(`<line x1="0" y1="18" x2="24" y2="6" stroke="#5e35b1" stroke-width="2"/>`)),
  hline: svgToData(base(`<line x1="3" y1="12" x2="21" y2="12" stroke="#00897b" stroke-width="2"/>`)),
  vline: svgToData(base(`<line x1="12" y1="3" x2="12" y2="21" stroke="#455a64" stroke-width="2"/>`)),
  channel: svgToData(base(`<line x1="3" y1="18" x2="21" y2="6" stroke="#6a1b9a" stroke-width="2"/><line x1="3" y1="22" x2="21" y2="10" stroke="#6a1b9a" stroke-width="2"/>`)),
  rect: svgToData(base(`<rect x="5" y="6" width="14" height="12" fill="rgba(33,150,243,0.15)" stroke="#2196f3" stroke-width="2"/>`)),
  triangle: svgToData(base(`<path d="M4 20 L12 6 L20 20 Z" fill="rgba(239,108,0,0.15)" stroke="#ef6c00" stroke-width="2"/>`)),
  text: svgToData(base(`<text x="5" y="16" font-size="12" fill="#fff">T</text><rect x="4" y="7" width="16" height="12" fill="rgba(0,0,0,0.6)" rx="3" />`)),
  fibRetrace: svgToData(base(`<line x1="5" y1="6" x2="19" y2="6" stroke="#1565c0"/><line x1="5" y1="10" x2="19" y2="10" stroke="#1565c0"/><line x1="5" y1="14" x2="19" y2="14" stroke="#1565c0"/><line x1="5" y1="18" x2="19" y2="18" stroke="#1565c0"/>`)),
  fibExt: svgToData(base(`<line x1="5" y1="10" x2="19" y2="10" stroke="#ad1457"/><line x1="5" y1="6" x2="19" y2="6" stroke="#ad1457"/><line x1="5" y1="2" x2="19" y2="2" stroke="#ad1457"/>`)),
  delete: svgToData(base(`<path d="M6 7h12l-1 12a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2L6 7zm3-3h6l1 2H8l1-2z" fill="#e53935"/><line x1="9" y1="9" x2="9" y2="19" stroke="#fff" stroke-width="1.5"/><line x1="12" y1="9" x2="12" y2="19" stroke="#fff" stroke-width="1.5"/><line x1="15" y1="9" x2="15" y2="19" stroke="#fff" stroke-width="1.5"/>`)),
} as const;
