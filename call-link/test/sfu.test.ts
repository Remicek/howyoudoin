import { test } from "node:test";
import assert from "node:assert/strict";
import { iceServers, Sfu, SfuError } from "../src/sfu.ts";

function fakeFetch(responses: { status?: number; body: unknown }[]) {
  const calls: { url: string; init: RequestInit }[] = [];
  const fetcher = (async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    const next = responses.shift()!;
    return new Response(JSON.stringify(next.body), { status: next.status ?? 200 });
  }) as unknown as typeof fetch;
  return { calls, fetcher };
}

test("requests go to the app's sessions with its secret", async () => {
  const { calls, fetcher } = fakeFetch([{ status: 201, body: { sessionId: "s1" } }]);
  assert.equal(await new Sfu("app", "secret", fetcher).newSession(), "s1");
  assert.equal(calls[0].url, "https://rtc.live.cloudflare.com/v1/apps/app/sessions/new");
  assert.equal(calls[0].init.method, "POST");
  assert.equal((calls[0].init.headers as Record<string, string>).Authorization, "Bearer secret");
});

test("a track that failed fails the request, even when the request succeeded", async () => {
  const { fetcher } = fakeFetch([
    { body: { tracks: [{ trackName: "audio", mid: "0" }, { trackName: "video1", errorCode: "not_found" }] } },
  ]);
  await assert.rejects(new Sfu("app", "secret", fetcher).newTracks("s1", {}), SfuError);
});

test("an error from the SFU is an SfuError", async () => {
  const { fetcher } = fakeFetch([{ status: 400, body: { errorCode: "invalid_session_description" } }]);
  await assert.rejects(new Sfu("app", "secret", fetcher).renegotiate("s1", { type: "answer", sdp: "" }), /invalid_session/);
});

test("without a TURN key, only STUN", async () => {
  assert.deepEqual(await iceServers(undefined, undefined, 60), [{ urls: "stun:stun.cloudflare.com:3478" }]);
});

test("TURN servers without port 53, which browsers block", async () => {
  const { calls, fetcher } = fakeFetch([
    {
      status: 201,
      body: {
        iceServers: [
          { urls: ["stun:stun.cloudflare.com:3478", "stun:stun.cloudflare.com:53"] },
          { urls: ["turn:turn.cloudflare.com:3478?transport=udp", "turn:turn.cloudflare.com:53?transport=udp"], username: "u", credential: "c" },
        ],
      },
    },
  ]);
  assert.deepEqual(await iceServers("key", "token", 600, fetcher), [
    { urls: ["stun:stun.cloudflare.com:3478"] },
    { urls: ["turn:turn.cloudflare.com:3478?transport=udp"], username: "u", credential: "c" },
  ]);
  assert.equal(calls[0].url, "https://rtc.live.cloudflare.com/v1/turn/keys/key/credentials/generate-ice-servers");
  assert.deepEqual(JSON.parse(calls[0].init.body as string), { ttl: 600 });
});

test("TURN failing leaves STUN", async () => {
  const { fetcher } = fakeFetch([{ status: 500, body: {} }]);
  const original = console.error;
  console.error = () => {};
  try {
    assert.deepEqual(await iceServers("key", "token", 600, fetcher), [{ urls: "stun:stun.cloudflare.com:3478" }]);
  } finally {
    console.error = original;
  }
});
