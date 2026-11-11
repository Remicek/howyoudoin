import { test } from "node:test";
import assert from "node:assert/strict";
import { allowance, charge, current, emptyUsage, LIMITS, nextMonthStart } from "../src/plans.ts";

const MIN = 60_000;
const NOV = Date.UTC(2026, 10, 10);
const DEC = Date.UTC(2026, 11, 2);

test("a free month: an hour of video, ten of audio, resetting on the 1st", () => {
  const left = allowance(emptyUsage(NOV), "free", NOV);
  assert.equal(left.videoLeftMs, 60 * MIN);
  assert.equal(left.audioLeftMs, 600 * MIN);
  assert.equal(left.resetsAt, Date.UTC(2026, 11, 1));
  assert.equal(nextMonthStart(Date.UTC(2026, 11, 31, 23)), Date.UTC(2027, 0, 1));
});

test("a call's time comes off the month's minutes, never below zero left", () => {
  let usage = charge(emptyUsage(NOV), 50 * MIN, 5 * MIN);
  assert.equal(allowance(usage, "free", NOV).videoLeftMs, 10 * MIN);
  usage = charge(usage, 30 * MIN, 0);
  assert.equal(usage.videoMs, 80 * MIN);
  assert.equal(allowance(usage, "free", NOV).videoLeftMs, 0);
  assert.equal(allowance(usage, "free", NOV).audioLeftMs, 595 * MIN);
});

test("a new month starts the counts again; bought time from older versions is dropped", () => {
  const used = charge(emptyUsage(NOV), 90 * MIN, 60 * MIN);
  assert.deepEqual(current(used, DEC), { month: "2026-12", videoMs: 0, audioMs: 0 });
  assert.deepEqual(current(used, NOV), used);
  const old = { ...used, topUpMs: 5 * 60 * MIN } as typeof used;
  assert.deepEqual(current(old, NOV), used);
});

test("Premium allows far more and better video", () => {
  const left = allowance(emptyUsage(NOV), "premium", NOV);
  assert.equal(left.videoLeftMs, 50 * 60 * MIN);
  assert.ok(LIMITS.premium.videoHeight > LIMITS.free.videoHeight);
  assert.ok(LIMITS.premium.maxCallMs > LIMITS.free.maxCallMs);
});
