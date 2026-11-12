import { test } from "node:test";
import assert from "node:assert/strict";
import {
  clearBytes,
  decrypt,
  directionKey,
  encrypt,
  frameTransform,
  IV_BYTES,
  IvCounter,
  ReplayCheck,
  type EncodedFrame,
} from "../web/frame-crypto.ts";

const key = (byte: number) =>
  crypto.subtle.importKey("raw", new Uint8Array(32).fill(byte), "AES-GCM", false, ["encrypt", "decrypt"]);
const frame = Uint8Array.from({ length: 100 }, (_, i) => i);
const iv = () => new IvCounter().next();

test("a frame decrypts to itself with the same key", async () => {
  const k = await key(1);
  const sealed = new Uint8Array(await encrypt(k, frame, 10, iv()));
  assert.deepEqual(new Uint8Array(await decrypt(k, sealed)), frame);
});

test("only the header stays readable", async () => {
  const sealed = new Uint8Array(await encrypt(await key(1), frame, 3, iv()));
  assert.deepEqual(sealed.subarray(0, 3), frame.subarray(0, 3));
  assert.notDeepEqual(sealed.subarray(3, 20), frame.subarray(3, 20));
  // Header, ciphertext, 16-byte tag, IV, header length
  assert.equal(sealed.byteLength, 100 + 16 + IV_BYTES + 1);
  assert.equal(sealed[sealed.byteLength - 1], 3);
});

test("another key, a changed header or an unencrypted frame doesn't decrypt", async () => {
  const sealed = new Uint8Array(await encrypt(await key(1), frame, 10, iv()));
  await assert.rejects(decrypt(await key(2), sealed));
  const tampered = sealed.slice();
  tampered[0] ^= 1;
  await assert.rejects(decrypt(await key(1), tampered));
  await assert.rejects(decrypt(await key(1), frame.slice()));
});

test("the IVs of a stream count up behind a random prefix", () => {
  const a = new IvCounter();
  const first = a.next();
  const second = a.next();
  assert.deepEqual(first.subarray(0, 4), second.subarray(0, 4));
  assert.equal(first[IV_BYTES - 1] + 1, second[IV_BYTES - 1]);
  assert.notDeepEqual(new IvCounter().next().subarray(0, 4), first.subarray(0, 4));
});

test("a replayed or reordered frame is dropped; a new stream starts over", () => {
  const check = new ReplayCheck();
  const a = new IvCounter();
  const first = a.next();
  const second = a.next();
  assert.ok(check.accept(first));
  assert.ok(check.accept(second));
  assert.ok(!check.accept(second));
  assert.ok(!check.accept(first));
  assert.ok(check.accept(new IvCounter().next()));
});

test("each direction has its own key, so a sender's frames don't pass as the other person's", async () => {
  const link = new Uint8Array(16).fill(7);
  const host = await directionKey(link, "host");
  const guest = await directionKey(link, "guest");
  const sealed = new Uint8Array(await encrypt(host, frame, 1, iv()));
  assert.deepEqual(new Uint8Array(await decrypt(host, sealed)), frame);
  await assert.rejects(decrypt(guest, sealed));
  // The same link key gives the same direction keys on both pages
  const again = await directionKey(new Uint8Array(16).fill(7), "host");
  assert.deepEqual(new Uint8Array(await decrypt(again, sealed)), frame);
});

test("the transforms encrypt one way and decrypt the other, dropping what doesn't decrypt", async () => {
  const link = new Uint8Array(16).fill(3);
  const run = async (options: Parameters<typeof frameTransform>[0], frames: EncodedFrame[]) => {
    const out: EncodedFrame[] = [];
    const stream = frameTransform(options);
    // Read while writing: a transform stream only takes the next frame once the last one was read
    const reading = (async () => {
      for await (const f of stream.readable) out.push(f);
    })();
    const writer = stream.writable.getWriter();
    for (const f of frames) await writer.write(f);
    await writer.close();
    await reading;
    return out;
  };
  const sent = await run({ op: "encrypt", key: link, sender: "host" }, [{ data: frame.slice().buffer, type: "key" }]);
  assert.equal(sent.length, 1);
  const received = await run({ op: "decrypt", key: link, sender: "host" }, [
    sent[0],
    sent[0], // the same frame again: a replay
    { data: frame.slice().buffer }, // never encrypted
  ]);
  assert.equal(received.length, 1);
  assert.deepEqual(new Uint8Array(received[0].data), frame);
  const wrongDirection = await run({ op: "decrypt", key: link, sender: "guest" }, [sent[0]]);
  assert.equal(wrongDirection.length, 0);
});

test("Opus keeps 1 byte; VP8 key frames 10, others 3", () => {
  assert.equal(clearBytes({ data: new ArrayBuffer(0) }), 1);
  assert.equal(clearBytes({ data: new ArrayBuffer(0), type: "key" }), 10);
  assert.equal(clearBytes({ data: new ArrayBuffer(0), type: "delta" }), 3);
});
