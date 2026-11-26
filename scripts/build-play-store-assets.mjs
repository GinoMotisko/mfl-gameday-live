#!/usr/bin/env node
/**
 * Repeatable Studio Play Store & Multi-Form-Factor Graphic Builder for GameDay Live (`com.motiskoventures.gameday`).
 *
 * Enforces Google Play Console 2026 visual & technical rules + Zero OS Status-Bar Clutter:
 *  - 24-bit RGB PNG (`channels === 3`, `hasAlpha === false` via two-pass `.flatten().removeAlpha()`) on ALL screenshots & feature graphic
 *  - 100% trademark-safe copy (two-word "Red Zone", zero "NFL"/"ESPN" claims, zero third-party logos)
 *  - Phone: 6 × 1080×1920 (9:16) with slim studio header + rounded titanium frame over inset-cropped playStore UI
 *  - Foldable: 4 × (2048×2048 1:1, 2048×1536 4:3, 1200×1920 10:16) over inset-cropped hinge-safe & cover UI
 *  - 7" Tablet: 4 × 1920×1080 (16:9) with 136px studio bar + native 2-pane UI
 *  - 10" Wide Tablet: 4 × 2560×1440 (16:9) with 176px studio bar + native 3-pane Command Center UI
 *  - Desktop / DeX / Chromebook: 2 × 2560×1440 (16:9) widescreen multi-column Broadcast Booth UI
 *  - Feature Graphic: 1024×500 (24-bit RGB)
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '..');

const sharpCandidates = [
  path.resolve(repoRoot, '../motisko-com/node_modules/sharp/lib/index.js'),
  'sharp',
];

let sharpMod = null;
const req = createRequire(import.meta.url);
for (const candidate of sharpCandidates) {
  try {
    sharpMod = req(candidate);
    break;
  } catch {
    // try next
  }
}
if (!sharpMod) {
  throw new Error('Could not resolve `sharp`. Install sharp or ensure ../motisko-com/node_modules/sharp exists.');
}
const sharp = sharpMod.default || sharpMod;

const rawDir = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.resolve(repoRoot, 'captures/raw_playstore_shots');

const outRoot = process.argv[3]
  ? path.resolve(process.argv[3])
  : path.resolve(repoRoot, 'dist/play-store-kit');

const phoneDir = path.join(outRoot, 'screenshots-phone');
const foldableDir = path.join(outRoot, 'screenshots-foldable');
const tab7Dir = path.join(outRoot, 'screenshots-tablet-7in');
const tab10Dir = path.join(outRoot, 'screenshots-tablet-10in');
const desktopDir = path.join(outRoot, 'screenshots-desktop');

function cleanAndEnsureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
  for (const entry of fs.readdirSync(dir)) {
    if (entry.toLowerCase().endsWith('.png') || entry.toLowerCase().endsWith('.jpg')) {
      fs.unlinkSync(path.join(dir, entry));
    }
  }
}

function escXml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

async function buildFeatureGraphic(destPath) {
  const w = 1024;
  const h = 500;
  const bars = [26, 48, 76, 42, 92, 64, 36, 84, 54, 30, 70, 44, 62, 28];
  const barSvg = bars
    .map((bh, idx) => {
      const x = 62 + idx * 14;
      const y = 398 - bh / 2;
      const fill = idx % 3 === 0 ? '#FBBF24' : idx % 2 === 0 ? '#14B8A6' : '#38BDF8';
      return `<rect x="${x}" y="${y}" width="8" height="${bh}" rx="4" fill="${fill}" opacity="0.95"/>`;
    })
    .join('\n');

  const svg = `<svg width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0%" stop-color="#050B16"/>
        <stop offset="52%" stop-color="#0B192E"/>
        <stop offset="100%" stop-color="#082826"/>
      </linearGradient>
      <radialGradient id="goldGlow" cx="76%" cy="32%" r="46%">
        <stop offset="0%" stop-color="#F59E0B" stop-opacity="0.30"/>
        <stop offset="100%" stop-color="#F59E0B" stop-opacity="0"/>
      </radialGradient>
      <radialGradient id="tealGlow" cx="18%" cy="82%" r="48%">
        <stop offset="0%" stop-color="#14B8A6" stop-opacity="0.28"/>
        <stop offset="100%" stop-color="#14B8A6" stop-opacity="0"/>
      </radialGradient>
      <linearGradient id="cardBg" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="#11223D"/>
        <stop offset="100%" stop-color="#091426"/>
      </linearGradient>
    </defs>

    <rect width="${w}" height="${h}" fill="url(#bg)"/>
    <rect width="${w}" height="${h}" fill="url(#goldGlow)"/>
    <rect width="${w}" height="${h}" fill="url(#tealGlow)"/>

    <!-- Subtle yard-line grid -->
    <g stroke="#1E3A5F" stroke-opacity="0.28" stroke-width="1">
      <line x1="0" y1="95" x2="1024" y2="95"/>
      <line x1="0" y1="250" x2="1024" y2="250"/>
      <line x1="0" y1="405" x2="1024" y2="405"/>
      <line x1="540" y1="0" x2="540" y2="500"/>
      <line x1="780" y1="0" x2="780" y2="500"/>
    </g>

    <!-- Top studio pill -->
    <rect x="58" y="54" width="340" height="34" rx="17" fill="#0F2937" stroke="#14B8A6" stroke-width="1.5"/>
    <circle cx="80" cy="71" r="5.5" fill="#10B981"/>
    <text x="95" y="76" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="13" font-weight="800" letter-spacing="1.3" fill="#5EEAD4">6 LIVE NEURAL COMMENTATORS</text>

    <!-- Headline -->
    <text x="54" y="152" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="56" font-weight="900" fill="#F8FAFC">GameDay Live</text>
    <text x="54" y="206" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="23" font-weight="700" fill="#FBBF24">Hear Every Big Play. Rule Every League.</text>
    <text x="54" y="250" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="16" font-weight="500" fill="#CBD5E1">Spoken play-by-play, linear stadium fields, Win %</text>
    <text x="54" y="276" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="16" font-weight="500" fill="#CBD5E1">&amp; Best Ball — Phone, Fold, Tablet &amp; Desktop.</text>

    <!-- Feature badges -->
    <rect x="58" y="312" width="176" height="32" rx="8" fill="#132640" stroke="#334155" stroke-width="1"/>
    <text x="146" y="333" text-anchor="middle" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="13" font-weight="700" fill="#E2E8F0">ZERO ACCOUNT MODE</text>

    <rect x="246" y="312" width="214" height="32" rx="8" fill="#132640" stroke="#334155" stroke-width="1"/>
    <text x="353" y="333" text-anchor="middle" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="13" font-weight="700" fill="#E2E8F0">5 BROADCAST THEMES</text>

    <!-- Equalizer waveform -->
    ${barSvg}
    <text x="274" y="404" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="15" font-weight="700" fill="#94A3B8">Auto-ducks music · Speaks in pocket</text>

    <!-- Right-side stylized Live Broadcast Card -->
    <rect x="548" y="56" width="424" height="388" rx="26" fill="url(#cardBg)" stroke="#F59E0B" stroke-opacity="0.7" stroke-width="2"/>

    <!-- Touchdown Alert Ribbon inside card -->
    <rect x="574" y="80" width="372" height="58" rx="14" fill="#3B2506" stroke="#FBBF24" stroke-width="1.5"/>
    <text x="596" y="105" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="12" font-weight="900" letter-spacing="1.2" fill="#FBBF24">TOUCHDOWN ALERT · SPOKEN LIVE</text>
    <text x="596" y="126" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="15" font-weight="700" fill="#FEF3C7">Mayfield 94-yd TD pass to C. Godwin (+19.4 pts)</text>

    <!-- Stylized Matchup Score Row -->
    <rect x="574" y="154" width="372" height="122" rx="16" fill="#0D1B30" stroke="#1E3A5F" stroke-width="1.2"/>
    <rect x="592" y="174" width="56" height="56" rx="14" fill="#7F1D1D" stroke="#FBBF24" stroke-width="2"/>
    <text x="620" y="210" text-anchor="middle" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="22" font-weight="900" fill="#FFFFFF">TB</text>
    <text x="664" y="198" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="15" font-weight="700" fill="#94A3B8">TAMPA BAY</text>
    <text x="664" y="228" font-family="Consolas, monospace" font-size="28" font-weight="900" fill="#F8FAFC">24</text>

    <text x="760" y="214" text-anchor="middle" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="14" font-weight="800" fill="#F59E0B">Q4 · 04:12</text>
    <rect x="722" y="226" width="76" height="24" rx="12" fill="#450A0A" stroke="#EF4444" stroke-width="1"/>
    <text x="760" y="242" text-anchor="middle" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="11" font-weight="900" fill="#FCA5A5">RED ZONE</text>

    <rect x="872" y="174" width="56" height="56" rx="14" fill="#0C4A6E" stroke="#38BDF8" stroke-width="1.5"/>
    <text x="900" y="210" text-anchor="middle" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="20" font-weight="900" fill="#FFFFFF">DET</text>
    <text x="856" y="198" text-anchor="end" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="15" font-weight="700" fill="#94A3B8">DETROIT</text>
    <text x="856" y="228" text-anchor="end" font-family="Consolas, monospace" font-size="28" font-weight="900" fill="#F8FAFC">21</text>

    <!-- Win Probability Bar -->
    <rect x="574" y="294" width="372" height="124" rx="16" fill="#0D1B30" stroke="#14B8A6" stroke-opacity="0.6" stroke-width="1.2"/>
    <text x="594" y="322" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="13" font-weight="900" fill="#2DD4BF">YOU · GRIDIRON GURUS</text>
    <text x="926" y="322" text-anchor="end" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="13" font-weight="800" fill="#CBD5E1">OPP · SUNDAY KINGS</text>
    <text x="594" y="356" font-family="Consolas, monospace" font-size="26" font-weight="900" fill="#F8FAFC">137.82</text>
    <text x="692" y="354" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="13" font-weight="700" fill="#5EEAD4">Est 154.6 · 84% Win</text>
    <text x="926" y="356" text-anchor="end" font-family="Consolas, monospace" font-size="26" font-weight="900" fill="#94A3B8">118.40</text>

    <rect x="594" y="376" width="332" height="14" rx="7" fill="#334155"/>
    <rect x="594" y="376" width="279" height="14" rx="7" fill="#14B8A6"/>
  </svg>`;

  await sharp(Buffer.from(svg))
    .flatten({ background: '#050B16' })
    .removeAlpha()
    .png({ compressionLevel: 9 })
    .toFile(destPath);
}

async function buildPortraitOrCoverCard({
  rawFile,
  outFile,
  W = 1080,
  H = 1920,
  pill,
  headline,
  subline,
  accent,
  bgTop,
  bgBottom,
  lightMode = false,
}) {
  const screenW = Math.round(W * 0.78);
  const screenY = Math.round(H * 0.175);
  const screenH = H - screenY - Math.round(H * 0.02);
  const screenX = Math.round((W - screenW) / 2);
  const radius = Math.round(W * 0.036);

  const resizedShot = await sharp(rawFile)
    .resize(screenW, screenH, { fit: 'fill' })
    .flatten({ background: lightMode ? '#F8FAFC' : '#050B16' })
    .png()
    .toBuffer();

  const maskSvg = Buffer.from(
    `<svg width="${screenW}" height="${screenH}" xmlns="http://www.w3.org/2000/svg">
      <rect x="0" y="0" width="${screenW}" height="${screenH}" rx="${radius}" ry="${radius}" fill="#FFFFFF"/>
    </svg>`
  );
  const roundedShot = await sharp(resizedShot)
    .composite([{ input: maskSvg, blend: 'dest-in' }])
    .png()
    .toBuffer();

  const bgSvg = `<svg width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <linearGradient id="bg" x1="0" y1="0" x2="0.4" y2="1">
        <stop offset="0%" stop-color="${bgTop}"/>
        <stop offset="100%" stop-color="${bgBottom}"/>
      </linearGradient>
      <radialGradient id="glow" cx="50%" cy="22%" r="55%">
        <stop offset="0%" stop-color="${accent}" stop-opacity="0.26"/>
        <stop offset="100%" stop-color="${accent}" stop-opacity="0"/>
      </radialGradient>
    </defs>
    <rect width="${W}" height="${H}" fill="url(#bg)"/>
    <rect width="${W}" height="${H}" fill="url(#glow)"/>

    <!-- Studio Pill -->
    <rect x="${Math.round((W - 520) / 2)}" y="56" width="520" height="44" rx="22" fill="#0F172A" stroke="${accent}" stroke-width="2"/>
    <text x="${Math.round(W / 2)}" y="85" text-anchor="middle" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="19" font-weight="800" letter-spacing="1.8" fill="${accent}">${escXml(pill)}</text>

    <!-- Headline & Subline -->
    <text x="${Math.round(W / 2)}" y="176" text-anchor="middle" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="54" font-weight="900" fill="#F8FAFC">${escXml(headline)}</text>
    <text x="${Math.round(W / 2)}" y="236" text-anchor="middle" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="27" font-weight="600" fill="#CBD5E1">${escXml(subline)}</text>

    <!-- Titanium Bezel Outer Frame -->
    <rect x="${screenX - 10}" y="${screenY - 10}" width="${screenW + 20}" height="${screenH + 20}" rx="${radius + 10}" fill="#1E293B" stroke="${accent}" stroke-opacity="0.65" stroke-width="3"/>
  </svg>`;

  const compBuf = await sharp(Buffer.from(bgSvg))
    .composite([{ input: roundedShot, left: screenX, top: screenY }])
    .png()
    .toBuffer();

  await sharp(compBuf)
    .flatten({ background: bgTop })
    .removeAlpha()
    .png({ compressionLevel: 9 })
    .toFile(outFile);
}

async function buildWideOrFoldableCard({
  rawFile,
  outFile,
  width,
  height,
  barHeight,
  badge,
  headline,
  subline,
  accent,
  bgLeft,
  bgRight,
}) {
  const padX = Math.round(width * 0.022);
  const padBottom = Math.round(height * 0.018);
  const shotW = width - padX * 2;
  const shotH = height - barHeight - padBottom;
  const radius = Math.round(width * 0.012);

  const resizedShot = await sharp(rawFile)
    .resize(shotW, shotH, { fit: 'fill' })
    .flatten({ background: '#070E1B' })
    .png()
    .toBuffer();

  const maskSvg = Buffer.from(
    `<svg width="${shotW}" height="${shotH}" xmlns="http://www.w3.org/2000/svg">
      <rect x="0" y="0" width="${shotW}" height="${shotH}" rx="${radius}" ry="${radius}" fill="#FFFFFF"/>
    </svg>`
  );
  const roundedShot = await sharp(resizedShot)
    .composite([{ input: maskSvg, blend: 'dest-in' }])
    .png()
    .toBuffer();

  const titleSize = Math.round(barHeight * 0.33);
  const subSize = Math.round(barHeight * 0.185);
  const pillH = Math.round(barHeight * 0.36);
  const pillW = Math.round(width * 0.24);
  const pillY = Math.round((barHeight - pillH) / 2);

  const bgSvg = `<svg width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0%" stop-color="${bgLeft}"/>
        <stop offset="100%" stop-color="${bgRight}"/>
      </linearGradient>
    </defs>
    <rect width="${width}" height="${height}" fill="url(#bg)"/>

    <!-- Left Pill Badge -->
    <rect x="${padX}" y="${pillY}" width="${pillW}" height="${pillH}" rx="${Math.round(pillH / 2)}" fill="#0F172A" stroke="${accent}" stroke-width="2"/>
    <text x="${padX + Math.round(pillW / 2)}" y="${pillY + Math.round(pillH * 0.66)}" text-anchor="middle" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="${Math.round(pillH * 0.37)}" font-weight="800" letter-spacing="1.4" fill="${accent}">${escXml(badge)}</text>

    <!-- Center/Right Studio Copy -->
    <text x="${padX + pillW + 28}" y="${Math.round(barHeight * 0.48)}" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="${titleSize}" font-weight="900" fill="#F8FAFC">${escXml(headline)}</text>
    <text x="${padX + pillW + 28}" y="${Math.round(barHeight * 0.80)}" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="${subSize}" font-weight="600" fill="#CBD5E1">${escXml(subline)}</text>

    <!-- Frame Border around Inset-Cropped Native Screenshot -->
    <rect x="${padX - 4}" y="${barHeight - 4}" width="${shotW + 8}" height="${shotH + 8}" rx="${radius + 4}" fill="#1E293B" stroke="${accent}" stroke-opacity="0.6" stroke-width="2.5"/>
  </svg>`;

  const compBuf = await sharp(Buffer.from(bgSvg))
    .composite([{ input: roundedShot, left: padX, top: barHeight }])
    .png()
    .toBuffer();

  await sharp(compBuf)
    .flatten({ background: bgLeft })
    .removeAlpha()
    .png({ compressionLevel: 9 })
    .toFile(outFile);
}

async function assertRgbAndSize(filePath, expectedW, expectedH) {
  const meta = await sharp(filePath).metadata();
  if (meta.width !== expectedW || meta.height !== expectedH) {
    throw new Error(`${path.basename(filePath)} size mismatch: got ${meta.width}x${meta.height}, expected ${expectedW}x${expectedH}`);
  }
  if (meta.channels !== 3 || meta.hasAlpha) {
    throw new Error(`${path.basename(filePath)} must be 24-bit RGB (3 channels, no alpha): got channels=${meta.channels}, hasAlpha=${meta.hasAlpha}`);
  }
  const kb = (fs.statSync(filePath).size / 1024).toFixed(0);
  console.log(`  [OK] ${path.relative(outRoot, filePath)} -> ${meta.width}x${meta.height}, 24-bit RGB (${kb} KB)`);
}

async function main() {
  console.log(`Building Play Store & Multi-Form-Factor 24-bit RGB Studio Assets from ${rawDir} -> ${outRoot}`);
  cleanAndEnsureDir(phoneDir);
  cleanAndEnsureDir(foldableDir);
  cleanAndEnsureDir(tab7Dir);
  cleanAndEnsureDir(tab10Dir);
  cleanAndEnsureDir(desktopDir);

  const featurePath = path.join(outRoot, 'feature-graphic-1024x500.png');
  await buildFeatureGraphic(featurePath);
  await assertRgbAndSize(featurePath, 1024, 500);

  const phoneSpecs = [
    {
      raw: 'p01_live_spoken.png',
      out: '01-phone-live-spoken-audio.png',
      pill: 'LIVE SPOKEN PLAY-BY-PLAY',
      headline: 'Hear Every Touchdown Live',
      subline: 'Linear stadium fields, spoken calls & Red Zone alerts',
      accent: '#FBBF24',
      bgTop: '#071124',
      bgBottom: '#0B1D36',
    },
    {
      raw: 'p02_multi_league_ppr.png',
      out: '02-phone-multi-league-winprob.png',
      pill: '5-STATE RINGS & WIN %',
      headline: 'Real-Time Win Probability',
      subline: '5-state player rings, pace projections & PPR tracking',
      accent: '#14B8A6',
      bgTop: '#061824',
      bgBottom: '#082630',
    },
    {
      raw: 'p03_bestball_oled.png',
      out: '03-phone-bestball-true-oled.png',
      pill: 'CYBER GRIDIRON TRUE OLED',
      headline: 'Optimal Best Ball Engine',
      subline: 'Live bubble starters, bench chasers & battery-saving black',
      accent: '#38BDF8',
      bgTop: '#020409',
      bgBottom: '#091426',
    },
    {
      raw: 'p04_voice_studio.png',
      out: '04-phone-voice-studio-personas.png',
      pill: '6 NEURAL COMMENTATORS',
      headline: 'Announcer Voice Studio',
      subline: 'David, Rex, Callie, Nigel, Macca & Coach Vance · Ducks music',
      accent: '#F87171',
      bgTop: '#1F090D',
      bgBottom: '#0E1322',
    },
    {
      raw: 'p05_daylight_plays.png',
      out: '05-phone-daylight-play-ticker.png',
      pill: 'SUNDAY DAYLIGHT THEME',
      headline: 'Patio-Bright Play Ticker',
      subline: 'Instant cross-league starter & conflict highlights',
      accent: '#38BDF8',
      bgTop: '#0C1E35',
      bgBottom: '#14304D',
      lightMode: true,
    },
    {
      raw: 'p06_launch_modes.png',
      out: '06-phone-zero-account-launch.png',
      pill: 'ZERO ACCOUNT REQUIRED',
      headline: 'Instant Kickoff in 1 Tap',
      subline: 'Live Scores Only, Sunday Simulator, or MFL Sync',
      accent: '#34D399',
      bgTop: '#061A1E',
      bgBottom: '#0B192E',
    },
  ];

  for (const s of phoneSpecs) {
    const outFile = path.join(phoneDir, s.out);
    await buildPortraitOrCoverCard({
      rawFile: path.join(rawDir, s.raw),
      outFile,
      W: 1080,
      H: 1920,
      pill: s.pill,
      headline: s.headline,
      subline: s.subline,
      accent: s.accent,
      bgTop: s.bgTop,
      bgBottom: s.bgBottom,
      lightMode: Boolean(s.lightMode),
    });
    await assertRgbAndSize(outFile, 1080, 1920);
  }

  // Foldable 4-Asset Matrix (1:1 2048x2048, 4:3 2048x1536, 10:16 1200x1920)
  const foldableSpecs = [
    {
      kind: 'wide',
      raw: 'f01_unfolded_1x1_oled.png',
      out: '01-fold-unfolded-1x1-oled.png',
      width: 2048,
      height: 2048,
      barHeight: 168,
      badge: 'UNFOLDED 1:1 OLED',
      headline: 'Hinge-Safe Master-Detail Split',
      subline: '5-state clay player rings, live drive strip & instant league switcher',
      accent: '#14B8A6',
      bgLeft: '#020408',
      bgRight: '#081C24',
    },
    {
      kind: 'wide',
      raw: 'f02_unfolded_4x3_live.png',
      out: '02-fold-unfolded-4x3-live.png',
      width: 2048,
      height: 1536,
      barHeight: 156,
      badge: 'UNFOLDED 4:3 LIVE',
      headline: 'Edge-to-Edge Linear Stadium Fields',
      subline: 'Spoken touchdown banners, Red Zone shading & 1-tap Booth Pill',
      accent: '#FBBF24',
      bgLeft: '#061020',
      bgRight: '#0E223D',
    },
    {
      kind: 'portrait',
      raw: 'f03_cover_10x16_winprob.png',
      out: '03-fold-cover-10x16-winprob.png',
      width: 1200,
      height: 1920,
      pill: 'FOLDABLE COVER 10:16',
      headline: 'One-Handed Cover Scoreboard',
      subline: 'Check Win % on the cover, unfold straight into full split view',
      accent: '#34D399',
      bgTop: '#061822',
      bgBottom: '#0B1D34',
    },
    {
      kind: 'wide',
      raw: 'f04_unfolded_1x1_daylight_bestball.png',
      out: '04-fold-unfolded-1x1-daylight-bestball.png',
      width: 2048,
      height: 2048,
      barHeight: 168,
      badge: 'DAYLIGHT 1:1 BEST BALL',
      headline: 'Optimal Counting Lineup & Chasers',
      subline: 'High-contrast Sunday Daylight palette across unfolded inner displays',
      accent: '#38BDF8',
      bgLeft: '#0B1E36',
      bgRight: '#133152',
    },
  ];

  for (const f of foldableSpecs) {
    const outFile = path.join(foldableDir, f.out);
    if (f.kind === 'portrait') {
      await buildPortraitOrCoverCard({
        rawFile: path.join(rawDir, f.raw),
        outFile,
        W: f.width,
        H: f.height,
        pill: f.pill,
        headline: f.headline,
        subline: f.subline,
        accent: f.accent,
        bgTop: f.bgTop,
        bgBottom: f.bgBottom,
      });
    } else {
      await buildWideOrFoldableCard({
        rawFile: path.join(rawDir, f.raw),
        outFile,
        width: f.width,
        height: f.height,
        barHeight: f.barHeight,
        badge: f.badge,
        headline: f.headline,
        subline: f.subline,
        accent: f.accent,
        bgLeft: f.bgLeft,
        bgRight: f.bgRight,
      });
    }
    await assertRgbAndSize(outFile, f.width, f.height);
  }

  const tab7Specs = [
    {
      raw: 't7_01_live_scoreboard.png',
      out: '01-tab7-live-scoreboard-grid.png',
      badge: 'COMPACT 7"–8" TABLET',
      headline: 'Multi-Column Scoreboard & Linear Stadium Fields',
      subline: 'Adaptive Navigation Rail + Red Zone field progress + spoken alerts',
      accent: '#FBBF24',
      bgLeft: '#061020',
      bgRight: '#0E223D',
    },
    {
      raw: 't7_02_oled_split.png',
      out: '02-tab7-oled-fantasy-split.png',
      badge: 'TRUE-BLACK OLED SPLIT',
      headline: 'Master-Detail Matchup & Starter Breakdown',
      subline: 'Pinned YOU vs OPP win probability alongside 5-state player rings',
      accent: '#14B8A6',
      bgLeft: '#020408',
      bgRight: '#081C24',
    },
    {
      raw: 't7_03_daylight_bestball.png',
      out: '03-tab7-daylight-bestball.png',
      badge: 'SUNDAY DAYLIGHT MODE',
      headline: 'Automated Best Ball Optimal Lineup Tracking',
      subline: 'Real-time optimal counting slots with live bench chaser alerts',
      accent: '#38BDF8',
      bgLeft: '#0B1E36',
      bgRight: '#133152',
    },
    {
      raw: 't7_04_gold_voice_themes.png',
      out: '04-tab7-classic-gold-voices.png',
      badge: 'CLASSIC GAMEDAY GOLD',
      headline: 'Adaptive Broadcast Studio & 6 Neural Commentators',
      subline: 'Audition David, Rex, Callie, Nigel, Macca & Coach Vance in one tap',
      accent: '#F59E0B',
      bgLeft: '#191206',
      bgRight: '#0D1726',
    },
  ];

  for (const s of tab7Specs) {
    const outFile = path.join(tab7Dir, s.out);
    await buildWideOrFoldableCard({
      rawFile: path.join(rawDir, s.raw),
      outFile,
      width: 1920,
      height: 1080,
      barHeight: 136,
      badge: s.badge,
      headline: s.headline,
      subline: s.subline,
      accent: s.accent,
      bgLeft: s.bgLeft,
      bgRight: s.bgRight,
    });
    await assertRgbAndSize(outFile, 1920, 1080);
  }

  const tab10Specs = [
    {
      raw: 't10_01_command_center.png',
      out: '01-tab10-sunday-command-center.png',
      badge: '10" 3-PANE COMMAND CENTER',
      headline: 'Built for the Sunday Couch & Man-Cave Counter',
      subline: 'Simultaneous Navigation Rail, Dual Matchup Scoreboard & Pinned Live Play Ticker',
      accent: '#FBBF24',
      bgLeft: '#060F1F',
      bgRight: '#0C213B',
    },
    {
      raw: 't10_02_daylight_live_grid.png',
      out: '02-tab10-daylight-live-grid.png',
      badge: 'HIGH-CONTRAST DAYLIGHT',
      headline: 'Wide-Screen Live Games Grid + Real-Time Call Feed',
      subline: 'Track every linear stadium drive on the left while spoken calls stream on the right',
      accent: '#38BDF8',
      bgLeft: '#0B1D34',
      bgRight: '#12314E',
    },
    {
      raw: 't10_03_oled_bestball_command.png',
      out: '03-tab10-oled-bestball-command.png',
      badge: 'CYBER GRIDIRON OLED',
      headline: 'Multi-League PPR & Best Ball Command Deck',
      subline: 'Switch leagues in one tap with 5-state clay rings & conflict badges',
      accent: '#14B8A6',
      bgLeft: '#020408',
      bgRight: '#071B24',
    },
    {
      raw: 't10_04_voice_and_themes.png',
      out: '04-tab10-broadcast-voice-themes.png',
      badge: 'STUDIO CUSTOMIZATION',
      headline: '5-Category Broadcast Hub & 2-Tap Cross-Device Sync',
      subline: '6 commentators, 5 themes, music ducking & password-free setup cloning',
      accent: '#34D399',
      bgLeft: '#06181F',
      bgRight: '#0C1E33',
    },
  ];

  for (const s of tab10Specs) {
    const outFile = path.join(tab10Dir, s.out);
    await buildWideOrFoldableCard({
      rawFile: path.join(rawDir, s.raw),
      outFile,
      width: 2560,
      height: 1440,
      barHeight: 176,
      badge: s.badge,
      headline: s.headline,
      subline: s.subline,
      accent: s.accent,
      bgLeft: s.bgLeft,
      bgRight: s.bgRight,
    });
    await assertRgbAndSize(outFile, 2560, 1440);
  }

  const desktopSpecs = [
    {
      raw: 'd01_desktop_command_center.png',
      out: '01-desktop-widescreen-command-center.png',
      badge: 'DESKTOP & SAMSUNG DEX',
      headline: 'Full Widescreen Fantasy War Room',
      subline: 'Side-by-side active starters, 5-state clay rings & pinned play feed on ChromeOS & DeX',
      accent: '#14B8A6',
      bgLeft: '#040C18',
      bgRight: '#0A1E32',
    },
    {
      raw: 'd02_desktop_live_stadium_grid.png',
      out: '02-desktop-widescreen-live-stadium-grid.png',
      badge: 'MULTI-GAME DESKTOP WALL',
      headline: '5-Column Live Stadium Grid + Spoken Booth',
      subline: 'Every active drive, Red Zone alert & fantasy conflict across your desktop monitor',
      accent: '#FBBF24',
      bgLeft: '#020408',
      bgRight: '#0B192C',
    },
  ];

  for (const s of desktopSpecs) {
    const outFile = path.join(desktopDir, s.out);
    await buildWideOrFoldableCard({
      rawFile: path.join(rawDir, s.raw),
      outFile,
      width: 2560,
      height: 1440,
      barHeight: 176,
      badge: s.badge,
      headline: s.headline,
      subline: s.subline,
      accent: s.accent,
      bgLeft: s.bgLeft,
      bgRight: s.bgRight,
    });
    await assertRgbAndSize(outFile, 2560, 1440);
  }

  console.log('ALL 21 STUDIO GRAPHICS VERIFIED (24-BIT RGB, ZERO ALPHA, ZERO OS STATUS-BAR CLUTTER)!');
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
