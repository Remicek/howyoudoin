// Calls through a link. HowYouDoin can't answer or place calls on WhatsApp, Messenger and the other networks, so it
// offers a separate call instead: the app asks this worker for a call, sends the link in the chat, and the other
// person joins from their browser. Audio and video go through Cloudflare Realtime SFU, end-to-end encrypted by the
// call page with a key that stays in the link's #fragment (browsers never send it to a server).
//
//   POST /api/calls             -> { joinUrl, hostUrl, … }   the app starts a call (naming it: web/link.ts)
//   GET  /api/calls/<room>/ws   WebSocket to the call (CallRoom): who's in it, and setting up the media
//   POST /api/usage             -> the minutes left this month (plans.ts)
//
// The app's requests carry its install ID and, with Premium, its Google Play subscription token. Only the person who
// starts a call uses minutes (CallAccount); the person joining from the link never does.
//
// Everything else is the call page (public/), served for / (the link's fragment says which call).

import { DurableObject } from "cloudflare:workers";
import { randomId } from "./random.ts";
import {
  admit,
  announce,
  canPublish,
  canSubscribe,
  disconnect,
  hangUp,
  emptyMeter,
  enforce,
  limitsView,
  meter,
  refreshLimits,
  newRoom,
  takeCharge,
  type CallLimits,
  nextDeadline,
  peerView,
  publish,
  type Role,
  type Room,
  takeSession,
  tick,
  isTrackName,
  MAX_CALL_MS,
} from "./room.ts";
import { iceServers, Sfu, type SessionDescription } from "./sfu.ts";
import { allowance, charge, current, PREMIUM_PRODUCT, type Allowance, type Plan, type Usage } from "./plans.ts";
import { Play, premiumOwner, type CheckedSubscription } from "./play.ts";

export interface Env {
  ASSETS: Fetcher;
  CALLS: DurableObjectNamespace<CallRoom>;
  ACCOUNTS: DurableObjectNamespace<CallAccount>;
  /**
   * A Google Cloud service account's JSON key, with access to the app in Play Console: to check Premium. Without it
   * everyone has the free plan.
   */
  PLAY_SERVICE_ACCOUNT?: string;
  /** The app's package on Google Play. */
  PLAY_PACKAGE: string;
  /** The Realtime SFU app (Realtime → Serverless SFU in the Cloudflare dashboard). */
  SFU_APP_ID: string;
  SFU_APP_SECRET: string;
  /** Optional: a Realtime TURN key, for networks that block direct WebRTC. */
  TURN_KEY_ID?: string;
  TURN_KEY_TOKEN?: string;
  /** Per install (and address): how often one app may start calls or ask for its minutes. */
  CREATE_LIMIT?: RateLimit;
  /** Per address: a ceiling over everything behind it, for someone making up install IDs. */
  IP_LIMIT?: RateLimit;
}

