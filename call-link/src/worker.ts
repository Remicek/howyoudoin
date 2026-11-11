// Calls through a link. HowYouDoin can't answer or place calls on WhatsApp, Messenger and the other networks, so it
// offers a separate call instead: the app asks this worker for a call, sends the link in the chat, and the other
// person joins from their browser. Audio and video go through Cloudflare Realtime SFU, end-to-end encrypted by the
// call page with a key that stays in the link's #fragment (browsers never send it to a server).
//
//   POST /api/calls             -> { room, joinUrl, hostUrl, … }   the app starts a call (group: true in a group chat)
//   GET  /api/calls/<room>/ws   WebSocket to the call (CallRoom): who's in it, and setting up the media
//   POST /api/usage             -> the minutes left this month (plans.ts)
//   POST /api/topups            adds a bought top-up, checked with Google Play
//
// The app's requests carry its install ID and, with Premium, its Google Play subscription token. Only the person who
// starts a call uses minutes (CallAccount); the person joining from the link never does.
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
  emptyMeter,
  enforce,
  limitsView,
  meter,
  refreshLimits,
  newRoom,
  takeCharge,
  type CallLimits,
  callView,
  GROUP_SIZE,
  type MemberId,
  nextDeadline,
  publish,
  type Room,
  tick,
  isTrackName,
  MAX_CALL_MS,
} from "./room.ts";
import { iceServers, Sfu, type SessionDescription } from "./sfu.ts";
import {
  addTopUp,
  allowance,
  charge,
  current,
  uncoveredVideo,
  useTopUp,
  PREMIUM_PRODUCT,
  TOP_UP_PRODUCT,
  type Allowance,
  type Plan,
  type Usage,
} from "./plans.ts";
import { Play, premiumOwner, type CheckedSubscription } from "./play.ts";

export interface Env {
  ASSETS: Fetcher;
  CALLS: DurableObjectNamespace<CallRoom>;
  ACCOUNTS: DurableObjectNamespace<CallAccount>;
  /**
   * A Google Cloud service account's JSON key, with access to the app in Play Console: to check Premium and
   * top-ups. Without it everyone has the free plan.
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
      if (url.pathname === "/api/usage" && request.method === "POST") return await usage(request, env);
      if (url.pathname === "/api/topups" && request.method === "POST") return await topUp(request, env);
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
  /** Starting a group call (in a group chat): up to GROUP_SIZE people. */
  group?: boolean;
  /** A top-up's purchase token. */
  token?: string;
}

const INSTALL_ID = /^[A-Za-z0-9_-]{16,64}$/;
const PURCHASE_TOKEN = /^[A-Za-z0-9._:-]{10,500}$/;

class BadRequest extends Error {}

async function readApp(request: Request): Promise<AppRequest & { install: string }> {
  const body = (await request.json().catch(() => null)) as AppRequest | null;
  if (!body || typeof body.install !== "string" || !INSTALL_ID.test(body.install)) {
    throw new BadRequest("This app is too old for calls through a link; update it");
  }
  for (const token of [body.subscription, body.token]) {
    if (token !== undefined && (typeof token !== "string" || !PURCHASE_TOKEN.test(token))) {
      throw new BadRequest("Not a purchase token");
    }
  }
  return body as AppRequest & { install: string };
}

