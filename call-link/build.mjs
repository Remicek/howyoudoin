// Bundles the call page's script and its encryption worker (a page can only start a worker from its own origin).
import { build } from "esbuild";

await build({
  entryPoints: { call: "web/call.ts", "e2ee-worker": "web/e2ee-worker.ts" },
  bundle: true,
  format: "esm",
  target: "es2020",
  minify: true,
  outdir: "public",
});
