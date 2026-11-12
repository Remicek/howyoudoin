// The end-to-end encryption of one audio or video frame.
//
// An encrypted frame is: the frame's first bytes as they were | AES-GCM ciphertext and tag | 12-byte IV | 1 byte
// saying how many bytes were left as they were. Those few bytes (Opus's table of contents; VP8's frame header) stay
// readable because the browser's packetiser and the SFU look at them to tell key frames from others; they're still
// authenticated, as additional data. Video is always VP8 (the call page sets it): other codecs need other rules.
//
// Each direction has its own key, derived from the link's key with the sender's role, so nothing a relay could do
// turns one person's frames into the other's. The IV is a random 4-byte prefix (new for every sender stream) and an
// 8-byte frame counter: a receiver drops a frame whose counter doesn't move forward, so frames can't be replayed.

export interface EncodedFrame {
  data: ArrayBuffer;
  /** Video only: "key", "delta" or "empty". */
  type?: string;
}

export type Sender = "host" | "guest";

export const IV_BYTES = 12;
const PREFIX_BYTES = 4;

/** The key for one direction: HKDF of the link's key with the sender's role. */
export async function directionKey(linkKey: Uint8Array<ArrayBuffer>, sender: Sender): Promise<CryptoKey> {
  const base = await crypto.subtle.importKey("raw", linkKey, "HKDF", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt: new Uint8Array(0), info: new TextEncoder().encode(`howyoudoin-call/${sender}`) },
    base,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

/** Opus: its 1-byte table of contents. VP8: the 10-byte key frame header, or 3 bytes of a delta frame. */
export function clearBytes(frame: EncodedFrame): number {
  if (frame.type === undefined) return 1;
  return frame.type === "key" ? 10 : 3;
}

/** Makes the IVs of one sender stream: a random prefix, then a count. */
export class IvCounter {
  private readonly prefix = crypto.getRandomValues(new Uint8Array(PREFIX_BYTES));
  private count = 0n;

  next(): Uint8Array<ArrayBuffer> {
    const iv = new Uint8Array(IV_BYTES);
    iv.set(this.prefix, 0);
    new DataView(iv.buffer).setBigUint64(PREFIX_BYTES, this.count++);
    return iv;
  }
}

/** Drops frames whose count doesn't go forward within the same prefix (a replay); a new prefix starts over. */
export class ReplayCheck {
  private prefix?: string;
  private last = -1n;

  accept(iv: Uint8Array): boolean {
    const prefix = Array.from(iv.subarray(0, PREFIX_BYTES)).join(",");
    const count = new DataView(iv.buffer, iv.byteOffset + PREFIX_BYTES, 8).getBigUint64(0);
    if (prefix !== this.prefix) {
      this.prefix = prefix;
      this.last = count;
      return true;
    }
    if (count <= this.last) return false;
    this.last = count;
    return true;
  }
}

export async function encrypt(
  key: CryptoKey,
  data: Uint8Array<ArrayBuffer>,
  clear: number,
  iv: Uint8Array<ArrayBuffer>,
): Promise<ArrayBuffer> {
  clear = Math.min(clear, data.byteLength);
  const header = data.subarray(0, clear);
  const sealed = new Uint8Array(
    await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: header }, key, data.subarray(clear)),
  );
  const out = new Uint8Array(clear + sealed.byteLength + IV_BYTES + 1);
  out.set(header, 0);
  out.set(sealed, clear);
  out.set(iv, clear + sealed.byteLength);
  out[out.byteLength - 1] = clear;
  return out.buffer;
}

/** The IV an encrypted frame carries, or null for one too short to be a frame. */
export function ivOf(data: Uint8Array<ArrayBuffer>): Uint8Array<ArrayBuffer> | null {
  const clear = data[data.byteLength - 1];
  const ivStart = data.byteLength - 1 - IV_BYTES;
  if (ivStart < clear) return null;
  return data.subarray(ivStart, ivStart + IV_BYTES);
}

export async function decrypt(key: CryptoKey, data: Uint8Array<ArrayBuffer>): Promise<ArrayBuffer> {
  const iv = ivOf(data);
  if (!iv) throw new Error("Too short");
  const clear = data[data.byteLength - 1];
  const ivStart = data.byteLength - 1 - IV_BYTES;
  const header = data.subarray(0, clear);
  const plain = new Uint8Array(
    await crypto.subtle.decrypt({ name: "AES-GCM", iv, additionalData: header }, key, data.subarray(clear, ivStart)),
  );
  const out = new Uint8Array(clear + plain.byteLength);
  out.set(header, 0);
  out.set(plain, clear);
  return out.buffer;
}

export interface TransformOptions {
  op: "encrypt" | "decrypt";
  /** The raw key from the link. */
  key: Uint8Array<ArrayBuffer>;
  /** Who encrypts these frames: this page (encrypt) or the other person (decrypt). */
  sender: Sender;
}

/** Encrypts or decrypts a stream of frames; one per sender or receiver. */
export function frameTransform({ op, key, sender }: TransformOptions): TransformStream<EncodedFrame, EncodedFrame> {
  const cryptoKey = directionKey(key, sender);
  const ivs = new IvCounter();
  const replays = new ReplayCheck();
  return new TransformStream({
    async transform(frame, controller) {
      const data = new Uint8Array(frame.data);
      if (data.byteLength === 0) return controller.enqueue(frame);
      try {
        if (op === "encrypt") {
          frame.data = await encrypt(await cryptoKey, data, clearBytes(frame), ivs.next());
        } else {
          const iv = ivOf(data);
          if (!iv || !replays.accept(iv)) return;
          frame.data = await decrypt(await cryptoKey, data);
        }
        controller.enqueue(frame);
      } catch {
        // A frame that can't be decrypted (a wrong key, or not encrypted) is dropped: nothing unencrypted is played
      }
    },
  });
}
