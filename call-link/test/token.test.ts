import { test } from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { createToken, randomId } from "../src/token.ts";

const decode = (part: string) => JSON.parse(Buffer.from(part, "base64url").toString());

test("a token is a JWT signed with the API secret, carrying the room grant", async () => {
  const token = await createToken({
    apiKey: "key", apiSecret: "secret", identity: "host", ttlSeconds: 600, now: 1_000,
    grant: { room: "abc", roomJoin: true },
  });
  const [header, claims, signature] = token.split(".");
  assert.deepEqual(decode(header), { alg: "HS256", typ: "JWT" });
  assert.deepEqual(decode(claims), {
    iss: "key", nbf: 990, exp: 1_600, sub: "host", jti: "host", video: { room: "abc", roomJoin: true },
  });
  const expected = createHmac("sha256", "secret").update(`${header}.${claims}`).digest("base64url");
  assert.equal(signature, expected);
});

test("room names are 128 random bits, URL-safe", () => {
  const ids = new Set(Array.from({ length: 100 }, () => randomId()));
  assert.equal(ids.size, 100);
  for (const id of ids) assert.match(id, /^[A-Za-z0-9_-]{22}$/);
});
