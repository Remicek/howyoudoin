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
  peerView,
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
  assert.deepEqual(admit(room, { token: "host-token" }, secret), { role: "host", gen: 1, secret: undefined });
  assert.equal(room.phase, "waiting");
});

test("one guest gets in and gets a secret; a second person with the link doesn't", () => {
  const room = call();
  assert.deepEqual(admit(room, {}, secret), { role: "guest", gen: 1, secret: "guest-secret" });
  assert.equal(room.phase, "active");
  assert.deepEqual(admit(room, {}, secret), { error: "busy" });
  assert.deepEqual(admit(room, { secret: "wrong-secret" }, secret), { error: "forbidden" });
});

test("a dropped guest comes back with their secret, and their old connection no longer counts", () => {
  const room = call();
  admit(room, {}, secret);
  assert.equal(disconnect(room, "guest", 1, 1000), true);
  assert.equal(peerView(room, "host", 0).present, false);
  assert.equal(nextDeadline(room, 0), 1000 + RECONNECT_MS);
  assert.deepEqual(admit(room, { secret: "guest-secret" }, secret), { role: "guest", gen: 2, secret: "guest-secret" });
  // The first connection's close arriving late changes nothing
  assert.equal(disconnect(room, "guest", 1, 2000), false);
  assert.equal(room.guest.connected, true);
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
  hangUp(room, "guest");
  assert.equal(room.phase, "waiting");
  assert.deepEqual(admit(room, {}, () => "second"), { role: "guest", gen: 2, secret: "second" });
  hangUp(room, "host");
  assert.equal(room.phase, "ended");
});

test("after both were in the call, either one hanging up ends it", () => {
  const room = call();
  admit(room, {}, secret);
  hangUp(room, "guest");
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
  assert.deepEqual(peerView(room, "guest", 0), { ...noLimits, present: true, session: undefined, tracks: [], camera: false });
  assert.equal(canSubscribe(room, "guest", "s1", ["audio"]), false);
  announce(room, "host");
  assert.deepEqual(peerView(room, "guest", 0).tracks, ["audio", "video1"]);
  assert.equal(canSubscribe(room, "guest", "s1", ["audio", "video1"]), true);
  // Not from another session, not unpublished tracks, and not one's own
  assert.equal(canSubscribe(room, "guest", "s2", ["audio"]), false);
  assert.equal(canSubscribe(room, "guest", "s1", ["video2"]), false);
  assert.equal(canSubscribe(room, "host", "s1", ["audio"]), false);
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
  room.host.camera = true;
  disconnect(room, "host", 1, 10);
  admit(room, { token: "host-token" }, secret);
  assert.deepEqual(peerView(room, "guest", 0), { ...noLimits, present: true, session: undefined, tracks: [], camera: false });
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
  room.guest.camera = true;
  meter(room, 5 * MIN);
  room.guest.camera = false;
  disconnect(room, "guest", 1, 5 * MIN);
  meter(room, 8 * MIN);
  assert.equal(room.meter.audioMs, 2 * MIN);
  assert.equal(room.meter.videoMs, 3 * MIN);
  assert.deepEqual(takeCharge(room), { videoMs: 3 * MIN, audioMs: 2 * MIN });
  assert.deepEqual(takeCharge(room), { videoMs: 0, audioMs: 0 });
});

test("video stops when its minutes are gone; the call goes on as audio", () => {
  const room = limitedCall(10 * MIN, 600 * MIN);
  room.host.camera = true;
  assert.equal(limitsView(room, 0).videoEndsAt, 10 * MIN);
  meter(room, 10 * MIN);
  assert.equal(enforce(room, 10 * MIN), true);
  assert.equal(room.meter.videoAllowed, false);
  assert.equal(room.host.camera, false);
  assert.equal(room.phase, "active");
  assert.equal(peerView(room, "guest", 10 * MIN).limits.videoAllowed, false);
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
  room.host.camera = true;
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
