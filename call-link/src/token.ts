// LiveKit access tokens: JWTs signed with the project's API secret (HS256), made with the platform's Web Crypto so the
// worker needs no libraries. https://docs.livekit.io/home/get-started/authentication/

/** What a token lets its holder do; see LiveKit's VideoGrant. */
export interface VideoGrant {
  room?: string;
  roomJoin?: boolean;
  roomCreate?: boolean;
  roomList?: boolean;
  canPublish?: boolean;
  canSubscribe?: boolean;
  canPublishData?: boolean;
}

export interface TokenOptions {
  apiKey: string;
  apiSecret: string;
  /** Who the holder is in the room; unique per participant. */
  identity?: string;
  name?: string;
  ttlSeconds: number;
  grant: VideoGrant;
  /** Seconds since the epoch; for tests. */
  now?: number;
}

export async function createToken(options: TokenOptions): Promise<string> {
  const now = options.now ?? Math.floor(Date.now() / 1000);
  const header = { alg: "HS256", typ: "JWT" };
  const claims: Record<string, unknown> = {
    iss: options.apiKey,
    nbf: now - 10,
    exp: now + options.ttlSeconds,
    video: options.grant,
  };
  if (options.identity) {
    claims.sub = options.identity;
    claims.jti = options.identity;
  }
  if (options.name) claims.name = options.name;
  const unsigned = `${base64url(json(header))}.${base64url(json(claims))}`;
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(options.apiSecret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(unsigned));
  return `${unsigned}.${base64url(new Uint8Array(signature))}`;
}

/** A random name nobody can guess (128 bits): knowing it is what lets someone into the call. */
export function randomId(bytes = 16): string {
  return base64url(crypto.getRandomValues(new Uint8Array(bytes)));
}

function json(value: unknown): Uint8Array {
  return new TextEncoder().encode(JSON.stringify(value));
}

export function base64url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
