#!/usr/bin/env node
/**
 * scripts/sync-claymation-pack.mjs
 *
 * Self-healing, checkpointed 3D Claymation Head Pack generator & delta-manifest updater.
 *
 * How it works:
 * 1. Queries MyFantasyLeague's live public endpoints (`TYPE=players&DETAILS=1`, `TYPE=topStarters`,
 *    `TYPE=topAdds`, `TYPE=projectedScores`) for the active NFL season to discover:
 *      - All signed NFL Head Coaches (`Coach`)
 *      - Fantasy-relevant offensive skill starters & primary backups (`QB`, `RB`, `WR`, `TE`, `PK`)
 *      - Top IDP defenders (`DT`, `DE`, `LB`, `CB`, `S`)
 *      - Weekly waiver-wire breakouts (`topAdds` / `topStarters`)
 * 2. Diffs that roster against `motisko-com/public/assets/gameday/clay/manifest.json` (`byMflId`),
 *    skipping every player whose neck-up clay head already exists (zero duplicate work across weeks/seasons!).
 * 3. Batches missing players 16-at-a-time into 4x4 dark-#0B131C sprite grids using Gemini Image API
 *    (`GEMINI_API_KEY`, model `gemini-3.1-flash-image-preview` / `imagen-3.0-generate-002`) with
 *    automatic exponential backoff on HTTP 429 / 503 capacity spikes.
 * 4. Slices each 4x4 sheet via border-seeded BFS flood-fill into `160x160` transparent `.webp` files
 *    (`~5.5 KB` each) and atomically bumps `manifestVersion` in `manifest.json`.
 *
 * Usage:
 *   node scripts/sync-claymation-pack.mjs --dry-run
 *   GEMINI_API_KEY=... node scripts/sync-claymation-pack.mjs --limit=64
 *   GEMINI_API_KEY=... node scripts/sync-claymation-pack.mjs --refresh-slug=lamar_jackson
 */

import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, v] = a.replace(/^--/, '').split('=');
    return [k, v ?? 'true'];
  })
);

const dryRun = args['dry-run'] === 'true';
const limit = Number(args.limit || 64);
const refreshSlug = args['refresh-slug'] || null;
const year = Number(args.year || new Date().getFullYear());
const clayDir =
  args['out-dir'] ||
  path.resolve(process.cwd(), '../motisko-com/public/assets/gameday/clay');
const headsDir = path.join(clayDir, 'heads');
const manifestPath = path.join(clayDir, 'manifest.json');

const require = createRequire(
  fs.existsSync(path.resolve(process.cwd(), '../motisko-com/package.json'))
    ? path.resolve(process.cwd(), '../motisko-com/package.json')
    : import.meta.url
);
const sharp = require('sharp');

function toDisplayName(rawName) {
  const parts = String(rawName || '').split(',').map((s) => s.trim());
  return parts.length === 2 && parts[1] ? `${parts[1]} ${parts[0]}` : String(rawName || '');
}

function toSlug(rawName) {
  return toDisplayName(rawName)
    .toLowerCase()
    .replace(/['’.]/g, '')
    .replace(/-/g, ' ')
    .split(/\s+/)
    .filter((t) => t && !['jr', 'sr', 'ii', 'iii', 'iv', 'v'].includes(t))
    .map((t) => t.replace(/[^a-z0-9]/g, ''))
    .join('_')
    .replace(/^_+|_+$/g, '');
}

async function fetchJsonSafe(url) {
  try {
    const r = await fetch(url, { headers: { 'User-Agent': 'MFLGameDayClaySync/1.0' } });
    if (!r.ok) return null;
    return await r.json();
  } catch {
    return null;
  }
}

function asArray(val) {
  if (!val) return [];
  return Array.isArray(val) ? val : [val];
}

function knockOutDarkBackground(rgba, width, height, tolerance = 26) {
  const corners = [
    0,
    (width - 1) * 4,
    (height - 1) * width * 4,
    ((height - 1) * width + (width - 1)) * 4,
  ];
  let bgR = 0, bgG = 0, bgB = 0;
  for (const idx of corners) {
    bgR += rgba[idx];
    bgG += rgba[idx + 1];
    bgB += rgba[idx + 2];
  }
  bgR /= corners.length;
  bgG /= corners.length;
  bgB /= corners.length;

  const distAt = (pIdx) => {
    const o = pIdx * 4;
    return Math.hypot(rgba[o] - bgR, rgba[o + 1] - bgG, rgba[o + 2] - bgB);
  };

  const visited = new Uint8Array(width * height);
  const isBg = new Uint8Array(width * height);
  const queue = new Int32Array(width * height);
  let head = 0, tail = 0;

  const seed = (x, y) => {
    const p = y * width + x;
    if (!visited[p] && distAt(p) <= tolerance) {
      visited[p] = 1;
      isBg[p] = 1;
      queue[tail++] = p;
    }
  };

  for (let x = 0; x < width; x++) { seed(x, 0); seed(x, height - 1); }
  for (let y = 0; y < height; y++) { seed(0, y); seed(width - 1, y); }

  while (head < tail) {
    const cur = queue[head++];
    const cx = cur % width;
    const cy = (cur / width) | 0;
    for (const [nx, ny] of [[cx - 1, cy], [cx + 1, cy], [cx, cy - 1], [cx, cy + 1]]) {
      if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
      const np = ny * width + nx;
      if (visited[np]) continue;
      visited[np] = 1;
      if (distAt(np) <= tolerance) {
        isBg[np] = 1;
        queue[tail++] = np;
      }
    }
  }

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const p = y * width + x;
      if (isBg[p]) {
        rgba[p * 4 + 3] = 0;
      }
    }
  }
}

