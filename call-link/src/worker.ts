// Calls through a link. HowYouDoin can't answer or place calls on WhatsApp, Messenger and the other networks, so it
// offers a separate call instead: the app asks this worker for a call, sends the link in the chat, and the other
// person joins from their browser. Audio and video go through Cloudflare Realtime SFU, end-to-end encrypted by the
// call page with a key that stays in the link's #fragment (browsers never send it to a server).
//
//   POST /api/calls             -> { room, joinUrl, hostUrl }   the app starts a call
//   GET  /api/calls/<room>/ws   WebSocket to the call (CallRoom): who's in it, and setting up the media
//
// Everything else is the call page (public/), served for /call/<room>.

import { DurableObject } from "cloudflare:workers";
import { randomId } from "./random.ts";
import {
  admit,
  announce,
  canSubscribe,
  disconnect,
  hangUp,
  newRoom,
  nextDeadline,
  peerView,
  publish,
  type Role,
  type Room,
  tick,
  isTrackName,
  MAX_CALL_MS,
} from "./room.ts";
import { iceServers, Sfu, type SessionDescription } from "./sfu.ts";

export interface Env {
  ASSETS: Fetcher;
  CALLS: DurableObjectNamespace<CallRoom>;
  /** The Realtime SFU app (Realtime → Serverless SFU in the Cloudflare dashboard). */
  SFU_APP_ID: string;
  SFU_APP_SECRET: string;
  /** Optional: a Realtime TURN key, for networks that block direct WebRTC. */
  TURN_KEY_ID?: string;
  TURN_KEY_TOKEN?: string;
  CREATE_LIMIT?: RateLimit;
}

const ROOM_NAME = /^[A-Za-z0-9_-]{22}$/;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    try {
      if (url.pathname === "/api/calls" && request.method === "POST") {
        return await createCall(request, env, url.origin);
      }
      const ws = url.pathname.match(/^\/api\/calls\/([^/]+)\/ws$/);
      if (ws) {
        if (request.headers.get("Upgrade") !== "websocket") return error(426, "Expected a WebSocket");
        if (!ROOM_NAME.test(ws[1])) return error(404, "This call doesn't exist");
        return env.CALLS.get(env.CALLS.idFromName(ws[1])).fetch(request);
      }
      if (url.pathname.startsWith("/api/")) return error(404, "Not found");
      return env.ASSETS.fetch(request);
    } catch (e) {
      console.error(e);
      return error(502, "The call service isn't reachable");
    }
  },
} satisfies ExportedHandler<Env>;

async function createCall(request: Request, env: Env, origin: string): Promise<Response> {
  if (!env.SFU_APP_ID || !env.SFU_APP_SECRET) return error(503, "Calls through a link aren't set up on this server");
  const ip = request.headers.get("CF-Connecting-IP") ?? "local";
  if (env.CREATE_LIMIT && !(await env.CREATE_LIMIT.limit({ key: ip })).success) {
    return error(429, "Too many calls; try again in a minute");
  }
  const room = randomId();
  const hostToken = randomId();
  await env.CALLS.get(env.CALLS.idFromName(room)).create(hostToken);
  const joinUrl = `${origin}/call/${room}`;
  return Response.json({
    room,
    joinUrl,
    // The token travels in the fragment, so it isn't logged anywhere. The app adds the encryption key (k) to both
    // links itself, so this worker never sees it
    hostUrl: `${joinUrl}?host=1#t=${hostToken}`,
  });
}

function error(status: number, message: string): Response {
  return Response.json({ error: message }, { status });
}

/** What a connection is, kept on its WebSocket so it survives the Durable Object sleeping between messages. */
interface Attachment {
  role?: Role;
  gen?: number;
}

/** Messages from the call page; each with an id that its answer repeats. */
type Request_ =
  | { type: "hello"; token?: string; secret?: string }
  | { type: "publish"; offer: SessionDescription; tracks: { mid: string; trackName: string }[] }
  | { type: "announce" }
  | { type: "subscribe"; session: string; tracks: string[]; fresh: boolean }
  | { type: "answer"; answer: SessionDescription }
  | { type: "camera"; on: boolean }
  | { type: "bye" };

class Refusal extends Error {}

/**
 * One call. It holds the call's state (room.ts) and its two WebSocket connections, and makes the SFU requests for the
 * pages, which never see the SFU's secret. It keeps nothing once the call ends.
 */
export class CallRoom extends DurableObject<Env> {
  private readonly sfu = new Sfu(this.env.SFU_APP_ID, this.env.SFU_APP_SECRET);
  /**
   * The call, loaded once: messages can interleave while one waits for the SFU, so they all change this same object.
   * null once it's over (or never existed).
   */
  private room?: Room | null;

  async create(hostToken: string): Promise<void> {
    this.room = newRoom(hostToken, Date.now());
    await this.save(this.room);
  }

  async fetch(request: Request): Promise<Response> {
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({} satisfies Attachment);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, data: string | ArrayBuffer): Promise<void> {
    let message: Request_ & { id?: number };
    try {
      message = JSON.parse(typeof data === "string" ? data : new TextDecoder().decode(data));
    } catch {
      return ws.close(1003, "Not JSON");
    }
    const id = message.id;
    try {
      const reply = await this.handle(ws, message);
      if (reply !== undefined) send(ws, { id, ...reply });
    } catch (e) {
      if (!(e instanceof Refusal)) console.error(message.type, e);
      send(ws, { id, error: e instanceof Refusal ? e.message : "failed" });
    }
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    await this.dropped(ws);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    await this.dropped(ws);
  }

