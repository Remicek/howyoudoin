// A call's rules, kept apart from the Durable Object that stores them so they can be tested on their own.
//
// A call has two places: the HowYouDoin user who started it (the host, who has the call's secret token) and one
// invited person (the guest, who has only the link and gets a secret of their own when they join, to come back after a
// dropped connection). If the host chose to (askFirst, a setting in the app), the guest waits until the host lets
// them in, so whoever opens a link that leaked through the chat can be turned away. Once both have been in the call, either one hanging up, or staying away longer than
// RECONNECT_MS, ends it for good.

import { LIMITS, type Plan } from "./plans.ts";

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
  /** How many SFU sessions this person has opened in this call (capped: each costs). */
  sessions?: number;
  /** Published but not yet connected, so not yet offered to the other person. */
  pending: string[];
  /** What the other person can receive (see isTrackName). */
  tracks: string[];
  /** Their camera is on (when it's off they send a black picture, so the SFU keeps the track). */
  camera: boolean;
}

/** What the host's plan allows this call. */
export interface CallLimits {
  plan: Plan;
  /** Whose minutes it uses (see Accounts in worker.ts). */
  owner: string;
  /**
   * The host's minutes for this call, counted from its start: what was left when it was created, then, each time it
   * is charged, what this call has used plus what the account has left (so calls at the same time share them).
   */
  videoLeftMs: number;
  audioLeftMs: number;
}

/** The call's time so far, counted while both are in it. */
export interface Meter {
  /** Up to when it has been counted. */
  at?: number;
  /** When both were first in the call. */
  activeAt?: number;
  videoMs: number;
  audioMs: number;
  /** How much of it the host's account has been charged. */
  chargedVideoMs: number;
  chargedAudioMs: number;
  /** False once the video minutes are used up: the rest of the call is audio. */
  videoAllowed: boolean;
}

export interface Room {
  createdAt: number;
  hostToken: string;
  /** waiting: the two haven't both been in it yet; active: they have; ended: over, the link no longer works. */
  phase: "waiting" | "active" | "ended";
  /** Why it ended, when it was a limit: "minutes" (none left) or "length" (the longest call). */
  endReason?: "minutes" | "length";
  host: Member;
  guest: Member;
  /** The guest may be in the call: from the start, unless the host asked to let them in. Missing (older calls): yes. */
  letIn?: boolean;
  /** Missing for calls created before plans. */
  limits?: CallLimits;
  meter: Meter;
}

/** How long the call waits for the invited person. */
export const JOIN_WINDOW_MS = 15 * 60 * 1000;
/** The longest call, so a forgotten one doesn't run up the bill (and the longest a plan allows). */
export const MAX_CALL_MS = 4 * 60 * 60 * 1000;
/** How often a running call charges the host's account, so a crash loses little. */
export const CHARGE_EVERY_MS = 60 * 1000;
/** How long someone whose connection dropped has to come back (a phone changing networks, a tunnel). */
export const RECONNECT_MS = 20 * 1000;
/** Most publications one connection may make (each time the camera is turned on is one). */
export const MAX_TRACKS = 50;
/** Most SFU sessions one person may open in a call: a few reconnections' worth. */
export const MAX_SESSIONS = 12;

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
  /** Connected now (a guest only once let in). */
  present: boolean;
  /** The host's view: someone opened the link and waits to be let in. */
  knocking: boolean;
  /** The guest is let in (the guest's page waits for it before sending anything). */
  letIn: boolean;
  /** Their publishing session, while they publish anything. */
  session?: string;
  tracks: string[];
  camera: boolean;
  limits: LimitsView;
}

/** What the call pages need to know about the plan's limits. */
export interface LimitsView {
  videoAllowed: boolean;
  /** Highest video to send, and its bitrate cap. */
  videoHeight: number;
  videoBitrate: number;
  /** When the call will end if nothing changes (its longest length, or the minutes running out). */
  endsAt?: number;
  /** When video will stop if the cameras stay on. */
  videoEndsAt?: number;
  /** The server's time, so the pages can count down whatever their own clock says. */
  now: number;
}

const emptyMember = (gen = 0): Member => ({ connected: false, gen, pending: [], tracks: [], camera: false });

export const emptyMeter = (limits?: CallLimits): Meter => ({
  videoMs: 0,
  audioMs: 0,
  chargedVideoMs: 0,
  chargedAudioMs: 0,
  videoAllowed: limits?.videoLeftMs !== 0,
});

