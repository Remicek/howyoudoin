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
} from "../src/room.ts";

const secret = () => "guest-secret";

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
  assert.equal(peerView(room, "host").present, false);
  assert.equal(nextDeadline(room), 1000 + RECONNECT_MS);
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
  assert.equal(nextDeadline(unjoined), JOIN_WINDOW_MS);
  assert.equal(tick(unjoined, JOIN_WINDOW_MS), true);
  assert.equal(unjoined.phase, "ended");

  const long = call();
  admit(long, {}, secret);
  assert.equal(tick(long, JOIN_WINDOW_MS), false);
  assert.equal(nextDeadline(long), MAX_CALL_MS);
  assert.equal(tick(long, MAX_CALL_MS), true);
});

test("published tracks are offered to the other person only once announced", () => {
  const room = call();
  admit(room, {}, secret);
  assert.equal(publish(room, "host", "s1", ["audio", "video1"]), true);
  assert.deepEqual(peerView(room, "guest"), { present: true, session: undefined, tracks: [], camera: false });
  assert.equal(canSubscribe(room, "guest", "s1", ["audio"]), false);
  announce(room, "host");
  assert.deepEqual(peerView(room, "guest").tracks, ["audio", "video1"]);
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
  assert.deepEqual(peerView(room, "guest"), { present: true, session: undefined, tracks: [], camera: false });
  assert.equal(publish(room, "host", "s9", ["audio"]), true);
});