const ROOM_NAME = /^[A-Za-z0-9_-]{22}$/;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    try {
      if (url.pathname === "/api/calls" && request.method === "POST") {
        return await createCall(request, env, url.origin);
      }
      if (url.pathname === "/api/usage" && request.method === "POST") return await usage(request, env);
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

/** What the app sends with each request. */
interface AppRequest {
  /** A random ID the app made when it was installed. */
  install?: string;
  /** The Google Play purchase token of its Premium subscription, if it has one. */
  subscription?: string;
  /** Starting a video call (else audio). */
  video?: boolean;
  /** The call's name, derived from its key (web/link.ts), when starting a call. */
  room?: string;
}

const INSTALL_ID = /^[A-Za-z0-9_-]{16,64}$/;
const PURCHASE_TOKEN = /^[A-Za-z0-9._:-]{10,500}$/;

class BadRequest extends Error {}

async function readApp(request: Request): Promise<AppRequest & { install: string }> {
  const body = (await request.json().catch(() => null)) as AppRequest | null;
  if (!body || typeof body.install !== "string" || !INSTALL_ID.test(body.install)) {
    throw new BadRequest("This app is too old for calls through a link; update it");
  }
  const token = body.subscription;
  if (token !== undefined && (typeof token !== "string" || !PURCHASE_TOKEN.test(token))) {
    throw new BadRequest("Not a purchase token");
  }
  if (body.room !== undefined && (typeof body.room !== "string" || !ROOM_NAME.test(body.room))) {
    throw new BadRequest("Not a call name");
  }
  return body as AppRequest & { install: string };
}

/**
 * Too many requests of this kind: from this install at this address (so one app can't run up the bill), or from the
 * address as a whole (so made-up install IDs don't get around that). Phones share addresses behind carrier NAT, which
 * is why the per-address ceiling is the loose one.
 */
async function rateLimited(request: Request, env: Env, kind: string, install: string): Promise<boolean> {
  const ip = request.headers.get("CF-Connecting-IP") ?? "local";
  if (env.CREATE_LIMIT && !(await env.CREATE_LIMIT.limit({ key: `${kind}:${ip}:${install}` })).success) return true;
  return Boolean(env.IP_LIMIT && !(await env.IP_LIMIT.limit({ key: `${kind}:${ip}` })).success);
}

/** Whose minutes a request uses, and on which plan: the subscription's while Premium is paid, else the install's. */
async function whoIs(app: AppRequest & { install: string }, env: Env): Promise<{ plan: Plan; owner: string }> {
  const free = { plan: "free" as const, owner: `install:${app.install}` };
  if (!app.subscription || !env.PLAY_SERVICE_ACCOUNT) return free;
  const now = Date.now();
  const cache = account(env, `subtoken:${await sha256(app.subscription)}`);
  const token = app.subscription;
  const { owner, remember } = await premiumOwner(
    await cache.checkedSubscription(),
    () => play(env).subscription(PREMIUM_PRODUCT, token, now),
    now,
  );
  if (remember === null) await cache.forgetSubscription();
  else if (remember) await cache.rememberSubscription(remember);
  return owner ? { plan: "premium", owner } : free;
}

async function sha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

let playClient: Play | undefined;
const play = (env: Env) => (playClient ??= new Play(env.PLAY_SERVICE_ACCOUNT!, env.PLAY_PACKAGE));

const account = (env: Env, owner: string) => env.ACCOUNTS.get(env.ACCOUNTS.idFromName(owner));

/** The minutes left on the plan's account. */
const minutesLeft = (env: Env, plan: Plan, owner: string): Promise<Allowance> =>
  account(env, owner).allowance(plan);

async function createCall(request: Request, env: Env, origin: string): Promise<Response> {
  if (!env.SFU_APP_ID || !env.SFU_APP_SECRET) return error(503, "Calls through a link aren't set up on this server");
  let app;
  try {
    app = await readApp(request);
  } catch (e) {
    if (e instanceof BadRequest) return error(400, e.message);
    throw e;
  }
  if (await rateLimited(request, env, "call", app.install)) return error(429, "Too many calls; try again in a minute");
  if (!app.room) return error(400, "This app is too old for calls through a link; update it");
  const { plan, owner } = await whoIs(app, env);
  const left = await minutesLeft(env, plan, owner);
  const videoAllowed = left.videoLeftMs > 0;
  if (left.audioLeftMs <= 0 && !(app.video && videoAllowed)) {
    return Response.json({ error: "No call minutes left this month", code: "no_minutes", ...left }, { status: 402 });
  }
  const limits: CallLimits = { plan, owner, videoLeftMs: left.videoLeftMs, audioLeftMs: left.audioLeftMs };
  const hostToken = randomId();
  // The app names the call after its key (a hash of it, so the name says nothing about the key): a name in use is
  // someone else's call, never taken over
  if (!(await env.CALLS.get(env.CALLS.idFromName(app.room)).create(hostToken, limits))) {
    return error(409, "This call already exists");
  }
  const joinUrl = `${origin}/`;
  return Response.json({
    joinUrl,
    // The token travels in the fragment, so it isn't logged anywhere. The app adds the encryption key (k) to both
    // links itself, so this worker never sees it
    hostUrl: `${joinUrl}?host=1#t=${hostToken}`,
    plan,
    videoAllowed,
  });
}

async function usage(request: Request, env: Env): Promise<Response> {
  try {
    const app = await readApp(request);
    if (await rateLimited(request, env, "usage", app.install)) {
      return error(429, "Too many requests; try again in a minute");
    }
    const { plan, owner } = await whoIs(app, env);
    return Response.json(await minutesLeft(env, plan, owner));
  } catch (e) {
    if (e instanceof BadRequest) return error(400, e.message);
    throw e;
  }
}

function error(status: number, message: string): Response {
  return Response.json({ error: message }, { status });
}

/**
 * One person's call minutes this month (see plans.ts), keyed "install:<id>" or "sub:<first order>". Also used keyed
 * "subtoken:<hash>" to remember a subscription Google Play confirmed.
 */
export class CallAccount extends DurableObject<Env> {
  private async load(): Promise<Usage> {
    return current(await this.ctx.storage.get<Usage>("usage"), Date.now());
  }

  async allowance(plan: Plan): Promise<Allowance> {
    return allowance(await this.load(), plan, Date.now());
  }

  /** Counts a call's time; returns what's left. */
  async charge(plan: Plan, videoMs: number, audioMs: number): Promise<Allowance> {
    const after = charge(await this.load(), videoMs, audioMs);
    await this.ctx.storage.put("usage", after);
    return allowance(after, plan, Date.now());
  }

  /** Keyed "subtoken:<hash of a purchase token>": what Google Play last said about that subscription. */
  async checkedSubscription(): Promise<CheckedSubscription | undefined> {
    return this.ctx.storage.get<CheckedSubscription>("subscription");
  }

  async rememberSubscription(checked: CheckedSubscription): Promise<void> {
    await this.ctx.storage.put("subscription", checked);
  }

  async forgetSubscription(): Promise<void> {
    await this.ctx.storage.delete("subscription");
  }
}

/** What a connection is, kept on its WebSocket so it survives the Durable Object sleeping between messages. */
interface Attachment {
  role?: Role;
  gen?: number;
  /** Messages in the current minute, and when it started: a page that floods is cut off. */
  count?: number;
  since?: number;
}

/** Most messages one connection may send a minute: a reconnection takes about ten. */
const MESSAGES_PER_MINUTE = 60;

/** Messages from the call page; each with an id that its answer repeats. */
type Request_ =
  | { type: "hello"; token?: string; secret?: string }
  | {
      type: "publish";
      offer: SessionDescription;
      tracks: { mid: string; trackName: string }[];
    }
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

  /** Makes the call; false when this name is already a call (ended ones are forgotten, so a name frees up). */
  async create(hostToken: string, limits?: CallLimits): Promise<boolean> {
    if (await this.load()) return false;
    this.room = newRoom(hostToken, Date.now(), limits);
    await this.save(this.room);
    return true;
  }

  async fetch(request: Request): Promise<Response> {
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({} satisfies Attachment);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, data: string | ArrayBuffer): Promise<void> {
    if (!this.withinRate(ws)) return ws.close(4008, "Too many messages");
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

  /** Counts this connection's messages in the current minute. */
  private withinRate(ws: WebSocket): boolean {
    const attachment = ws.deserializeAttachment() as Attachment;
    const now = Date.now();
    if (attachment.since === undefined || now - attachment.since >= 60_000) {
      attachment.since = now;
      attachment.count = 0;
    }
    attachment.count = (attachment.count ?? 0) + 1;
    ws.serializeAttachment(attachment);
    return attachment.count <= MESSAGES_PER_MINUTE;
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
    meter(room, Date.now());
    const ticked = tick(room, Date.now());
    // Mostly the minute's charge: the pages only hear about it when something they show changed
    await this.changed(room, undefined, !ticked);
  }

  private async handle(ws: WebSocket, message: Request_): Promise<object | undefined> {
    const room = await this.load();
    if (!room || room.phase === "ended") {
      send(ws, { type: "ended", reason: room?.endReason });
      ws.close(1000, "ended");
      return undefined;
    }
    // The time until now counts as the call was, before this message changes it
    meter(room, Date.now());
    if (message.type === "hello") return this.hello(ws, room, message);

    const { role, gen } = ws.deserializeAttachment() as Attachment;
    if (!role || room[role].gen !== gen) throw new Refusal("not in the call");
    const me = room[role];

    switch (message.type) {
      case "publish": {
        const names = message.tracks.map((t) => t.trackName);
        if (!names.every(isTrackName)) throw new Refusal("unknown track");
        if (!room.meter.videoAllowed && names.some((n) => n !== "audio")) throw new Refusal("no video minutes");
        // Everything is checked before a session is made, so a refused request costs nothing
        if (!canPublish(room, role, names)) throw new Refusal("already published");
        const session = me.pubSession ?? (await this.newSession(room, role));
        if (!publish(room, role, session, names)) throw new Refusal("already published");
        const response = await this.sfu.newTracks(session, {
          sessionDescription: message.offer,
          tracks: message.tracks.map((t) => ({
            location: "local",
            mid: t.mid,
            trackName: t.trackName,
          })),
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
        if (message.fresh || !me.subSession) me.subSession = await this.newSession(room, role);
        const response = await this.sfu.newTracks(me.subSession, {
          tracks: message.tracks.map((name) => ({
            location: "remote",
            sessionId: message.session,
            trackName: name,
          })),
        });
        await this.save(room);
        return {
          offer: response.sessionDescription,
          tracks: (response.tracks ?? []).map((t) => ({
            mid: t.mid,
            trackName: t.trackName,
          })),
        };
      }
      case "answer":
        if (!me.subSession) throw new Refusal("nothing to answer");
        await this.sfu.renegotiate(me.subSession, message.answer);
        return {};
      case "camera":
        if (message.on && !room.meter.videoAllowed) throw new Refusal("no video minutes");
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

  /** An SFU session for this person, within their share of them. */
  private async newSession(room: Room, role: Role): Promise<string> {
    if (!takeSession(room, role)) throw new Refusal("too many sessions");
    await this.save(room);
    return this.sfu.newSession();
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
    const counted = ws.deserializeAttachment() as Attachment;
    ws.serializeAttachment({ role, gen, count: counted.count, since: counted.since } satisfies Attachment);
    const ttl = Math.ceil((room.createdAt + MAX_CALL_MS - Date.now()) / 1000);
    const servers = await iceServers(this.env.TURN_KEY_ID, this.env.TURN_KEY_TOKEN, ttl);
    // The others hear about it; this page learns it all from the welcome
    await this.changed(room, ws);
    return {
      type: "welcome",
      role,
      secret: admission.secret,
      iceServers: servers,
      peer: peerView(room, role, Date.now()),
    };
  }

  private async dropped(ws: WebSocket): Promise<void> {
    const { role, gen } = ws.deserializeAttachment() as Attachment;
    if (!role || gen === undefined) return;
    const room = await this.load();
    if (!room) return;
    meter(room, Date.now());
    if (disconnect(room, role, gen, Date.now())) await this.changed(room);
  }

  /**
   * Applies the limits, charges the host's account for the time so far, saves the call and tells both people what
   * changed; once it has ended, closes it and forgets it.
   */
  private async changed(room: Room, except?: WebSocket, quietIfSame = false): Promise<void> {
    const now = Date.now();
    meter(room, now);
    const before = limitsView(room, now);
    // Video stopped by the limits: both pages hear it, even the one whose message this was
    const enforced = enforce(room, now);
    if (enforced) except = undefined;
    await this.charge(room);
    const after = limitsView(room, now);
    const same =
      !enforced && room.phase !== "ended" && before.endsAt === after.endsAt && before.videoEndsAt === after.videoEndsAt;
    for (const ws of quietIfSame && same ? [] : this.ctx.getWebSockets()) {
      const { role } = ws.deserializeAttachment() as Attachment;
      if (!role || ws === except) continue;
      if (room.phase === "ended") {
        send(ws, { type: "ended", reason: room.endReason });
        ws.close(1000, "ended");
      } else {
        send(ws, { type: "peer", peer: peerView(room, role, now) });
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

  private async charge(room: Room): Promise<void> {
    if (!room.limits) return;
    const due = takeCharge(room);
    if (due.videoMs <= 0 && due.audioMs <= 0) return;
    const { plan, owner } = room.limits;
    let left;
    try {
      left = await account(this.env, owner).charge(plan, due.videoMs, due.audioMs);
    } catch (e) {
      // Counted again with the next charge
      room.meter.chargedVideoMs -= due.videoMs;
      room.meter.chargedAudioMs -= due.audioMs;
      console.error("Charging the call", e);
      return;
    }
    // What the account has left now, after any other call of the same person's too
    refreshLimits(room, { videoLeftMs: left.videoLeftMs, audioLeftMs: left.audioLeftMs });
  }

  private async save(room: Room): Promise<void> {
    await this.ctx.storage.put("room", room);
    await this.ctx.storage.setAlarm(nextDeadline(room, Date.now()));
  }

  private async load(): Promise<Room | null> {
    if (this.room === undefined) {
      this.room = (await this.ctx.storage.get<Room>("room")) ?? null;
      // A call saved before plans existed
      if (this.room) this.room.meter ??= emptyMeter(this.room.limits);
    }
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