/** @param askFirst the host lets the guest in themselves; otherwise whoever opens the link first is in. */
export function newRoom(hostToken: string, now: number, limits?: CallLimits, askFirst = false): Room {
  return {
    createdAt: now,
    hostToken,
    phase: "waiting",
    host: emptyMember(),
    guest: emptyMember(),
    letIn: !askFirst,
    limits,
    meter: emptyMeter(limits),
  };
}

export const isLetIn = (room: Room): boolean => room.letIn !== false;
const startIfBothIn = (room: Room) => {
  if (room.host.connected && room.guest.connected && isLetIn(room)) room.phase = "active";
};
const bothIn = (room: Room) => room.phase === "active" && room.host.connected && room.guest.connected;
const videoOn = (room: Room) => room.host.camera || room.guest.camera;

/**
 * Counts the time since the last count, as the call was until now. Call it before anything changes (someone joining,
 * leaving, a camera) and after, which starts the count once both are in.
 */
export function meter(room: Room, now: number): void {
  const m = room.meter;
  if (m.at !== undefined && bothIn(room)) {
    const elapsed = Math.max(0, now - m.at);
    if (videoOn(room)) m.videoMs += elapsed;
    else m.audioMs += elapsed;
  }
  if (room.phase === "active") {
    m.activeAt ??= now;
    m.at = now;
  }
}

/** The longest this call may last. */
const maxLength = (room: Room) => (room.limits ? LIMITS[room.limits.plan].maxCallMs : MAX_CALL_MS);

/**
 * Applies the plan's limits to the time counted so far: video stops once its minutes are gone, and the call ends at
 * its longest length or when no minutes are left. Returns whether anything changed.
 */
export function enforce(room: Room, now: number): boolean {
  const { limits, meter: m } = room;
  if (room.phase === "ended" || !limits) return false;
  let changed = false;
  if (m.videoAllowed && m.videoMs >= limits.videoLeftMs) {
    m.videoAllowed = false;
    changed = true;
  }
  if (!m.videoAllowed && videoOn(room)) {
    room.host.camera = false;
    room.guest.camera = false;
    changed = true;
  }
  if (m.activeAt !== undefined && now >= m.activeAt + maxLength(room)) {
    room.phase = "ended";
    room.endReason = "length";
    return true;
  }
  if (m.activeAt !== undefined && m.audioMs >= limits.audioLeftMs && !videoOn(room)) {
    room.phase = "ended";
    room.endReason = "minutes";
    return true;
  }
  return changed;
}

/** What the host's account has left after a charge: the call may use what it has used so far plus this. */
export function refreshLimits(room: Room, left: { videoLeftMs: number; audioLeftMs: number }): void {
  if (!room.limits) return;
  room.limits.videoLeftMs = room.meter.chargedVideoMs + left.videoLeftMs;
  room.limits.audioLeftMs = room.meter.chargedAudioMs + left.audioLeftMs;
}

/** The time not yet charged to the host's account; marks it charged. */
export function takeCharge(room: Room): { videoMs: number; audioMs: number } {
  const m = room.meter;
  const due = { videoMs: m.videoMs - m.chargedVideoMs, audioMs: m.audioMs - m.chargedAudioMs };
  m.chargedVideoMs = m.videoMs;
  m.chargedAudioMs = m.audioMs;
  return due;
}

export function limitsView(room: Room, now: number): LimitsView {
  const plan = LIMITS[room.limits?.plan ?? "premium"];
  const m = room.meter;
  const view: LimitsView = {
    videoAllowed: m.videoAllowed,
    videoHeight: plan.videoHeight,
    videoBitrate: plan.videoBitrate,
    now,
  };
  if (m.activeAt === undefined || !room.limits) return view;
  const { videoLeftMs, audioLeftMs } = room.limits;
  const longest = m.activeAt + maxLength(room);
  const counted = m.at ?? now;
  if (videoOn(room) && m.videoAllowed) {
    view.videoEndsAt = counted + Math.max(0, videoLeftMs - m.videoMs);
    view.endsAt = Math.min(longest, view.videoEndsAt + Math.max(0, audioLeftMs - m.audioMs));
  } else {
    view.endsAt = Math.min(longest, counted + Math.max(0, audioLeftMs - m.audioMs));
  }
  return view;
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
    sessions: before.sessions,
  };
  startIfBothIn(room);
  return { role, gen: room[role].gen, secret: room[role].secret };
}

