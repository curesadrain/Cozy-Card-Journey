// Renders the app's card shaders in headless Chromium (WebGL 1 = GLSL ES 1.00, same as GLES 2.0).
// Usage:
//   1. ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest.renderCardArt'   (writes texture layers)
//   2. python3 tools/shader_check/extract.py                                      (shaders.json + textures)
//   3. NODE_PATH=$(npm root -g) node tools/shader_check/render.js                 (gl_*.png in this folder)
const { chromium } = require('playwright');
const path = require('path');
const sh = require('./shaders.json');
(async () => {
  const b = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--allow-file-access-from-files'] });
  const p = await b.newPage({ viewport: { width: 520, height: 700 } });
  await p.goto('file://' + path.join(__dirname, 'page.html'));
  const shots = [
    [0, 0, 3, 1.0, 0.85, 'frameL.png', 'artL.png', 'legendary_front'], [-14, 28, 3, 1.0, 0.85, 'frameL.png', 'artL.png', 'legendary_tilt'],
    [8, 70, 3, 1.0, 0.85, 'frameL.png', 'artL.png', 'legendary_edge'], [-10, 25, 2, 0.9, 0.45, 'frameE.png', 'artE.png', 'epic_tilt'],
    [-10, -25, 1, 0.75, 0, 'frameR.png', 'artR.png', 'rare_tilt'], [-8, 22, 0, 0, 0, 'frameC.png', 'artC.png', 'common_tilt'],
  ];
  for (const [rx, ry, rar, ff, af, f1, f2, name] of shots) {
    const err = await p.evaluate(async ([sh, rx, ry, rar, ff, af, f1, f2]) => {
      try { return await window.render(sh, rx, ry, rar, ff, af, 0, [f1, f2]); } catch (e) { return 'ERR ' + e; }
    }, [sh, rx, ry, rar, ff, af, f1, f2]);
    console.log(name, err === 0 ? 'ok' : err);
    if (err !== 0) process.exitCode = 1;
    await p.screenshot({ path: path.join(__dirname, `gl_${name}.png`) });
  }
  await b.close();
})();
