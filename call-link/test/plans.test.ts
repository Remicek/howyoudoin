import { test } from "node:test";
import assert from "node:assert/strict";
import {
  addTopUp,
  allowance,
  charge,
  current,
  emptyUsage,
  LIMITS,
  nextMonthStart,
  TOP_UP_VIDEO_MS,
  uncoveredVideo,
  useTopUp,
} from "../src/plans.ts";

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

test("video uses the month's minutes first, then bought time", () => {
  let usage = addTopUp(emptyUsage(NOV));
  usage = charge(usage, "free", 50 * MIN, 5 * MIN);
  assert.equal(allowance(usage, "free", NOV).videoLeftMs, 10 * MIN + TOP_UP_VIDEO_MS);
  usage = charge(usage, "free", 30 * MIN, 0);
  assert.equal(usage.videoMs, 60 * MIN);
  assert.equal(usage.topUpMs, TOP_UP_VIDEO_MS - 20 * MIN);
  assert.equal(allowance(usage, "free", NOV).audioLeftMs, 595 * MIN);
});

test("a new month starts the counts again but keeps bought time", () => {
  const used = charge(addTopUp(emptyUsage(NOV)), "free", 90 * MIN, 60 * MIN);
  const next = current(used, DEC);
  assert.deepEqual(next, { month: "2026-12", videoMs: 0, audioMs: 0, topUpMs: TOP_UP_VIDEO_MS - 30 * MIN });
  assert.equal(current(used, NOV), used);
});

test("Premium allows far more and better video", () => {
  const left = allowance(emptyUsage(NOV), "premium", NOV);
  assert.equal(left.videoLeftMs, 50 * 60 * MIN);
  assert.ok(LIMITS.premium.videoHeight > LIMITS.free.videoHeight);
  assert.ok(LIMITS.premium.maxCallMs > LIMITS.free.maxCallMs);
});

test("video past the month's and the account's bought time is uncovered (a subscriber's install pays it)", () => {
  const usage = { ...emptyUsage(NOV), videoMs: 50 * 60 * MIN - 2 * MIN };
  assert.equal(uncoveredVideo(usage, "premium", 5 * MIN), 3 * MIN);
  assert.equal(uncoveredVideo(usage, "premium", MIN), 0);
  const bought = useTopUp(addTopUp(emptyUsage(NOV)), 3 * MIN);
  assert.equal(bought.topUpMs, TOP_UP_VIDEO_MS - 3 * MIN);
  assert.equal(useTopUp(bought, 10 * 60 * MIN).topUpMs, 0);
});
