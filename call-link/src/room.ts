// A call's rules, kept apart from the Durable Object that stores them so they can be tested on their own.
//
// A call has the HowYouDoin user who started it (the host, who has the call's secret token) and the people invited by
// link (guests, who get a secret of their own when they join, to come back after a dropped connection).
//
// A one-to-one call has one guest. Once both have been in it, either one hanging up, or staying away longer than
// RECONNECT_MS, ends it for good.
//
// A group call (started in a group chat) takes up to GROUP_SIZE people. Hanging up only leaves it; it ends once
// nobody is left, or when someone has been alone in it for ALONE_MS. While n people are in it, the host's minutes go
// n - 1 times as fast: everyone receives everyone else, so the call costs that much more to carry.

import { LIMITS, type Plan } from "./plans.ts";

/** "host", or a guest's ID ("g1", "g2"…). */
export type MemberId = string;
export const HOST: MemberId = "host";

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
  /** Realtime SFU sessions that receive the others' media, one per person. */
  subSessions: Record<MemberId, string>;
  /** Published but not yet connected, so not yet offered to the others. */
  pending: string[];
  /** What the others can receive (see isTrackName). */
  tracks: string[];
  /** Their camera is on (when it's off they send a black picture, so the SFU keeps the track). */
  camera: boolean;
}

/** What the host's plan allows this call. */
export interface CallLimits {
  plan: Plan;
  /** Whose minutes it uses (see Accounts in worker.ts). */
  owner: string;
  /** A subscriber's install, whose bought time is used once the subscription's video is gone. */
  topUpOwner?: string;
  /**
   * The host's minutes for this call, counted from its start: what was left when it was created, then, each time it
   * is charged, what this call has used plus what the account has left (so calls at the same time share them).
   */
  videoLeftMs: number;
  audioLeftMs: number;
}

