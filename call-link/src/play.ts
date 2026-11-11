// Checks Google Play purchases with the Google Play Developer API, so a modified app can't claim Premium or a top-up
// it didn't buy. Needs a Google Cloud service account that Play Console has given access to (README.md: "Premium").

const SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";

interface ServiceAccount {
  client_email: string;
  private_key: string;
  token_uri?: string;
}

export interface Subscription {
  /** Paid up (or in its grace period) until expiresAt. */
  active: boolean;
  expiresAt: number;
  /**
   * The same for every renewal of this subscription (its first order's ID), so time is counted per subscription and
   * sharing a purchase token doesn't multiply the allowance.
   */
  owner: string;
}

export class Play {
  private accessToken?: { value: string; expiresAt: number };

  private readonly serviceAccountJson: string;
  private readonly packageName: string;
  private readonly fetcher: typeof fetch;

  constructor(serviceAccountJson: string, packageName: string, fetcher: typeof fetch = fetch) {
    this.serviceAccountJson = serviceAccountJson;
    this.packageName = packageName;
    this.fetcher = fetcher;
  }

  async subscription(productId: string, token: string, now = Date.now()): Promise<Subscription | null> {
    const response = await this.call("GET", `purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`);
    if (response.status === 400 || response.status === 404 || response.status === 410) return null;
    if (!response.ok) throw new Error(`Play subscription check: ${response.status}`);
    const body = (await response.json()) as {
      subscriptionState?: string;
      latestOrderId?: string;
      acknowledgementState?: string;
      lineItems?: { productId?: string; expiryTime?: string }[];
    };
    const item = body.lineItems?.find((line) => line.productId === productId);
    if (!item || !body.latestOrderId) return null;
    const expiresAt = Date.parse(item.expiryTime ?? "") || 0;
    // A cancelled subscription still has what it paid for until it expires
    const paid = ["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", "SUBSCRIPTION_STATE_CANCELED"];
    const active = paid.includes(body.subscriptionState ?? "") && expiresAt > now;
    if (active && body.acknowledgementState === "ACKNOWLEDGEMENT_STATE_PENDING") {
      // Play refunds a purchase nobody acknowledges within 3 days; the app does it too, this is in case it can't
      await this.call(
        "POST",
        `purchases/subscriptions/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(token)}:acknowledge`,
      ).catch(() => undefined);
    }
    return { active, expiresAt, owner: `sub:${firstOrder(body.latestOrderId)}` };
  }

  /** Whether this one-time purchase was paid for (and not refunded); consumes it so it can be bought again. */
  async consumePurchase(productId: string, token: string): Promise<boolean> {
    const path = `purchases/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(token)}`;
    const response = await this.call("GET", path);
    if (response.status === 400 || response.status === 404 || response.status === 410) return false;
    if (!response.ok) throw new Error(`Play purchase check: ${response.status}`);
    const body = (await response.json()) as { purchaseState?: number };
    if (body.purchaseState !== 0) return false;
    await this.call("POST", `${path}:consume`).catch(() => undefined);
    return true;
  }

  private async call(method: string, path: string): Promise<Response> {
    return this.fetcher(`${API}/${encodeURIComponent(this.packageName)}/${path}`, {
      method,
      headers: { Authorization: `Bearer ${await this.token()}` },
    });
  }

  private async token(now = Date.now()): Promise<string> {
    if (this.accessToken && this.accessToken.expiresAt > now + 60_000) return this.accessToken.value;
    const account = JSON.parse(this.serviceAccountJson) as ServiceAccount;
    const tokenUri = account.token_uri ?? "https://oauth2.googleapis.com/token";
    const seconds = Math.floor(now / 1000);
    const assertion = await signJwt(account.private_key, {
      iss: account.client_email,
      scope: SCOPE,
      aud: tokenUri,
      iat: seconds,
      exp: seconds + 3600,
    });
    const response = await this.fetcher(tokenUri, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
    });
    if (!response.ok) throw new Error(`Google sign-in: ${response.status}`);
    const body = (await response.json()) as { access_token: string; expires_in: number };
    this.accessToken = { value: body.access_token, expiresAt: now + body.expires_in * 1000 };
    return body.access_token;
  }
}

/** What Google Play last confirmed about a subscription, kept by the worker. */
export interface CheckedSubscription {
  owner: string;
  expiresAt: number;
  checkedAt: number;
}

/** How long a subscription Google Play confirmed is trusted before asking again. */
export const SUBSCRIPTION_CHECK_MS = 10 * 60 * 1000;

/**
 * Whose Premium a subscription token is now (null: not Premium), and what to remember about it (undefined: keep what
 * is remembered, null: forget it). When Google Play can't be asked, only a subscription it confirmed before and that
 * is still paid for keeps Premium: never anything else, or a made-up token sent while Play fails (or is made to fail)
 * would get it.
 */
export async function premiumOwner(
  known: CheckedSubscription | undefined,
  ask: () => Promise<Subscription | null>,
  now: number,
): Promise<{ owner: string | null; remember?: CheckedSubscription | null }> {
  const stillPaid = known !== undefined && known.expiresAt > now;
  if (stillPaid && now - known.checkedAt < SUBSCRIPTION_CHECK_MS) return { owner: known.owner };
  let subscription: Subscription | null;
  try {
    subscription = await ask();
  } catch (e) {
    console.error("Premium check", e);
    return { owner: stillPaid ? known.owner : null };
  }
  if (!subscription?.active) return { owner: null, remember: known ? null : undefined };
  return {
    owner: subscription.owner,
    remember: { owner: subscription.owner, expiresAt: subscription.expiresAt, checkedAt: now },
  };
}

/** "GPA.1234-5678-9012-34567..3" is the 4th renewal of "GPA.1234-5678-9012-34567". */
export const firstOrder = (orderId: string): string => orderId.replace(/\.\.\d+$/, "");

async function signJwt(privateKeyPem: string, claims: object): Promise<string> {
  const der = Uint8Array.from(
    atob(privateKeyPem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "")),
    (c) => c.charCodeAt(0),
  );
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, [
    "sign",
  ]);
  const unsigned = `${base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }))}.${base64url(JSON.stringify(claims))}`;
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  return `${unsigned}.${base64url(new Uint8Array(signature))}`;
}

function base64url(data: string | Uint8Array): string {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : data;
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
