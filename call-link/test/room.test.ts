import { test } from "node:test";
import assert from "node:assert/strict";
import {
  admit,
  announce,
  canSubscribe,
  disconnect,
  hangUp,
  JOIN_WINDOW_MS,
  MAX_CALL_MS,
  newRoom,
  nextDeadline,
  callView,
  GROUP_SIZE,
  ALONE_MS,
  rate,
  publish,
  RECONNECT_MS,
  type Room,
  tick,
  meter,
  enforce,
  takeCharge,
  refreshLimits,
  limitsView,
} from "../src/room.ts";

const secret = () => "guest-secret";
/** What one person sees of the other in a one-to-one call. */
const peerView = (room: Room, id: string, now: number) => {
  const view = callView(room, id, now);
  return { ...view.peers[0], limits: view.limits };
};
const strip = ({ id: _id, ...rest }: { id: string } & Record<string, unknown>) => rest;
/** What a call made before plans shows: no limits, 540p. */
const noLimits = { limits: { videoAllowed: true, videoHeight: 540, videoBitrate: 700_000, now: 0 } };

function call(): Room {
  const room = newRoom("host-token", 0);
  admit(room, { token: "host-token" }, secret);
  return room;
}

test("the host joins with the call's token; a wrong token is refused", () => {
  const room = newRoom("host-token", 0);
  assert.deepEqual(admit(room, { token: "nope" }, secret), { error: "forbidden" });
  assert.deepEqual(admit(room, { token: "host-token" }, secret), { member: "host", gen: 1, secret: undefined });
  assert.equal(room.phase, "waiting");
});

test("one guest gets in and gets a secret; a second person with the link doesn't", () => {
  const room = call();
  assert.deepEqual(admit(room, {}, secret), { member: "g1", gen: 1, secret: "guest-secret" });
  assert.equal(room.phase, "active");
  assert.deepEqual(admit(room, {}, secret), { error: "busy" });
  assert.deepEqual(admit(room, { secret: "wrong-secret" }, secret), { error: "forbidden" });
});

test("a dropped guest comes back with their secret, and their old connection no longer counts", () => {
  const room = call();
  admit(room, {}, secret);
  assert.equal(disconnect(room, "g1", 1, 1000), true);
  assert.equal(peerView(room, "host", 0).present, false);
  assert.equal(nextDeadline(room, 0), 1000 + RECONNECT_MS);
  assert.deepEqual(admit(room, { secret: "guest-secret" }, secret), { member: "g1", gen: 2, secret: "guest-secret" });
  // The first connection's close arriving late changes nothing
  assert.equal(disconnect(room, "g1", 1, 2000), false);
  assert.equal(room.members.g1.connected, true);
  assert.equal(tick(room, 1000 + RECONNECT_MS), false);
  assert.equal(room.phase, "active");
});

test("someone away longer than the reconnect window ends the call", () => {
  const room = call();
  admit(room, {}, secret);
  disconnect(room, "host", 1, 1000);
  assert.equal(tick(room, 1000 + RECONNECT_MS - 1), false);
  assert.equal(tick(room, 1000 + RECONNECT_MS), true);
  assert.equal(room.phase, "ended");
  assert.deepEqual(admit(room, { token: "host-token" }, secret), { error: "ended" });
});

test("a guest leaving before the host saw them frees the place; the host leaving ends the call", () => {
  const room = newRoom("host-token", 0);
  admit(room, {}, secret);
  hangUp(room, "g1");
  assert.equal(room.phase, "waiting");
  assert.deepEqual(admit(room, {}, () => "second"), { member: "g2", gen: 1, secret: "second" });
  hangUp(room, "host");
  assert.equal(room.phase, "ended");
});

test("after both were in the call, either one hanging up ends it", () => {
  const room = call();
  admit(room, {}, secret);
  hangUp(room, "g1");
  assert.equal(room.phase, "ended");
});

test("a call nobody joins ends after the join window; any call ends after the longest call", () => {
  const unjoined = call();
  assert.equal(nextDeadline(unjoined, 0), JOIN_WINDOW_MS);
  assert.equal(tick(unjoined, JOIN_WINDOW_MS), true);
  assert.equal(unjoined.phase, "ended");

  const long = call();
  admit(long, {}, secret);
  assert.equal(tick(long, JOIN_WINDOW_MS), false);
  assert.equal(nextDeadline(long, 0), MAX_CALL_MS);
  assert.equal(tick(long, MAX_CALL_MS), true);
});

test("published tracks are offered to the other person only once announced", () => {
  const room = call();
  admit(room, {}, secret);
  assert.equal(publish(room, "host", "s1", ["audio", "video1"]), true);
  assert.deepEqual(strip(peerView(room, "g1", 0)), { ...noLimits, present: true, session: undefined, tracks: [], camera: false });
  assert.equal(canSubscribe(room, "g1", "host", "s1", ["audio"]), false);
  announce(room, "host");
  assert.deepEqual(peerView(room, "g1", 0).tracks, ["audio", "video1"]);
  assert.equal(canSubscribe(room, "g1", "host", "s1", ["audio", "video1"]), true);
  // Not from another session, not unpublished tracks, and not one's own
  assert.equal(canSubscribe(room, "g1", "host", "s2", ["audio"]), false);
  assert.equal(canSubscribe(room, "g1", "host", "s1", ["video2"]), false);
  assert.equal(canSubscribe(room, "host", "host", "s1", ["audio"]), false);
});

