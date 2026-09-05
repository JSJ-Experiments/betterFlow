import { build } from 'esbuild';
import { cp, mkdir, rm } from 'node:fs/promises';
await rm('dist', { recursive: true, force: true });
await mkdir('dist', { recursive: true });
await build({
  entryPoints: ['src/main.js'],
  bundle: true,
  minify: true,
  format: 'esm',
  outfile: 'dist/app.js',
  target: ['es2020'],
});
await cp('src/index.html', 'dist/index.html');
await cp('src/style.css', 'dist/style.css');
