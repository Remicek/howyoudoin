// End-to-end encryption of every audio and video frame (frame-crypto.ts), run off the page's main thread. The key
// comes from the link's #fragment, so the SFU forwards frames it can't read.

import { frameTransform, type TransformOptions } from "./frame-crypto.ts";

interface TransformerEvent extends Event {
  transformer: { readable: ReadableStream; writable: WritableStream; options: TransformOptions };
}

// RTCRtpScriptTransform (Safari, Firefox, recent Chrome): the browser hands each sender or receiver to the worker
self.addEventListener("rtctransform", (event) => {
  const { readable, writable, options } = (event as TransformerEvent).transformer;
  readable.pipeThrough(frameTransform(options)).pipeTo(writable);
});

// Older Chrome: the page takes the encoded streams itself (createEncodedStreams) and posts them here
self.addEventListener("message", (event: MessageEvent) => {
  const { readable, writable, ...options } = event.data as TransformOptions & {
    readable: ReadableStream;
    writable: WritableStream;
  };
  readable.pipeThrough(frameTransform(options)).pipeTo(writable);
});
