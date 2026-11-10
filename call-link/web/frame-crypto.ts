// The end-to-end encryption of one audio or video frame.
//
// An encrypted frame is: the frame's first bytes as they were | AES-GCM ciphertext and tag | 12-byte IV | 1 byte
// saying how many bytes were left as they were. Those few bytes (Opus's table of contents; VP8's frame header) stay
// readable because the browser's packetiser and the SFU look at them to tell key frames from others; they're still
// authenticated, as additional data. Video is always VP8 (the call page sets it): other codecs need other rules.

export interface EncodedFrame {
  data: ArrayBuffer;
  /** Video only: "key", "delta" or "empty". */
  type?: string;
}

export const IV_BYTES = 12;

/** Opus: its 1-byte table of contents. VP8: the 10-byte key frame header, or 3 bytes of a delta frame. */
export function clearBytes(frame: EncodedFrame): number {
  if (frame.type === undefined) return 1;
  return frame.type === "key" ? 10 : 3;
}

export async function encrypt(key: CryptoKey, data: Uint8Array<ArrayBuffer>, clear: number): Promise<ArrayBuffer> {
  clear = Math.min(clear, data.byteLength);
  const iv = crypto.getRandomValues(new Uint8Array(IV_BYTES));
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

export async function decrypt(key: CryptoKey, data: Uint8Array<ArrayBuffer>): Promise<ArrayBuffer> {
  const clear = data[data.byteLength - 1];
  const ivStart = data.byteLength - 1 - IV_BYTES;
  if (ivStart < clear) throw new Error("Too short");
  const header = data.subarray(0, clear);
  const plain = new Uint8Array(
    await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: data.subarray(ivStart, ivStart + IV_BYTES), additionalData: header },
      key,
      data.subarray(clear, ivStart),
    ),
  );
  const out = new Uint8Array(clear + plain.byteLength);
  out.set(header, 0);
  out.set(plain, clear);
  return out.buffer;
}