test("only known track names, each once, from one session", () => {
  const room = call();
  assert.equal(publish(room, "host", "s1", ["screen"]), false);
  assert.equal(publish(room, "host", "s1", ["video0"]), false);
  assert.equal(publish(room, "host", "s1", ["audio", "audio"]), false);
  assert.equal(publish(room, "host", "s1", ["audio"]), true);
  assert.equal(publish(room, "host", "s1", ["audio"]), false);
  assert.equal(publish(room, "host", "s2", ["video1"]), false);
  assert.equal(publish(room, "host", "s1", ["video1"]), true);
});

test("joining again starts over: new sessions, nothing published", () => {
  const room = call();
  publish(room, "host", "s1", ["audio"]);
  announce(room, "host");
  room.members.host.camera = true;
  disconnect(room, "host", 1, 10);
  admit(room, { token: "host-token" }, secret);
  assert.deepEqual(strip(peerView(room, "g1", 0)), { ...noLimits, present: true, session: undefined, tracks: [], camera: false });
  assert.equal(publish(room, "host", "s9", ["audio"]), true);
});

// Plans: the host's minutes are counted while both are in the call

const MIN = 60_000;

function limitedCall(videoLeftMs: number, audioLeftMs: number, plan: "free" | "premium" = "free"): Room {
  const room = newRoom("host-token", 0, { plan, owner: "install:x", videoLeftMs, audioLeftMs });
  admit(room, { token: "host-token" }, secret);
  meter(room, 0);
  admit(room, {}, secret);
  meter(room, 0);
  return room;
}

test("time counts as video while a camera is on, else as audio, only while both are in", () => {
  const room = limitedCall(60 * MIN, 600 * MIN);
  meter(room, 2 * MIN);
  room.members.g1.camera = true;
  meter(room, 5 * MIN);
  room.members.g1.camera = false;
  disconnect(room, "g1", 1, 5 * MIN);
  meter(room, 8 * MIN);
  assert.equal(room.meter.audioMs, 2 * MIN);
  assert.equal(room.meter.videoMs, 3 * MIN);
  assert.deepEqual(takeCharge(room), { videoMs: 3 * MIN, audioMs: 2 * MIN });
  assert.deepEqual(takeCharge(room), { videoMs: 0, audioMs: 0 });
});

test("video stops when its minutes are gone; the call goes on as audio", () => {
  const room = limitedCall(10 * MIN, 600 * MIN);
  room.members.host.camera = true;
  assert.equal(limitsView(room, 0).videoEndsAt, 10 * MIN);
  meter(room, 10 * MIN);
  assert.equal(enforce(room, 10 * MIN), true);
  assert.equal(room.meter.videoAllowed, false);
  assert.equal(room.members.host.camera, false);
  assert.equal(room.phase, "active");
  assert.equal(peerView(room, "g1", 10 * MIN).limits.videoAllowed, false);
});

test("a free call ends after an hour, and is told when", () => {
  const room = limitedCall(60 * MIN, 600 * MIN);
  assert.equal(limitsView(room, 0).endsAt, 60 * MIN);
  assert.ok(nextDeadline(room, 0) <= MIN, "charges every minute");
  meter(room, 60 * MIN);
  assert.equal(enforce(room, 60 * MIN), true);
  assert.equal(room.phase, "ended");
  assert.equal(room.endReason, "length");
});

test("the call ends when the audio minutes run out", () => {
  const room = limitedCall(0, 3 * MIN, "premium");
  assert.equal(room.meter.videoAllowed, false);
  assert.equal(limitsView(room, 0).endsAt, 3 * MIN);
  meter(room, 3 * MIN);
  enforce(room, 3 * MIN);
  assert.equal(room.endReason, "minutes");
});

test("waiting for the guest uses nothing", () => {
  const room = newRoom("host-token", 0, { plan: "free", owner: "install:x", videoLeftMs: 0, audioLeftMs: 0 });
  admit(room, { token: "host-token" }, secret);
  meter(room, 5 * MIN);
  assert.equal(enforce(room, 5 * MIN), false);
  assert.equal(room.phase, "waiting");
  assert.equal(room.meter.audioMs, 0);
});

test("another call using the same minutes shortens this one at the next charge", () => {
  const room = limitedCall(60 * MIN, 600 * MIN);
  room.members.host.camera = true;
  meter(room, 10 * MIN);
  takeCharge(room);
  // The account had 60 min; this call used 10 and another call 30: 20 are left
  refreshLimits(room, { videoLeftMs: 20 * MIN, audioLeftMs: 600 * MIN });
  assert.equal(room.limits?.videoLeftMs, 30 * MIN);
  assert.equal(limitsView(room, 10 * MIN).videoEndsAt, 30 * MIN);
  meter(room, 30 * MIN);
  assert.equal(enforce(room, 30 * MIN), true);
  assert.equal(room.meter.videoAllowed, false);
});