async function generateGridWithBackoff(batch, apiKey) {
  const cellLines = batch.map((p, idx) => {
    const r = Math.floor(idx / 4) + 1;
    const c = (idx % 4) + 1;
    return `  ${idx + 1}. (R${r},C${c}) ${p.displayName} (${p.position}, ${p.team})`;
  }).join('\n');

  const prompt = [
    'Generate a crisp, evenly-spaced 4x4 sprite grid (1:1 square) on a pure flat dark #0B131C background with zero text, zero numbers, zero borders, and zero grid lines.',
    'Style: Handcrafted Aardman / Pixar stop-motion 3D polymer-clay caricature HEAD & NECK BUSTS ONLY (cropped cleanly at the base of the bare neck — NO jersey, NO shoulders, NO clothing, NO helmets, NO logos!).',
    `Exact ${batch.length} subjects in strict row-major order:`,
    cellLines,
  ].join('\n');

  const model = process.env.GEMINI_IMAGE_MODEL || 'gemini-2.0-flash-preview-image-generation';
  const endpoint = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}`;

  for (let attempt = 1; attempt <= 4; attempt++) {
    const res = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [{ parts: [{ text: prompt }] }],
        generationConfig: { responseModalities: ['TEXT', 'IMAGE'] },
      }),
    });

    if (res.status === 429 || res.status === 503) {
      const waitMs = attempt * 6000;
      console.warn(`[backoff] HTTP ${res.status} on attempt ${attempt}/4 — waiting ${waitMs}ms...`);
      await new Promise((r) => setTimeout(r, waitMs));
      continue;
    }

    if (!res.ok) {
      throw new Error(`Gemini Image API HTTP ${res.status}: ${await res.text()}`);
    }

    const json = await res.json();
    const parts = json?.candidates?.[0]?.content?.parts || [];
    const imgPart = parts.find((pt) => pt.inlineData?.data);
    if (!imgPart) throw new Error('No inlineData image returned by Gemini API');
    return Buffer.from(imgPart.inlineData.data, 'base64');
  }
  throw new Error('Exhausted retries on 429/503');
}

async function main() {
  fs.mkdirSync(headsDir, { recursive: true });
  const manifest = fs.existsSync(manifestPath)
    ? JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
    : {
        manifestVersion: 0,
        updatedAt: '',
        killSwitch: false,
        baseUrl: 'https://www.motisko.com/assets/gameday/clay/heads',
        byMflId: {},
        byEspnId: {},
        bySlug: {},
      };

  const statusJson = await fetchJsonSafe(`https://api.myfantasyleague.com/fflnetdynamic${year}/mfl_status.json`);
  const resolvedYear = Number(statusJson?.mfl_status?.year || (year - 1));
  const liveWeek = Number(statusJson?.mfl_status?.weeks?.LiveScoringWeek || 1);

  const [playersRes, projRes, topAddsRes, topStartersRes] = await Promise.all([
    fetchJsonSafe(`https://api.myfantasyleague.com/${resolvedYear}/export?TYPE=players&DETAILS=1&JSON=1`),
    fetchJsonSafe(`https://api.myfantasyleague.com/${resolvedYear}/export?TYPE=projectedScores&W=${liveWeek}&COUNT=2500&JSON=1`),
    fetchJsonSafe(`https://api.myfantasyleague.com/${resolvedYear}/export?TYPE=topAdds&W=${liveWeek}&JSON=1`),
    fetchJsonSafe(`https://api.myfantasyleague.com/${resolvedYear}/export?TYPE=topStarters&W=${liveWeek}&JSON=1`),
  ]);

  const allPlayers = asArray(playersRes?.players?.player);
  const projMap = new Map(
    asArray(projRes?.projectedScores?.playerScore).map((s) => [s.id, Number(s.score || 0)])
  );
  const priorityIds = new Set([
    ...asArray(topStartersRes?.topStarters?.player).map((p) => p.id),
    ...asArray(topAddsRes?.topAdds?.player).map((p) => p.id),
  ]);

  const SKILL_POS = new Set(['QB', 'RB', 'WR', 'TE', 'PK']);
  const IDP_POS = new Set(['DT', 'DE', 'LB', 'CB', 'S']);

  const hasLiveProjections = projMap.size > 0;

  const candidates = allPlayers
    .filter((p) => p.id && p.name && p.team && p.team !== 'FA')
    .filter((p) => {
      if (refreshSlug && toSlug(p.name) === refreshSlug) return true;
      if (manifest.byMflId[p.id]) return false;
      if (p.position === 'Coach') return true;
      const proj = projMap.get(p.id) || 0;
      if (SKILL_POS.has(p.position)) {
        return hasLiveProjections ? (priorityIds.has(p.id) || proj >= 1.0) : Boolean(p.espn_id);
      }
      if (IDP_POS.has(p.position)) return priorityIds.has(p.id) || proj >= 5.5;
      return false;
    })
    .map((p) => ({
      id: p.id,
      espnId: p.espn_id || null,
      displayName: toDisplayName(p.name),
      slug: toSlug(p.name),
      position: p.position,
      team: p.team,
      priorityScore:
        (priorityIds.has(p.id) ? 200 : 0) +
        (projMap.get(p.id) || 0) * 10 +
        (p.position === 'Coach' ? 140 : 0),
    }))
    .sort((a, b) => b.priorityScore - a.priorityScore);

  console.log(JSON.stringify({
    season: resolvedYear,
    week: liveWeek,
    alreadyMappedInManifest: Object.keys(manifest.byMflId).length,
    missingPriorityCandidates: candidates.length,
    top10NextQueue: candidates.slice(0, 10).map((c) => `${c.displayName} (${c.position} ${c.team}, MFL#${c.id})`),
  }, null, 2));

  if (dryRun) return;

  const apiKey = process.env.GEMINI_API_KEY || process.env.GOOGLE_API_KEY;
  if (!apiKey) {
    throw new Error('Set GEMINI_API_KEY (or pass --dry-run to inspect the discovery queue).');
  }

  const targetQueue = candidates.slice(0, limit);
  let generatedCount = 0;

  for (let offset = 0; offset < targetQueue.length; offset += 16) {
    const batch = targetQueue.slice(offset, offset + 16);
    while (batch.length < 16) {
      batch.push(batch[batch.length - 1]); // pad final partial grid cleanly
    }
    const imgBuf = await generateGridWithBackoff(batch, apiKey);
    const meta = await sharp(imgBuf).metadata();
    const cellW = Math.floor(meta.width / 4);
    const cellH = Math.floor(meta.height / 4);

    const uniqueCount = Math.min(16, targetQueue.length - offset);
    for (let i = 0; i < uniqueCount; i++) {
      const item = batch[i];
      const r = Math.floor(i / 4);
      const c = i % 4;
      const prevKey = manifest.byMflId[item.id];
      const nextVer = prevKey ? Number(prevKey.match(/_v(\d+)$/)?.[1] || 1) + 1 : 1;
      const assetKey = `${item.slug}_${item.id}_v${nextVer}`;

      const { data, info } = await sharp(imgBuf)
        .extract({ left: c * cellW, top: r * cellH, width: cellW, height: cellH })
        .ensureAlpha()
        .raw()
        .toBuffer({ resolveWithObject: true });

      knockOutDarkBackground(data, info.width, info.height, 26);

      await sharp(data, { raw: { width: info.width, height: info.height, channels: 4 } })
        .trim({ threshold: 5 })
        .resize(160, 160, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
        .webp({ quality: 82, alphaQuality: 90 })
        .toFile(path.join(headsDir, `${assetKey}.webp`));

      manifest.byMflId[item.id] = assetKey;
      if (item.espnId) manifest.byEspnId[item.espnId] = assetKey;
      if (!manifest.bySlug[item.slug]) manifest.bySlug[item.slug] = assetKey;
      generatedCount++;
    }

    // Checkpoint manifest after every 16-player sheet so progress is never lost!
    manifest.manifestVersion = Number(manifest.manifestVersion || 0) + 1;
    manifest.updatedAt = new Date().toISOString();
    fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
    console.log(`[checkpoint] Saved manifest v${manifest.manifestVersion} (+${uniqueCount} heads)`);
  }

  console.log(`Completed sync: +${generatedCount} new clay heads (manifestVersion=${manifest.manifestVersion}).`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
