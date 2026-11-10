// Bundles the call page's script and copies LiveKit's end-to-end encryption worker next to it: a page can only start
// a worker from its own origin.
import { build } from "esbuild";
import { copyFileSync } from "node:fs";

await build({
  entryPoints: ["web/call.ts"],
  bundle: true,
  format: "esm",
  target: "es2020",
  minify: true,
  outfile: "public/call.js",
});
copyFileSync("node_modules/livekit-client/dist/livekit-client.e2ee.worker.mjs", "public/e2ee-worker.mjs");