test("a Premium call gets its 4 hours even when the guest joined late", () => {
  const room = newRoom("host-token", 0, { plan: "premium", owner: "sub:x", videoLeftMs: 0, audioLeftMs: 6000 * MIN });
  admit(room, { token: "host-token" }, secret);
  meter(room, 0);
  admit(room, {}, secret);
  meter(room, 10 * MIN);
  assert.equal(tick(room, 4 * 60 * MIN + 5 * MIN), false);
  assert.equal(room.phase, "active");
  assert.equal(limitsView(room, 10 * MIN).endsAt, 4 * 60 * MIN + 10 * MIN);
  meter(room, 4 * 60 * MIN + 10 * MIN);
  enforce(room, 4 * 60 * MIN + 10 * MIN);
  assert.equal(room.endReason, "length");
});

// Group calls

function group(): Room {
  const room = newRoom("host-token", 0, { plan: "premium", owner: "sub:x", videoLeftMs: 0, audioLeftMs: 6000 * MIN }, GROUP_SIZE);
  admit(room, { token: "host-token" }, secret, 0);
  return room;
}

test("a group call takes up to GROUP_SIZE people, each with their own secret", () => {
  const room = group();
  let n = 0;
  for (let i = 1; i < GROUP_SIZE; i++) {
    const admission = admit(room, {}, () => `s${++n}`, 0);
    assert.deepEqual(admission, { member: `g${i}`, gen: 1, secret: `s${n}` });
  }
  assert.deepEqual(admit(room, {}, secret, 0), { error: "busy" });
  assert.equal(callView(room, "g1", 0).peers.length, GROUP_SIZE - 1);
  assert.equal(callView(room, "g1", 0).group, true);
  // Someone coming back with their secret is the same person
  disconnect(room, "g2", 1, 10);
  assert.deepEqual(admit(room, { secret: "s2" }, secret, 20), { member: "g2", gen: 2, secret: "s2" });
});

test("in a group call hanging up leaves; whoever left frees their place and can come back", () => {
  const room = group();
  admit(room, {}, () => "a", 0);
  admit(room, {}, () => "b", 0);
  hangUp(room, "host", 0);
  assert.equal(room.phase, "active");
  hangUp(room, "g1", 0);
  assert.equal(room.phase, "active");
  assert.deepEqual(admit(room, { secret: "a" }, secret, 0), { member: "g1", gen: 2, secret: "a" });
  hangUp(room, "g1", 0);
  hangUp(room, "g2", 0);
  assert.equal(room.phase, "ended");
});

test("a group call left with one person ends after ALONE_MS, unless someone comes", () => {
  const room = group();
  admit(room, {}, () => "a", 0);
  hangUp(room, "g1", 100);
  assert.equal(room.aloneSince, 100);
  assert.equal(nextDeadline(room, 100) <= 100 + ALONE_MS, true);
  assert.equal(tick(room, 100 + ALONE_MS - 1), false);
  admit(room, {}, () => "b", 200);
  assert.equal(room.aloneSince, undefined);
  hangUp(room, "g2", 300);
  assert.equal(tick(room, 300 + ALONE_MS), true);
  assert.equal(room.phase, "ended");
});

test("a dropped connection in a group call only leaves once the reconnect window is over", () => {
  const room = group();
  admit(room, {}, () => "a", 0);
  admit(room, {}, () => "b", 0);
  disconnect(room, "g1", 1, 1000);
  assert.equal(tick(room, 1000 + RECONNECT_MS), true);
  assert.equal(room.phase, "active");
  assert.equal(room.members.g1.leftAt, undefined);
});

test("with n people in a group call the minutes go n - 1 times as fast", () => {
  const room = group();
  meter(room, 0);
  admit(room, {}, () => "a", 0);
  meter(room, 0);
  meter(room, MIN);
  assert.equal(room.meter.audioMs, MIN);
  admit(room, {}, () => "b", MIN);
  admit(room, {}, () => "c", MIN);
  assert.equal(rate(room), 3);
  meter(room, 2 * MIN);
  assert.equal(room.meter.audioMs, 4 * MIN);
  // The end moves closer accordingly: 5996 minutes left at 3x
  assert.equal(limitsView(room, 2 * MIN).endsAt, Math.min(2 * MIN + (5996 * MIN) / 3, 4 * 60 * MIN));
});

test("each person in a group call can receive each other person's tracks", () => {
  const room = group();
  admit(room, {}, () => "a", 0);
  admit(room, {}, () => "b", 0);
  publish(room, "g1", "s1", ["audio"]);
  announce(room, "g1");
  assert.equal(canSubscribe(room, "g2", "g1", "s1", ["audio"]), true);
  assert.equal(canSubscribe(room, "host", "g1", "s1", ["audio"]), true);
  assert.equal(canSubscribe(room, "g1", "g1", "s1", ["audio"]), false);
  assert.equal(canSubscribe(room, "g2", "g3", "s1", ["audio"]), false);
});
