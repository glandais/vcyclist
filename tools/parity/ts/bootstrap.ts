/**
 * Runtime bootstrap for the TypeScript parity runners.
 *
 * MUST be the first import of every runner: ESM/CJS evaluate imported modules in
 * declaration order, and `virtual-cyclist`'s `Logger.ts` dereferences the bare global
 * `__DEV__` at module-evaluation time (Vite `define`s it at build time; under `tsx` it
 * simply does not exist -> ReferenceError).
 *
 * Also installs a DOM: `GPXParser.ts` uses `new DOMParser()`, which Node does not provide.
 * The library's own vitest config runs in the `jsdom` environment, so using jsdom here
 * reproduces exactly the DOM implementation the TS reference is tested against.
 */
import { createRequire } from 'node:module';
import { join } from 'node:path';

// Build-time constants that Vite `define`s and that both reference libraries dereference
// as bare globals at module-evaluation time (see their vite.config.ts `define` blocks).
//
// __DEV__ = false -> production semantics, all logging stripped, stdout clean for dumps.
// __NODE__ = true -> the `elevation` library selects its Node tile fetcher and node-canvas
//                    WebP decoder, which is the decoder this harness means to measure.
(globalThis as Record<string, unknown>).__DEV__ = false;
(globalThis as Record<string, unknown>).__NODE__ = true;

// This script lives in the vcyclist repo but runs against the virtual-cyclist repo's
// dependency tree (cwd == virtual-cyclist/, which is also what makes tsx pick up its
// tsconfig `paths`). Node would resolve a bare `jsdom` import relative to *this* file and
// fail, so resolve it from the cwd package instead of vendoring a second copy of jsdom.
const requireFromRefRepo = createRequire(join(process.cwd(), 'package.json'));
const { JSDOM } = requireFromRefRepo('jsdom') as typeof import('jsdom');

const dom = new JSDOM('<!doctype html><html><body></body></html>');
const g = globalThis as Record<string, unknown>;
g.DOMParser = dom.window.DOMParser;
g.document = dom.window.document;
g.window = dom.window;
g.Node = dom.window.Node;
g.Element = dom.window.Element;