/** The call's time so far, counted while at least two people are in it (in "minutes used", see rate). */
export interface Meter {
  /** Up to when it has been counted. */
  at?: number;
  /** When two people were first in the call. */
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
  /** waiting: nobody has been in it with someone else yet; active: they have; ended: over, the link no longer works. */
  phase: "waiting" | "active" | "ended";
  /** Why it ended, when it was a limit: "minutes" (none left) or "length" (the longest call). */
  endReason?: "minutes" | "length";
  /** Most people in the call: 2 for a one-to-one call. */
  size: number;
  /** Everyone who has joined, by ID; a guest who left keeps their place (and secret) to come back. */
  members: Record<MemberId, Member>;
  /** The number the next guest's ID gets. */
  nextGuest: number;
  /** A group call: since when one person (or nobody) has been in it, once it was active. */
  aloneSince?: number;
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
/** The most people in a group call. Without simulcast everyone receives everyone's full video, so keep it small. */
export const GROUP_SIZE = 6;
/** How long a group call stays open with one person in it (the others may come back, or still join). */
export const ALONE_MS = 15 * 60 * 1000;
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

export type Admission = { member: MemberId; gen: number; secret?: string } | { error: "ended" | "busy" | "forbidden" };

export interface PeerView {
  id: MemberId;
  /** Connected now. */
  present: boolean;
  /** Their publishing session, while they publish anything. */
  session?: string;
  tracks: string[];
  camera: boolean;
}

/** What one person needs to know about the call: everyone else, and the limits. */
export interface CallView {
  group: boolean;
  peers: PeerView[];
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

const emptyMember = (gen = 0): Member => ({
  connected: false,
  gen,
  subSessions: {},
  pending: [],
  tracks: [],
  camera: false,
});

export const emptyMeter = (limits?: CallLimits): Meter => ({
  videoMs: 0,
  audioMs: 0,
  chargedVideoMs: 0,
  chargedAudioMs: 0,
  videoAllowed: limits?.videoLeftMs !== 0,
});

export function newRoom(hostToken: string, now: number, limits?: CallLimits, size = 2): Room {
  return {
    createdAt: now,
    hostToken,
    phase: "waiting",
    size,
    members: { [HOST]: emptyMember() },
    nextGuest: 1,
    limits,
    meter: emptyMeter(limits),
  };
}

export const isGroup = (room: Room): boolean => room.size > 2;
const everyone = (room: Room) => Object.values(room.members);
const inCall = (room: Room) => everyone(room).filter((m) => m.connected).length;
const together = (room: Room) => room.phase === "active" && inCall(room) >= 2;
const videoOn = (room: Room) => everyone(room).some((m) => m.camera);
/** How fast the host's minutes go: once for two people, n - 1 times for n. */
export const rate = (room: Room): number => Math.max(1, inCall(room) - 1);

/**
 * Counts the time since the last count, as the call was until now. Call it before anything changes (someone joining,
 * leaving, a camera) and after, which starts the count once both are in.
 */
export function meter(room: Room, now: number): void {
  const m = room.meter;
  if (m.at !== undefined && together(room)) {
    const elapsed = Math.max(0, now - m.at) * rate(room);
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
    for (const member of everyone(room)) member.camera = false;
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
  // Minutes left, as time at the call's current pace
  const r = rate(room);
  const videoLeft = Math.max(0, videoLeftMs - m.videoMs) / r;
  const audioLeft = Math.max(0, audioLeftMs - m.audioMs) / r;
  if (videoOn(room) && m.videoAllowed) {
    view.videoEndsAt = counted + videoLeft;
    view.endsAt = Math.min(longest, view.videoEndsAt + audioLeft);
  } else {
    view.endsAt = Math.min(longest, counted + audioLeft);
  }
  return view;
}

/**
 * Whether someone counts towards the call's size: in it, or dropped and still able to come back. In a one-to-one call
 * the guest keeps the place once they're in (only the host can then end it); in a group call whoever hung up frees it.
 */
const holdsPlace = (member: Member) => member.connected || member.leftAt !== undefined;
const taken = (room: Room) =>
  isGroup(room)
    ? Object.values(room.members).filter(holdsPlace).length + (room.members[HOST] && !holdsPlace(room.members[HOST]) ? 1 : 0)
    : Object.keys(room.members).length;

/** Keeps aloneSince up to date; call it after anyone joins or leaves. */
function presenceChanged(room: Room, now: number): void {
  if (room.phase === "waiting" && inCall(room) >= 2) room.phase = "active";
  if (!isGroup(room) || room.phase !== "active") return;
  if (inCall(room) >= 2) room.aloneSince = undefined;
  else room.aloneSince ??= now;
}

/**
 * Lets someone in: the host with the call's token, a guest coming back with their secret, or a new guest while there's
 * room. Someone coming back replaces their old connection, which may not have noticed it's gone yet.
 */
export function admit(room: Room, credentials: Credentials, newSecret: () => string, now = 0): Admission {
  if (room.phase === "ended") return { error: "ended" };
  let id: MemberId;
  if (credentials.token !== undefined) {
    if (!sameSecret(credentials.token, room.hostToken)) return { error: "forbidden" };
    id = HOST;
  } else if (credentials.secret !== undefined) {
    const found = Object.entries(room.members).find(
      ([key, m]) => key !== HOST && m.secret !== undefined && sameSecret(credentials.secret!, m.secret),
    );
    if (!found) return { error: "forbidden" };
    id = found[0];
    if (!holdsPlace(room.members[id]) && taken(room) >= room.size) return { error: "busy" };
  } else {
    if (taken(room) >= room.size) return { error: "busy" };
    id = `g${room.nextGuest++}`;
  }
  const before = room.members[id] ?? emptyMember();
  room.members[id] = {
    ...emptyMember(before.gen + 1),
    connected: true,
    secret: id === HOST ? undefined : (before.secret ?? newSecret()),
  };
  presenceChanged(room, now);
  return { member: id, gen: room.members[id].gen, secret: room.members[id].secret };
}

/** A connection closed. Returns false for a connection that was already replaced. */
export function disconnect(room: Room, id: MemberId, gen: number, now: number): boolean {
  const member = room.members[id];
  if (!member || member.gen !== gen || !member.connected) return false;
  room.members[id] = { ...emptyMember(gen), secret: member.secret, leftAt: now };
  presenceChanged(room, now);
  return true;
}

/**
 * Someone hung up. A one-to-one call ends, unless it's the guest leaving before the host ever saw them (their place
 * is freed). In a group call they just leave; it ends once nobody is left.
 */
export function hangUp(room: Room, id: MemberId, now = 0): void {
  const member = room.members[id];
  if (!member) return;
  if (!isGroup(room)) {
    if (room.phase === "active" || id === HOST) room.phase = "ended";
    else delete room.members[id];
    return;
  }
  room.members[id] = { ...emptyMember(member.gen), secret: member.secret };
  if (inCall(room) === 0) room.phase = "ended";
  else presenceChanged(room, now);
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
  for (const [id, member] of Object.entries(room.members)) {
    if (member.leftAt === undefined || now < member.leftAt + RECONNECT_MS) continue;
    hangUp(room, id, now);
    changed = true;
    if ((room.phase as Room["phase"]) === "ended") return true;
  }
  if (room.aloneSince !== undefined && now >= room.aloneSince + ALONE_MS) {
    room.phase = "ended";
    return true;
  }
  return changed;
}

/** When tick (or the limits, or charging the account) has something to do next. */
export function nextDeadline(room: Room, now: number): number {
  const deadlines = [hardStop(room)];
  const m = room.meter;
  if (room.limits && m.activeAt !== undefined) {
    deadlines.push(m.activeAt + maxLength(room));
    if (together(room)) {
      deadlines.push(now + CHARGE_EVERY_MS);
      const view = limitsView(room, now);
      if (view.videoEndsAt !== undefined) deadlines.push(view.videoEndsAt);
      if (view.endsAt !== undefined) deadlines.push(view.endsAt);
    }
  }
  if (room.phase === "waiting") deadlines.push(room.createdAt + JOIN_WINDOW_MS);
  if (room.aloneSince !== undefined) deadlines.push(room.aloneSince + ALONE_MS);
  for (const member of everyone(room)) {
    if (member.leftAt !== undefined) deadlines.push(member.leftAt + RECONNECT_MS);
  }
  return Math.min(...deadlines);
}

/** Records what someone has started publishing; it's offered to the others once it's connected (announce). */
export function publish(room: Room, id: MemberId, session: string, names: string[]): boolean {
  const member = room.members[id];
  if (!member) return false;
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

export function announce(room: Room, id: MemberId): void {
  const member = room.members[id];
  if (!member) return;
  member.tracks = [...new Set([...member.tracks, ...member.pending])];
  member.pending = [];
}

/** Whether this person may receive these of another person's publications. */
export function canSubscribe(room: Room, id: MemberId, peer: MemberId, session: string, names: string[]): boolean {
  const publisher = room.members[peer];
  return (
    peer !== id &&
    publisher !== undefined &&
    publisher.connected &&
    publisher.pubSession === session &&
    names.length > 0 &&
    names.every((n) => publisher.tracks.includes(n))
  );
}

/** What this person needs to know about everyone else (the host first), and about the call's limits. */
export function callView(room: Room, id: MemberId, now: number): CallView {
  const peers = Object.entries(room.members)
    .filter(([key]) => key !== id)
    .map(([key, peer]) => ({
      id: key,
      present: peer.connected,
      session: peer.tracks.length ? peer.pubSession : undefined,
      tracks: peer.tracks,
      camera: peer.camera,
    }));
  // A one-to-one call shows its other person even before they join
  if (!isGroup(room) && peers.length === 0) {
    peers.push({ id: id === HOST ? "g1" : HOST, present: false, session: undefined, tracks: [], camera: false });
  }
  return { group: isGroup(room), peers, limits: limitsView(room, now) };
}

function sameSecret(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