async function rateLimited(request: Request, env: Env, kind: string): Promise<boolean> {
  const ip = request.headers.get("CF-Connecting-IP") ?? "local";
  return Boolean(env.CREATE_LIMIT && !(await env.CREATE_LIMIT.limit({ key: `${kind}:${ip}` })).success);
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

/**
 * The minutes left. Bought time is always on the install: a subscriber uses it once the subscription's video is gone,
 * and keeps what's left of it if the subscription ends.
 */
async function minutesLeft(env: Env, plan: Plan, owner: string, install: string): Promise<Allowance> {
  const left = await account(env, owner).allowance(plan);
  if (owner === `install:${install}`) return left;
  const bought = await account(env, `install:${install}`).allowance("free");
  return { ...left, videoLeftMs: left.videoLeftMs + bought.topUpMs, topUpMs: bought.topUpMs };
}

async function createCall(request: Request, env: Env, origin: string): Promise<Response> {
  if (!env.SFU_APP_ID || !env.SFU_APP_SECRET) return error(503, "Calls through a link aren't set up on this server");
  if (await rateLimited(request, env, "call")) return error(429, "Too many calls; try again in a minute");
  let app;
  try {
    app = await readApp(request);
  } catch (e) {
    if (e instanceof BadRequest) return error(400, e.message);
    throw e;
  }
  const { plan, owner } = await whoIs(app, env);
  const left = await minutesLeft(env, plan, owner, app.install);
  const videoAllowed = left.videoLeftMs > 0;
  if (left.audioLeftMs <= 0 && !(app.video && videoAllowed)) {
    return Response.json({ error: "No call minutes left this month", code: "no_minutes", ...left }, { status: 402 });
  }
  const install = `install:${app.install}`;
  const limits: CallLimits = {
    plan,
    owner,
    topUpOwner: owner === install ? undefined : install,
    videoLeftMs: left.videoLeftMs,
    audioLeftMs: left.audioLeftMs,
  };
  const room = randomId();
  const hostToken = randomId();
  const group = app.group === true;
  await env.CALLS.get(env.CALLS.idFromName(room)).create(hostToken, limits, group ? GROUP_SIZE : 2);
  const callUrl = `${origin}/call/${room}`;
  return Response.json({
    room,
    // g=1 lets the invitation say it's a group call before joining
    joinUrl: group ? `${callUrl}?g=1` : callUrl,
    // The token travels in the fragment, so it isn't logged anywhere. The app adds the encryption key (k) to both
    // links itself, so this worker never sees it
    hostUrl: `${callUrl}?host=1${group ? "&g=1" : ""}#t=${hostToken}`,
    plan,
    videoAllowed,
  });
}

async function usage(request: Request, env: Env): Promise<Response> {
  if (await rateLimited(request, env, "usage")) return error(429, "Too many requests; try again in a minute");
  try {
    const app = await readApp(request);
    const { plan, owner } = await whoIs(app, env);
    return Response.json(await minutesLeft(env, plan, owner, app.install));
  } catch (e) {
    if (e instanceof BadRequest) return error(400, e.message);
    throw e;
  }
}

/** Adds a bought top-up to the install, once per purchase. */
async function topUp(request: Request, env: Env): Promise<Response> {
  if (await rateLimited(request, env, "topup")) return error(429, "Too many requests; try again in a minute");
  let app;
  try {
    app = await readApp(request);
  } catch (e) {
    if (e instanceof BadRequest) return error(400, e.message);
    throw e;
  }
  if (!app.token) return error(400, "No purchase token");
  if (!env.PLAY_SERVICE_ACCOUNT) return error(503, "Purchases can't be checked on this server");
  const owner = `install:${app.install}`;
  const purchase = await sha256(app.token);
  const claim = account(env, `purchase:${purchase}`);
  // Each step can be repeated: the app asks again whenever it didn't hear back, wherever this stopped
  let claimedBy = await claim.claimedBy();
  if (claimedBy === undefined) {
    if (!(await play(env).consumePurchase(TOP_UP_PRODUCT, app.token))) {
      return error(402, "This purchase wasn't completed");
    }
    claimedBy = await claim.claim(owner);
  }
  if (claimedBy !== owner) return error(409, "This purchase was already used on another phone");
  await account(env, owner).addTopUp(purchase);
  const { plan, owner: user } = await whoIs(app, env);
  return Response.json(await minutesLeft(env, plan, user, app.install));
}

function error(status: number, message: string): Response {
  return Response.json({ error: message }, { status });
}

/**
 * One person's call minutes this month and their bought time (see plans.ts), keyed "install:<id>" or
 * "sub:<first order>". Also used keyed "purchase:<hash>" to count each top-up once, and "subtoken:<hash>" to remember
 * a subscription Google Play confirmed.
 */
export class CallAccount extends DurableObject<Env> {
  private async load(): Promise<Usage> {
    return current(await this.ctx.storage.get<Usage>("usage"), Date.now());
  }

  async allowance(plan: Plan): Promise<Allowance> {
    return allowance(await this.load(), plan, Date.now());
  }

  /** Counts a call's time; returns what's left, and the video this account couldn't cover. */
  async charge(plan: Plan, videoMs: number, audioMs: number): Promise<{ left: Allowance; uncoveredVideoMs: number }> {
    const before = await this.load();
    const after = charge(before, plan, videoMs, audioMs);
    await this.ctx.storage.put("usage", after);
    return { left: allowance(after, plan, Date.now()), uncoveredVideoMs: uncoveredVideo(before, plan, videoMs) };
  }

  /** Takes video from bought time only; returns the bought time left. */
  async useTopUp(videoMs: number): Promise<number> {
    const after = useTopUp(await this.load(), videoMs);
    await this.ctx.storage.put("usage", after);
    return after.topUpMs;
  }

  /** Adds a bought top-up, once per purchase (keyed by its token's hash). */
  async addTopUp(purchase: string): Promise<void> {
    const key = `topup:${purchase}`;
    if (await this.ctx.storage.get<boolean>(key)) return;
    // Both in one write: never one without the other
    await this.ctx.storage.put({ usage: addTopUp(await this.load()), [key]: true });
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

  /** Keyed "purchase:<hash of its token>": the account a top-up went to. */
  async claimedBy(): Promise<string | undefined> {
    return this.ctx.storage.get<string>("claimedBy");
  }

  /** Gives the purchase to owner unless it already went to someone; returns whose it is. */
  async claim(owner: string): Promise<string> {
    const already = await this.claimedBy();
    if (already !== undefined) return already;
    await this.ctx.storage.put("claimedBy", owner);
    return owner;
  }
}

/** What a connection is, kept on its WebSocket so it survives the Durable Object sleeping between messages. */
interface Attachment {
  member?: MemberId;
  gen?: number;
}

/** Messages from the call page; each with an id that its answer repeats. */
type Request_ =
  | { type: "hello"; token?: string; secret?: string }
  | {
      type: "publish";
      offer: SessionDescription;
      tracks: { mid: string; trackName: string }[];
    }
  | { type: "announce" }
  | { type: "subscribe"; peer: MemberId; session: string; tracks: string[]; fresh: boolean }
  | { type: "answer"; peer: MemberId; answer: SessionDescription }
  | { type: "camera"; on: boolean }
  | { type: "bye" };

class Refusal extends Error {}

/**
 * One call. It holds the call's state (room.ts) and everyone's WebSocket connections, and makes the SFU requests for the
 * pages, which never see the SFU's secret. It keeps nothing once the call ends.
 */
export class CallRoom extends DurableObject<Env> {
  private readonly sfu = new Sfu(this.env.SFU_APP_ID, this.env.SFU_APP_SECRET);
  /**
   * The call, loaded once: messages can interleave while one waits for the SFU, so they all change this same object.
   * null once it's over (or never existed).
   */
  private room?: Room | null;

  async create(hostToken: string, limits?: CallLimits, size = 2): Promise<void> {
    this.room = newRoom(hostToken, Date.now(), limits, size);
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

    const { member: id, gen } = ws.deserializeAttachment() as Attachment;
    const me = id === undefined ? undefined : room.members[id];
    if (id === undefined || !me || me.gen !== gen || !me.connected) throw new Refusal("not in the call");

    switch (message.type) {
      case "publish": {
        const names = message.tracks.map((t) => t.trackName);
        if (!names.every(isTrackName)) throw new Refusal("unknown track");
        if (!room.meter.videoAllowed && names.some((n) => n !== "audio")) throw new Refusal("no video minutes");
        const session = me.pubSession ?? (await this.sfu.newSession());
        if (!publish(room, id, session, names)) throw new Refusal("already published");
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
        announce(room, id);
        await this.changed(room);
        return {};
      case "subscribe": {
        const peer = String(message.peer);
        if (!canSubscribe(room, id, peer, message.session, message.tracks)) throw new Refusal("not available");
        if (message.fresh || !me.subSessions[peer]) me.subSessions[peer] = await this.sfu.newSession();
        const response = await this.sfu.newTracks(me.subSessions[peer], {
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
      case "answer": {
        const session = me.subSessions[String(message.peer)];
        if (!session) throw new Refusal("nothing to answer");
        await this.sfu.renegotiate(session, message.answer);
        return {};
      }
      case "camera":
        if (message.on && !room.meter.videoAllowed) throw new Refusal("no video minutes");
        me.camera = Boolean(message.on);
        await this.changed(room);
        return {};
      case "bye":
        hangUp(room, id, Date.now());
        await this.changed(room);
        return undefined;
      default:
        throw new Refusal("unknown message");
    }
  }

  private async hello(ws: WebSocket, room: Room, message: { token?: string; secret?: string }): Promise<object> {
    if ((ws.deserializeAttachment() as Attachment).member) throw new Refusal("already in the call");
    const admission = admit(room, { token: message.token, secret: message.secret }, () => randomId(), Date.now());
    if ("error" in admission) throw new Refusal(admission.error);
    const { member, gen } = admission;
    // A connection being replaced may not know it yet
    for (const old of this.ctx.getWebSockets()) {
      const attachment = old.deserializeAttachment() as Attachment;
      if (old !== ws && attachment.member === member) old.close(4000, "replaced");
    }
    ws.serializeAttachment({ member, gen } satisfies Attachment);
    const ttl = Math.ceil((room.createdAt + MAX_CALL_MS - Date.now()) / 1000);
    const servers = await iceServers(this.env.TURN_KEY_ID, this.env.TURN_KEY_TOKEN, ttl);
    // The others hear about it; this page learns it all from the welcome
    await this.changed(room, ws);
    return {
      type: "welcome",
      member,
      secret: admission.secret,
      iceServers: servers,
      call: callView(room, member, Date.now()),
    };
  }

  private async dropped(ws: WebSocket): Promise<void> {
    const { member, gen } = ws.deserializeAttachment() as Attachment;
    if (member === undefined || gen === undefined) return;
    const room = await this.load();
    if (!room) return;
    meter(room, Date.now());
    if (disconnect(room, member, gen, Date.now())) await this.changed(room);
  }

  /**
   * Applies the limits, charges the host's account for the time so far, saves the call and tells everyone what
   * changed; once it has ended, closes it and forgets it.
   */
  private async changed(room: Room, except?: WebSocket, quietIfSame = false): Promise<void> {
    const now = Date.now();
    meter(room, now);
    const before = limitsView(room, now);
    // Video stopped by the limits: every page hears it, even the one whose message this was
    const enforced = enforce(room, now);
    if (enforced) except = undefined;
    await this.charge(room);
    const after = limitsView(room, now);
    const same =
      !enforced && room.phase !== "ended" && before.endsAt === after.endsAt && before.videoEndsAt === after.videoEndsAt;
    for (const ws of quietIfSame && same ? [] : this.ctx.getWebSockets()) {
      const { member } = ws.deserializeAttachment() as Attachment;
      if (member === undefined || ws === except) continue;
      if (room.phase === "ended") {
        send(ws, { type: "ended", reason: room.endReason });
        ws.close(1000, "ended");
      } else {
        send(ws, { type: "call", call: callView(room, member, now) });
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
    const { plan, owner, topUpOwner } = room.limits;
    let charged;
    try {
      charged = await account(this.env, owner).charge(plan, due.videoMs, due.audioMs);
    } catch (e) {
      // Counted again with the next charge
      room.meter.chargedVideoMs -= due.videoMs;
      room.meter.chargedAudioMs -= due.audioMs;
      console.error("Charging the call", e);
      return;
    }
    let videoLeftMs = charged.left.videoLeftMs;
    if (topUpOwner) {
      // A subscriber past the subscription's video: the rest comes from the install's bought time
      try {
        const bought = account(this.env, topUpOwner);
        videoLeftMs +=
          charged.uncoveredVideoMs > 0
            ? await bought.useTopUp(charged.uncoveredVideoMs)
            : (await bought.allowance("free")).topUpMs;
      } catch (e) {
        console.error("Charging the top-up", e);
        return;
      }
    }
    // What the account has left now, after any other call of the same person's too
    refreshLimits(room, { videoLeftMs, audioLeftMs: charged.left.audioLeftMs });
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
      // A call saved before group calls (host and guest): it simply ends
      if (this.room && !this.room.members) this.room = null;
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
