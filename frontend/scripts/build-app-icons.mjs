// Draws the install icons for each role address (issue #201) into
// public/icons/app. Run by hand after changing a glyph or a colour, then commit
// the PNGs: `node scripts/build-app-icons.mjs` from frontend/. It uses the sharp
// that Next.js installs, so it adds no dependency, and it is not part of the
// build because the icons change rarely.
import { mkdirSync, readFileSync } from "node:fs";
import sharp from "sharp";

// The glyph from public/icons/go and the tile colour, as in src/app-shell/appManifest.ts.
const ICONS = {
  driver: { glyph: "truck.svg", background: "#0e766d" },
  loader: { glyph: "dock.svg", background: "#031b08" },
  store: { glyph: "home.svg", background: "#0b8a3a" },
  waypoint: { glyph: "box.svg", background: "#0e766d" },
};

const OUT = "public/icons/app";

/** The glyph's inner markup in white, and its square view box size. */
function glyph(file) {
  const svg = readFileSync(`public/icons/go/${file}`, "utf8");
  const size = Number(/viewBox="0 0 (\d+(?:\.\d+)?) /.exec(svg)[1]);
  const inner = svg
    .replace(/^[\s\S]*?<svg[^>]*>/, "")
    .replace(/<\/svg>\s*$/, "")
    .replace(/(stroke|fill)="(?!none)[^"]*"/g, '$1="#ffffff"');
  return { inner, size };
}

/**
 * One icon. `maskable` fills the square edge to edge and keeps the glyph inside
 * the safe circle (40% of the side either way from the centre); the plain icon
 * is a rounded tile with a larger glyph.
 */
function tile({ glyph: file, background }, maskable) {
  const { inner, size } = glyph(file);
  const side = 512;
  const glyphSide = maskable ? 240 : 300;
  const at = (side - glyphSide) / 2;
  const radius = maskable ? 0 : 112;
  return Buffer.from(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${side}" height="${side}" viewBox="0 0 ${side} ${side}">` +
      `<rect width="${side}" height="${side}" rx="${radius}" fill="${background}"/>` +
      `<svg x="${at}" y="${at}" width="${glyphSide}" height="${glyphSide}" viewBox="0 0 ${size} ${size}" fill="none">${inner}</svg>` +
      `</svg>`,
  );
}

mkdirSync(OUT, { recursive: true });
for (const [name, icon] of Object.entries(ICONS)) {
  const plain = tile(icon, false);
  const full = tile(icon, true);
  await sharp(plain).resize(192).png().toFile(`${OUT}/${name}-192.png`);
  await sharp(plain).resize(512).png().toFile(`${OUT}/${name}-512.png`);
  await sharp(full).resize(512).png().toFile(`${OUT}/${name}-maskable-512.png`);
  // iOS rounds the corners itself and shows transparency as black, so the touch icon is full bleed.
  await sharp(full).resize(180).png().toFile(`${OUT}/${name}-180.png`);
}
console.log(`App icons written to ${OUT}.`);
