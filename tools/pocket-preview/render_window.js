// Renders night_window.mat's own fragment block, as the phone frames it, to raw float32 RGBA.
//
//   node render_window.js <out.f32> <travelled_m> [width height]
//
// The picture is linear, before the tonemapper, exactly what the material hands Filament.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const F = require('./framing.json');

(async () => {
  const [out, travelled, w, h] = process.argv.slice(2);
  const W = +(w || 720), H = +(h || Math.round(720 * F.screen_h_px / F.screen_w_px));
  const mat = fs.readFileSync(path.join(__dirname, '../../app/src/main/materials/night_window.mat'), 'utf8');
  const frag = mat.slice(mat.indexOf('fragment {') + 'fragment {'.length, mat.lastIndexOf('}'));
  // Each layer's scroll as TrainMotion.scroll computes it, wrapped at the shader's period.
  const scroll = F.layer_depth_m.map((d, i) => {
    const s = +travelled * F.picture_distance_m / d, period = F.layer_cell_m[i] * 4096;
    return (s - Math.floor(s / period) * period).toFixed(5);
  }).join(',');
  const q = `w=${W}&h=${H}&vw=${F.visible_w_m}&vh=${F.visible_h_m}&size=${F.plane_m.join(',')}` +
            `&scroll=${scroll}&gain=${F.window_gain}`;
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await browser.newPage();
  await page.addInitScript(f => { window.__frag = f; }, frag);
  await page.goto(`file://${__dirname}/window.html?${q}`);
  await page.waitForFunction(() => document.body.dataset.ready || document.body.dataset.error, null, { timeout: 120000 });
  const err = await page.evaluate(() => document.body.dataset.error);
  if (err) { console.error('shader error:', err); process.exit(1); }
  const b64 = await page.evaluate(() => {
    const u8 = new Uint8Array(window.__pixels.buffer); let s = '';
    for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
    return btoa(s);
  });
  fs.writeFileSync(out, Buffer.from(b64, 'base64'));
  await browser.close();
  console.log(`window ${W}x${H} at ${travelled}m -> ${out}`);
})();
