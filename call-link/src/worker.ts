// Calls through a link. HowYouDoin can't answer or place calls on WhatsApp, Messenger and the other networks, so it
// offers a separate call instead: the app asks this worker for a room, sends the link in the chat, and the other
// person joins from their browser. The worker only creates rooms and signs tokens; the call itself goes through
// LiveKit, end-to-end encrypted with a key that stays in the link's #fragment (browsers never send it to a server).
//
//   POST /api/calls                -> { room, joinUrl, hostUrl }   the app starts a call
//   POST /api/calls/<room>/join    -> { url, token }               the invited person's page joins it
//
// Everything else is the call page (public/), served for /call/<room>.

import { createToken, randomId } from "./token.ts";

export interface Env {
  ASSETS: Fetcher;
  /** wss://… of the LiveKit project. */
  LIVEKIT_URL: string;
  LIVEKIT_API_KEY: string;
  LIVEKIT_API_SECRET: string;
  CREATE_LIMIT?: RateLimit;
}

/** How long a room lasts with nobody in it: time enough for the invited person to open the link. */
const EMPTY_TIMEOUT_SECONDS = 15 * 60;
/** After the last person leaves. */
const DEPARTURE_TIMEOUT_SECONDS = 20;
/** The app's own token is used right away; the invited person's token when they press Join. */
const TOKEN_TTL_SECONDS = 10 * 60;
const ROOM_NAME = /^[A-Za-z0-9_-]{22}$/;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    try {
      if (url.pathname === "/api/calls" && request.method === "POST") {
        return await createCall(request, env, url.origin);
      }
      const join = url.pathname.match(/^\/api\/calls\/([^/]+)\/join$/);
      if (join && request.method === "POST") return await joinCall(join[1], env);
      if (url.pathname.startsWith("/api/")) return error(404, "Not found");
      return env.ASSETS.fetch(request);
    } catch (e) {
      console.error(e);
      return error(502, "The call service isn't reachable");
    }
  },
} satisfies ExportedHandler<Env>;

async function createCall(request: Request, env: Env, origin: string): Promise<Response> {
  if (!configured(env)) return error(503, "Calls through a link aren't set up on this server");
  const ip = request.headers.get("CF-Connecting-IP") ?? "local";
  if (env.CREATE_LIMIT && !(await env.CREATE_LIMIT.limit({ key: ip })).success) {
    return error(429, "Too many calls; try again in a minute");
  }
  const room = randomId();
  // Created up front with room for two, so a link can't fill a room with strangers (or be reused for long)
  await roomService(env, "CreateRoom", {
    name: room,
    max_participants: 2,
    empty_timeout: EMPTY_TIMEOUT_SECONDS,
    departure_timeout: DEPARTURE_TIMEOUT_SECONDS,
  });
  const token = await participantToken(env, room, "host");
  const joinUrl = `${origin}/call/${room}`;
  return Response.json({
    room,
    joinUrl,
    // The app's own page joins straight away; the token travels in the fragment, so it isn't logged anywhere. The app
    // adds the encryption key (k) to both links itself, so this worker never sees it
    hostUrl: `${joinUrl}?host=1#t=${token}&u=${encodeURIComponent(env.LIVEKIT_URL)}`,
  });
}

async function joinCall(room: string, env: Env): Promise<Response> {
  if (!configured(env)) return error(503, "Calls through a link aren't set up on this server");
  if (!ROOM_NAME.test(room)) return error(404, "This call doesn't exist");
  // Only rooms made by createCall: a token for any other name would open a new room on our account
  const list = (await roomService(env, "ListRooms", { names: [room] })) as { rooms?: unknown[] };
  if (!list.rooms?.length) return error(404, "This call has ended");
  return Response.json({ url: env.LIVEKIT_URL, token: await participantToken(env, room, `guest-${randomId(6)}`) });
}

function participantToken(env: Env, room: string, identity: string): Promise<string> {
  return createToken({
    apiKey: env.LIVEKIT_API_KEY,
    apiSecret: env.LIVEKIT_API_SECRET,
    identity,
    ttlSeconds: TOKEN_TTL_SECONDS,
    grant: { room, roomJoin: true, canPublish: true, canSubscribe: true, canPublishData: true },
  });
}

/** LiveKit's server API (Twirp over HTTP, JSON), authorised with a short-lived admin token. */
async function roomService(env: Env, method: string, body: unknown): Promise<unknown> {
  const token = await createToken({
    apiKey: env.LIVEKIT_API_KEY,
    apiSecret: env.LIVEKIT_API_SECRET,
    ttlSeconds: 60,
    grant: { roomCreate: true, roomList: true },
  });
  const response = await fetch(`${httpUrl(env.LIVEKIT_URL)}/twirp/livekit.RoomService/${method}`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`LiveKit ${method}: ${response.status} ${await response.text()}`);
  return response.json();
}

function configured(env: Env): boolean {
  return Boolean(env.LIVEKIT_URL && env.LIVEKIT_API_KEY && env.LIVEKIT_API_SECRET);
}

export function httpUrl(livekitUrl: string): string {
  return livekitUrl.replace(/^ws(s?):\/\//, "http$1://").replace(/\/$/, "");
}

function error(status: number, message: string): Response {
  return Response.json({ error: message }, { status });
}