/** The host lets in the guest who is waiting. False when nobody is. */
export function letIn(room: Room): boolean {
  if (room.phase === "ended" || isLetIn(room) || !room.guest.connected) return false;
  room.letIn = true;
  startIfBothIn(room);
  return true;
}

/**
 * The host turns away the guest who is waiting: their secret no longer works and the place is free for someone else
 * with the link. False when nobody is waiting.
 */
export function turnAway(room: Room): boolean {
  if (room.phase === "ended" || isLetIn(room) || !room.guest.secret) return false;
  room.guest = emptyMember(room.guest.gen + 1);
  return true;
}

/** A connection closed. Returns false for a connection that was already replaced. */
export function disconnect(room: Room, role: Role, gen: number, now: number): boolean {
  const member = room[role];
  if (member.gen !== gen || !member.connected) return false;
  room[role] = { ...emptyMember(gen), secret: member.secret, leftAt: now, sessions: member.sessions };
  return true;
}

/** Someone hung up: the call ends, unless it's the guest leaving before the host ever saw them. */
export function hangUp(room: Room, role: Role): void {
  if (room.phase === "active" || role === "host") room.phase = "ended";
  else room.guest = emptyMember(room.guest.gen);
}

/**
 * The latest a call can still be going, whatever happens: a plan's longest call counts from when both are in it
 * (enforce), which is at most JOIN_WINDOW_MS after it was created.
 */
const hardStop = (room: Room) => room.createdAt + (room.limits ? JOIN_WINDOW_MS : 0) + MAX_CALL_MS;

/** Applies the time limits. Returns whether anything changed. */
export function tick(room: Room, now: number): boolean {
  if (room.phase === "ended") return false;
  if ((room.phase === "waiting" && now >= room.createdAt + JOIN_WINDOW_MS) || now >= hardStop(room)) {
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

/** When tick (or the limits, or charging the account) has something to do next. */
export function nextDeadline(room: Room, now: number): number {
  const deadlines = [hardStop(room)];
  const m = room.meter;
  if (room.limits && m.activeAt !== undefined) {
    deadlines.push(m.activeAt + maxLength(room));
    if (bothIn(room)) {
      deadlines.push(now + CHARGE_EVERY_MS);
      const view = limitsView(room, now);
      if (view.videoEndsAt !== undefined) deadlines.push(view.videoEndsAt);
      if (view.endsAt !== undefined) deadlines.push(view.endsAt);
    }
  }
  if (room.phase === "waiting") deadlines.push(room.createdAt + JOIN_WINDOW_MS);
  for (const member of [room.host, room.guest]) {
    if (member.leftAt !== undefined) deadlines.push(member.leftAt + RECONNECT_MS);
  }
  return Math.min(...deadlines);
}

/** Whether these are new, valid track names this person may still publish (checked before any SFU session is made). */
export function canPublish(room: Room, role: Role, names: string[]): boolean {
  const member = room[role];
  return (
    names.length > 0 &&
    names.every((n) => isTrackName(n) && !member.tracks.includes(n) && !member.pending.includes(n)) &&
    new Set(names).size === names.length &&
    member.tracks.length + member.pending.length + names.length <= MAX_TRACKS
  );
}

/** Records what someone has started publishing; it's offered to the other person once it's connected (announce). */
export function publish(room: Room, role: Role, session: string, names: string[]): boolean {
  const member = room[role];
  if (!canPublish(room, role, names) || (member.pubSession !== undefined && member.pubSession !== session)) return false;
  member.pubSession = session;
  member.pending = [...new Set([...member.pending, ...names])];
  return true;
}

/** Counts a new SFU session for this person; false once they've had their share. */
export function takeSession(room: Room, role: Role): boolean {
  const member = room[role];
  const used = member.sessions ?? 0;
  if (used >= MAX_SESSIONS) return false;
  member.sessions = used + 1;
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

/** What this person needs to know about the other one, and about the call's limits. */
export function peerView(room: Room, role: Role, now: number): PeerView {
  const peer = room[other(role)];
  const waiting = !isLetIn(room) && room.guest.connected;
  return {
    present: peer.connected && (role === "guest" || isLetIn(room)),
    knocking: role === "host" && waiting,
    letIn: isLetIn(room),
    session: peer.tracks.length ? peer.pubSession : undefined,
    tracks: peer.tracks,
    camera: peer.camera,
    limits: limitsView(room, now),
  };
}

function sameSecret(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
