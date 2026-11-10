import { test } from "node:test";
import assert from "node:assert/strict";
import { clearBytes, decrypt, encrypt, IV_BYTES } from "../web/frame-crypto.ts";

const key = (byte: number) =>
  crypto.subtle.importKey("raw", new Uint8Array(32).fill(byte), "AES-GCM", false, ["encrypt", "decrypt"]);
const frame = Uint8Array.from({ length: 100 }, (_, i) => i);

test("a frame decrypts to itself with the same key", async () => {
  const k = await key(1);
  const sealed = new Uint8Array(await encrypt(k, frame, 10));
  assert.deepEqual(new Uint8Array(await decrypt(k, sealed)), frame);
});

test("only the header stays readable", async () => {
  const sealed = new Uint8Array(await encrypt(await key(1), frame, 3));
  assert.deepEqual(sealed.subarray(0, 3), frame.subarray(0, 3));
  assert.notDeepEqual(sealed.subarray(3, 20), frame.subarray(3, 20));
  // Header, ciphertext, 16-byte tag, IV, header length
  assert.equal(sealed.byteLength, 100 + 16 + IV_BYTES + 1);
  assert.equal(sealed[sealed.byteLength - 1], 3);
});

test("another key, a changed header or an unencrypted frame doesn't decrypt", async () => {
  const sealed = new Uint8Array(await encrypt(await key(1), frame, 10));
  await assert.rejects(decrypt(await key(2), sealed));
  const tampered = sealed.slice();
  tampered[0] ^= 1;
  await assert.rejects(decrypt(await key(1), tampered));
  await assert.rejects(decrypt(await key(1), frame.slice()));
});

test("each encryption uses a new IV", async () => {
  const k = await key(1);
  const a = new Uint8Array(await encrypt(k, frame, 1));
  const b = new Uint8Array(await encrypt(k, frame, 1));
  assert.notDeepEqual(a, b);
});

test("Opus keeps 1 byte; VP8 key frames 10, others 3", () => {
  assert.equal(clearBytes({ data: new ArrayBuffer(0) }), 1);
  assert.equal(clearBytes({ data: new ArrayBuffer(0), type: "key" }), 10);
  assert.equal(clearBytes({ data: new ArrayBuffer(0), type: "delta" }), 3);
});
