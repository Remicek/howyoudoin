// What a call link carries: one secret after the #, the call's key. The call's name on the server is derived from it
// (a hash), so the link is short and the server, which knows the name, still can't get the key.
//
//   invite:  https://call.howyoudoin.app/#<key>
//   the app: https://call.howyoudoin.app/?host=1#t=<token>&k=<key>&v=1
//
// The app derives the name the same way (HttpLinkCalls.roomOf).

export const KEY_BYTES = 16;
export const ROOM_CHARS = 22;

export function base64url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** The base64url key from the link: 16 bytes, or null for anything else. */
export function decodeKey(key: string): Uint8Array<ArrayBuffer> | null {
  try {
    const bytes = Uint8Array.from(atob(key.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));
    return bytes.length === KEY_BYTES ? bytes : null;
  } catch {
    return null;
  }
}

/** The call's name on the server: the first 22 base64url characters of the key's SHA-256 (128 bits). */
export async function roomOf(key: Uint8Array<ArrayBuffer>): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", key));
  return base64url(digest).slice(0, ROOM_CHARS);
}

/** The fragment's parts: the bare key of an invite, or the app's t=, k= and v=. */
export function fragmentParams(hash: string): URLSearchParams {
  const raw = hash.startsWith("#") ? hash.slice(1) : hash;
  return raw.includes("=") ? new URLSearchParams(raw) : new URLSearchParams({ k: raw });
}
