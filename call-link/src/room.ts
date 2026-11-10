// A call's rules, kept apart from the Durable Object that stores them so they can be tested on their own.
//
// A call has two places: the HowYouDoin user who started it (the host, who has the call's secret token) and one
// invited person (the guest, who has only the link and gets a secret of their own when they join, to come back after a
// dropped connection). Once both have been in the call, either one hanging up, or staying away longer than
// RECONNECT_MS, ends it for good.

export type Role = "host" | "guest";

export interface Member {
  /** Has an open connection to the call now. */
  connected: boolean;
  /** Counts their connections, so a replaced connection closing late doesn't count as leaving. */
  gen: number;
  /** The guest's way back in after a dropped connection. */
  secret?: string;
  /** When their connection dropped. */
  leftAt?: number;
  /** Realtime SFU session that sends their media. */
  pubSession?: string;
  /** Realtime SFU session that receives the other person's media. */
  subSession?: string;
  /** Published but not yet connected, so not yet offered to the other person. */
  pending: string[];
  /** What the other person can receive (see isTrackName). */
  tracks: string[];
  /** Their camera is on (when it's off they send a black picture, so the SFU keeps the track). */
  camera: boolean;
}

export interface Room {
  createdAt: number;
  hostToken: string;
  /** waiting: the two haven't both been in it yet; active: they have; ended: over, the link no longer works. */
  phase: "waiting" | "active" | "ended";
  host: Member;
  guest: Member;
}

/** How long the call waits for the invited person. */
export const JOIN_WINDOW_MS = 15 * 60 * 1000;
/** The longest call, so a forgotten one doesn't run up the bill. */
export const MAX_CALL_MS = 4 * 60 * 60 * 1000;
/** How long someone whose connection dropped has to come back (a phone changing networks, a tunnel). */
export const RECONNECT_MS = 20 * 1000;
/** Most publications one connection may make (each time the camera is turned on is one). */
export const MAX_TRACKS = 50;

/**
 * "audio", and "video1", "video2"…: a camera turned off stops sending, and the SFU drops a track that sends nothing
 * for 30 seconds, so turning it back on publishes a new one. The other person shows the newest.
 */
export const isTrackName = (name: string): boolean => /^(audio|video[1-9][0-9]{0,2})$/.test(name);

export interface Credentials {
  /** The host's token. */
  token?: string;
  /** The guest's secret, when coming back. */
  secret?: string;
}

export type Admission = { role: Role; gen: number; secret?: string } | { error: "ended" | "busy" | "forbidden" };

export interface PeerView {
  /** Connected now. */
  present: boolean;
  /** Their publishing session, while they publish anything. */
  session?: string;
  tracks: string[];
  camera: boolean;
}

const emptyMember = (gen = 0): Member => ({ connected: false, gen, pending: [], tracks: [], camera: false });

export function newRoom(hostToken: string, now: number): Room {
  return { createdAt: now, hostToken, phase: "waiting", host: emptyMember(), guest: emptyMember() };
}

export const other = (role: Role): Role => (role === "host" ? "guest" : "host");

/**
 * Lets someone in: the host with the call's token, the guest coming back with their secret, or a first guest. Someone
 * coming back replaces their old connection, which may not have noticed it's gone yet.
 */
export function admit(room: Room, credentials: Credentials, newSecret: () => string): Admission {
  if (room.phase === "ended") return { error: "ended" };
  let role: Role;
  if (credentials.token !== undefined) {
    if (!sameSecret(credentials.token, room.hostToken)) return { error: "forbidden" };
    role = "host";
  } else if (credentials.secret !== undefined) {
    if (!room.guest.secret || !sameSecret(credentials.secret, room.guest.secret)) return { error: "forbidden" };
    role = "guest";
  } else {
    if (room.guest.secret) return { error: "busy" };
    role = "guest";
  }
  const before = room[role];
  room[role] = {
    ...emptyMember(before.gen + 1),
    connected: true,
    secret: role === "guest" ? (before.secret ?? newSecret()) : undefined,
  };
  if (room.host.connected && room.guest.connected) room.phase = "active";
  return { role, gen: room[role].gen, secret: room[role].secret };
}

/** A connection closed. Returns false for a connection that was already replaced. */
export function disconnect(room: Room, role: Role, gen: number, now: number): boolean {
  const member = room[role];
  if (member.gen !== gen || !member.connected) return false;
  room[role] = { ...emptyMember(gen), secret: member.secret, leftAt: now };
  return true;
}

/** Someone hung up: the call ends, unless it's the guest leaving before the host ever saw them. */
export function hangUp(room: Room, role: Role): void {
  if (room.phase === "active" || role === "host") room.phase = "ended";
  else room.guest = emptyMember(room.guest.gen);
}

/** Applies the time limits. Returns whether anything changed. */
export function tick(room: Room, now: number): boolean {
  if (room.phase === "ended") return false;
  if ((room.phase === "waiting" && now >= room.createdAt + JOIN_WINDOW_MS) || now >= room.createdAt + MAX_CALL_MS) {
    room.phase = "ended";
    return true;
  }
  let changed = false;
  for (const role of ["host", "guest"] as const) {
    const leftAt = room[role].leftAt;
    if (leftAt === undefined || now < leftAt + RECONNECT_MS) continue;
    hangUp(room, role);
    changed = true;
  }
  return changed;
}

/** When tick has something to do next. */
export function nextDeadline(room: Room): number {
  const deadlines = [room.createdAt + MAX_CALL_MS];
  if (room.phase === "waiting") deadlines.push(room.createdAt + JOIN_WINDOW_MS);
  for (const member of [room.host, room.guest]) {
    if (member.leftAt !== undefined) deadlines.push(member.leftAt + RECONNECT_MS);
  }
  return Math.min(...deadlines);
}

/** Records what someone has started publishing; it's offered to the other person once it's connected (announce). */
export function publish(room: Room, role: Role, session: string, names: string[]): boolean {
  const member = room[role];
  const valid =
    names.length > 0 &&
    names.every((n) => isTrackName(n) && !member.tracks.includes(n) && !member.pending.includes(n)) &&
    new Set(names).size === names.length &&
    member.tracks.length + member.pending.length + names.length <= MAX_TRACKS &&
    (member.pubSession === undefined || member.pubSession === session);
  if (!valid) return false;
  member.pubSession = session;
  member.pending = [...new Set([...member.pending, ...names])];
  return true;
}

export function announce(room: Room, role: Role): void {
  const member = room[role];
  member.tracks = [...new Set([...member.tracks, ...member.pending])];
  member.pending = [];
}

/** Whether this person may receive these of the other person's publications. */
export function canSubscribe(room: Room, role: Role, session: string, names: string[]): boolean {
  const publisher = room[other(role)];
  return (
    publisher.connected &&
    publisher.pubSession === session &&
    names.length > 0 &&
    names.every((n) => publisher.tracks.includes(n))
  );
}

/** What this person needs to know about the other one. */
export function peerView(room: Room, role: Role): PeerView {
  const peer = room[other(role)];
  return {
    present: peer.connected,
    session: peer.tracks.length ? peer.pubSession : undefined,
    tracks: peer.tracks,
    camera: peer.camera,
  };
}

function sameSecret(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
