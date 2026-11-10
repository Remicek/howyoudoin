import { test } from "node:test";
import assert from "node:assert/strict";
import { firstOrder, Play } from "../src/play.ts";

async function serviceAccount(): Promise<string> {
  const pair = (await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;
  const der = new Uint8Array((await crypto.subtle.exportKey("pkcs8", pair.privateKey)) as ArrayBuffer);
  const pem = `-----BEGIN PRIVATE KEY-----\n${Buffer.from(der).toString("base64")}\n-----END PRIVATE KEY-----\n`;
  return JSON.stringify({ client_email: "play@example.iam.gserviceaccount.com", private_key: pem });
}

/** A pretend Google: signs in, then answers each API path from `answers`. */
function google(answers: Record<string, unknown>, seen: string[]): typeof fetch {
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    seen.push(`${init?.method ?? "GET"} ${url}`);
    if (url.startsWith("https://oauth2.googleapis.com/token")) {
      assert.match(String(init?.body), /assertion=[\w-]+\.[\w-]+\.[\w-]+/);
      return Response.json({ access_token: "at", expires_in: 3600 });
    }
    assert.equal(new Headers(init?.headers).get("Authorization"), "Bearer at");
    const answer = Object.entries(answers).find(([path]) => url.endsWith(path))?.[1];
    return answer === undefined ? new Response("", { status: 404 }) : Response.json(answer);
  }) as typeof fetch;
}

const NOW = Date.UTC(2026, 10, 10);

test("an active subscription is Premium, counted per subscription, and gets acknowledged", async () => {
  const seen: string[] = [];
  const play = new Play(
    await serviceAccount(),
    "com.example",
    google(
      {
        "subscriptionsv2/tokens/tok": {
          subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
          latestOrderId: "GPA.1-2-3..4",
          acknowledgementState: "ACKNOWLEDGEMENT_STATE_PENDING",
          lineItems: [{ productId: "premium_monthly", expiryTime: "2026-12-10T00:00:00Z" }],
        },
        ":acknowledge": {},
      },
      seen,
    ),
  );
  const sub = await play.subscription("premium_monthly", "tok", NOW);
  assert.deepEqual(sub, { active: true, expiresAt: Date.UTC(2026, 11, 10), owner: "sub:GPA.1-2-3" });
  assert.ok(seen.some((s) => s.startsWith("POST") && s.endsWith("premium_monthly/tokens/tok:acknowledge")));
  assert.ok(seen.some((s) => s.includes("/applications/com.example/")));
});

test("expired, other products and unknown tokens aren't Premium", async () => {
  const play = new Play(
    await serviceAccount(),
    "com.example",
    google(
      {
        "tokens/old": {
          subscriptionState: "SUBSCRIPTION_STATE_EXPIRED",
          latestOrderId: "GPA.9",
          lineItems: [{ productId: "premium_monthly", expiryTime: "2026-10-01T00:00:00Z" }],
        },
        "tokens/other": {
          subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
          latestOrderId: "GPA.8",
          lineItems: [{ productId: "something_else", expiryTime: "2027-01-01T00:00:00Z" }],
        },
      },
      [],
    ),
  );
  assert.equal((await play.subscription("premium_monthly", "old", NOW))?.active, false);
  assert.equal(await play.subscription("premium_monthly", "other", NOW), null);
  assert.equal(await play.subscription("premium_monthly", "nope", NOW), null);
});

test("a paid top-up is consumed; an unpaid one isn't counted", async () => {
  const seen: string[] = [];
  const play = new Play(
    await serviceAccount(),
    "com.example",
    google({ "video_5h/tokens/paid": { purchaseState: 0 }, "video_5h/tokens/pending": { purchaseState: 2 }, ":consume": {} }, seen),
  );
  assert.equal(await play.consumePurchase("video_5h", "paid"), true);
  assert.ok(seen.some((s) => s.startsWith("POST") && s.endsWith("video_5h/tokens/paid:consume")));
  assert.equal(await play.consumePurchase("video_5h", "pending"), false);
});

test("renewals share the first order's ID", () => {
  assert.equal(firstOrder("GPA.3312-5678-9012-34567..12"), "GPA.3312-5678-9012-34567");
  assert.equal(firstOrder("GPA.3312-5678-9012-34567"), "GPA.3312-5678-9012-34567");
});
