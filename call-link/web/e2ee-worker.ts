// End-to-end encryption of every audio and video frame (frame-crypto.ts), run off the page's main thread. The key
// comes from the link's #fragment, so the SFU forwards frames it can't read.

import { clearBytes, decrypt, encrypt, type EncodedFrame } from "./frame-crypto.ts";

interface Options {
  op: "encrypt" | "decrypt";
  /** The raw 256-bit key. */
  key: Uint8Array<ArrayBuffer>;
}

function frameTransform({ op, key }: Options): TransformStream<EncodedFrame, EncodedFrame> {
  const cryptoKey = crypto.subtle.importKey("raw", key, "AES-GCM", false, ["encrypt", "decrypt"]);
  return new TransformStream({
    async transform(frame, controller) {
      const data = new Uint8Array(frame.data);
      if (data.byteLength === 0) return controller.enqueue(frame);
      try {
        frame.data = op === "encrypt" ? await encrypt(await cryptoKey, data, clearBytes(frame)) : await decrypt(await cryptoKey, data);
        controller.enqueue(frame);
      } catch {
        // A frame that can't be decrypted (a wrong key, or not encrypted) is dropped: nothing unencrypted is played
      }
    },
  });
}

interface TransformerEvent extends Event {
  transformer: { readable: ReadableStream; writable: WritableStream; options: Options };
}

// RTCRtpScriptTransform (Safari, Firefox, recent Chrome): the browser hands each sender or receiver to the worker
self.addEventListener("rtctransform", (event) => {
  const { readable, writable, options } = (event as TransformerEvent).transformer;
  readable.pipeThrough(frameTransform(options)).pipeTo(writable);
});

// Older Chrome: the page takes the encoded streams itself (createEncodedStreams) and posts them here
self.addEventListener("message", (event: MessageEvent) => {
  const { readable, writable, ...options } = event.data as Options & {
    readable: ReadableStream;
    writable: WritableStream;
  };
  readable.pipeThrough(frameTransform(options)).pipeTo(writable);
});