  async alarm(): Promise<void> {
    const room = await this.load();
    if (!room) return;
    tick(room, Date.now());
    await this.changed(room);
  }

  private async handle(ws: WebSocket, message: Request_): Promise<object | undefined> {
    const room = await this.load();
    if (!room || room.phase === "ended") {
      send(ws, { type: "ended" });
      ws.close(1000, "ended");
      return undefined;
    }
    if (message.type === "hello") return this.hello(ws, room, message);

    const { role, gen } = ws.deserializeAttachment() as Attachment;
    if (!role || room[role].gen !== gen) throw new Refusal("not in the call");
    const me = room[role];

    switch (message.type) {
      case "publish": {
        const names = message.tracks.map((t) => t.trackName);
        if (!names.every(isTrackName)) throw new Refusal("unknown track");
        const session = me.pubSession ?? (await this.sfu.newSession());
        if (!publish(room, role, session, names)) throw new Refusal("already published");
        const response = await this.sfu.newTracks(session, {
          sessionDescription: message.offer,
          tracks: message.tracks.map((t) => ({ location: "local", mid: t.mid, trackName: t.trackName })),
        });
        await this.save(room);
        return { answer: response.sessionDescription };
      }
      case "announce":
        announce(room, role);
        await this.changed(room);
        return {};
      case "subscribe": {
        if (!canSubscribe(room, role, message.session, message.tracks)) throw new Refusal("not available");
        if (message.fresh || !me.subSession) me.subSession = await this.sfu.newSession();
        const response = await this.sfu.newTracks(me.subSession, {
          tracks: message.tracks.map((name) => ({ location: "remote", sessionId: message.session, trackName: name })),
        });
        await this.save(room);
        return {
          offer: response.sessionDescription,
          tracks: (response.tracks ?? []).map((t) => ({ mid: t.mid, trackName: t.trackName })),
        };
      }
      case "answer":
        if (!me.subSession) throw new Refusal("nothing to answer");
        await this.sfu.renegotiate(me.subSession, message.answer);
        return {};
      case "camera":
        me.camera = Boolean(message.on);
        await this.changed(room);
        return {};
      case "bye":
        hangUp(room, role);
        await this.changed(room);
        return undefined;
      default:
        throw new Refusal("unknown message");
    }
  }

  private async hello(ws: WebSocket, room: Room, message: { token?: string; secret?: string }): Promise<object> {
    if ((ws.deserializeAttachment() as Attachment).role) throw new Refusal("already in the call");
    const admission = admit(room, { token: message.token, secret: message.secret }, () => randomId());
    if ("error" in admission) throw new Refusal(admission.error);
    const { role, gen } = admission;
    // A connection being replaced may not know it yet
    for (const old of this.ctx.getWebSockets()) {
      const attachment = old.deserializeAttachment() as Attachment;
      if (old !== ws && attachment.role === role) old.close(4000, "replaced");
    }
    ws.serializeAttachment({ role, gen } satisfies Attachment);
    const ttl = Math.ceil((room.createdAt + MAX_CALL_MS - Date.now()) / 1000);
    const servers = await iceServers(this.env.TURN_KEY_ID, this.env.TURN_KEY_TOKEN, ttl);
    // The others hear about it; this page learns it all from the welcome
    await this.changed(room, ws);
    return { type: "welcome", role, secret: admission.secret, iceServers: servers, peer: peerView(room, role) };
  }

  private async dropped(ws: WebSocket): Promise<void> {
    const { role, gen } = ws.deserializeAttachment() as Attachment;
    if (!role || gen === undefined) return;
    const room = await this.load();
    if (room && disconnect(room, role, gen, Date.now())) await this.changed(room);
  }

  /** Saves the call and tells both people what changed; once it has ended, closes it and forgets it. */
  private async changed(room: Room, except?: WebSocket): Promise<void> {
    for (const ws of this.ctx.getWebSockets()) {
      const { role } = ws.deserializeAttachment() as Attachment;
      if (!role || ws === except) continue;
      if (room.phase === "ended") {
        send(ws, { type: "ended" });
        ws.close(1000, "ended");
      } else {
        send(ws, { type: "peer", peer: peerView(room, role) });
      }
    }
    if (room.phase === "ended") {
      this.room = null;
      await this.ctx.storage.deleteAll();
      await this.ctx.storage.deleteAlarm();
    } else {
      await this.save(room);
    }
  }

  private async save(room: Room): Promise<void> {
    await this.ctx.storage.put("room", room);
    await this.ctx.storage.setAlarm(nextDeadline(room));
  }

  private async load(): Promise<Room | null> {
    if (this.room === undefined) this.room = (await this.ctx.storage.get<Room>("room")) ?? null;
    return this.room;
  }
}

function send(ws: WebSocket, message: object): void {
  try {
    ws.send(JSON.stringify(message));
  } catch {
    // Already closed
  }
}
