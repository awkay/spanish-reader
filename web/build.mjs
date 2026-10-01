// Builds the web app into dist/: bundled app.js + app.css, static files, and a service worker whose cache name
// changes with every build.
import { createHash } from 'node:crypto';
import { cpSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import * as esbuild from 'esbuild';

rmSync('dist', { recursive: true, force: true });
mkdirSync('dist');
await esbuild.build({
  entryPoints: { app: 'src/main.tsx' },
  bundle: true, minify: true, format: 'esm', target: ['safari15', 'chrome100'], outdir: 'dist',
  jsx: 'automatic', jsxImportSource: 'preact', legalComments: 'none', logLevel: 'warning',
});
cpSync('public', 'dist', { recursive: true });

const hash = createHash('sha256');
for (const f of readdirSync('dist').sort()) hash.update(f).update(readFileSync(`dist/${f}`));
const build = hash.digest('hex').slice(0, 12);

const index = readFileSync('dist/index.html', 'utf8').replaceAll('__BUILD__', build);
writeFileSync('dist/index.html', index);
const shell = ['./', './index.html', `./app.js?v=${build}`, `./app.css?v=${build}`, './manifest.webmanifest', './icon.svg',
  './icon-192.png', './icon-512.png', './apple-touch-icon.png'];
await esbuild.build({
  entryPoints: ['src/sw.ts'], bundle: true, minify: true, outfile: 'dist/sw.js', target: ['safari15'],
  define: { BUILD: JSON.stringify(build), SHELL: JSON.stringify(shell) }, logLevel: 'warning',
});
for (const f of readdirSync('dist')) console.log(`${f.padEnd(26)} ${(readFileSync(`dist/${f}`).length / 1024).toFixed(1)} KB`);
console.log('build', build);
