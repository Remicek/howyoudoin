// What each plan allows, and a person's call minutes for the month. Kept apart from the Durable Objects so the rules
// can be tested on their own.
//
// Only the HowYouDoin user who starts a call uses minutes; the person who joins from the link never pays anything. A
// call's time counts from when both are in it: as video while either camera is on, otherwise as audio.

export type Plan = "free" | "premium";

const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;

export interface PlanLimits {
  /** Video a month. */
  videoMs: number;
  /** Audio-only time a month. */
  audioMs: number;
  /** The longest one call can last, from when both are in it. */
  maxCallMs: number;
  /** Highest video sent (pixels tall) and its bitrate cap. */
  videoHeight: number;
  videoBitrate: number;
}

export const LIMITS: Record<Plan, PlanLimits> = {
  free: { videoMs: 60 * MINUTE, audioMs: 10 * HOUR, maxCallMs: 60 * MINUTE, videoHeight: 360, videoBitrate: 350_000 },
  // "Unlimited for normal use": the caps only stop someone running calls all day
  premium: { videoMs: 50 * HOUR, audioMs: 100 * HOUR, maxCallMs: 4 * HOUR, videoHeight: 540, videoBitrate: 700_000 },
};

/** What one top-up adds: video time that never expires, used once the month's video is gone. */
export const TOP_UP_VIDEO_MS = 5 * HOUR;
/** The Play product of the top-up, and of the subscription. */
export const TOP_UP_PRODUCT = "video_5h";
export const PREMIUM_PRODUCT = "premium_monthly";
/** How long before a call's end both people are told. */
export const WARN_BEFORE_END_MS = 5 * MINUTE;

export interface Usage {
  /** "2026-11": the month videoMs and audioMs belong to. */
  month: string;
  videoMs: number;
  audioMs: number;
  /** Bought video time left; carried over every month. */
  topUpMs: number;
}

export interface Allowance {
  plan: Plan;
  /** Video left now: the month's, then bought time. */
  videoLeftMs: number;
  audioLeftMs: number;
  /** Of videoLeftMs, how much is bought time. */
  topUpMs: number;
  /** When the month's allowance starts again (the 1st, 00:00 UTC). */
  resetsAt: number;
}

export const monthOf = (now: number): string => new Date(now).toISOString().slice(0, 7);

export function nextMonthStart(now: number): number {
  const date = new Date(now);
  return Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 1);
}

export const emptyUsage = (now: number): Usage => ({ month: monthOf(now), videoMs: 0, audioMs: 0, topUpMs: 0 });

/** A new month starts the month's counts again; bought time stays. */
export function current(usage: Usage | undefined, now: number): Usage {
  if (!usage) return emptyUsage(now);
  if (usage.month === monthOf(now)) return usage;
  return { ...emptyUsage(now), topUpMs: usage.topUpMs };
}

export function allowance(usage: Usage, plan: Plan, now: number): Allowance {
  const limits = LIMITS[plan];
  const monthVideo = Math.max(0, limits.videoMs - usage.videoMs);
  return {
    plan,
    videoLeftMs: monthVideo + usage.topUpMs,
    audioLeftMs: Math.max(0, limits.audioMs - usage.audioMs),
    topUpMs: usage.topUpMs,
    resetsAt: nextMonthStart(now),
  };
}

/** Counts a call's time: video comes from the month's allowance first, then from bought time. */
export function charge(usage: Usage, plan: Plan, videoMs: number, audioMs: number): Usage {
  const monthVideoLeft = Math.max(0, LIMITS[plan].videoMs - usage.videoMs);
  const fromMonth = Math.min(videoMs, monthVideoLeft);
  return {
    ...usage,
    videoMs: usage.videoMs + fromMonth,
    audioMs: usage.audioMs + audioMs,
    topUpMs: Math.max(0, usage.topUpMs - (videoMs - fromMonth)),
  };
}

/** Video in a charge that neither the month's allowance nor this account's bought time covers. */
export function uncoveredVideo(usage: Usage, plan: Plan, videoMs: number): number {
  const monthVideoLeft = Math.max(0, LIMITS[plan].videoMs - usage.videoMs);
  return Math.max(0, videoMs - monthVideoLeft - usage.topUpMs);
}

export const addTopUp = (usage: Usage): Usage => ({ ...usage, topUpMs: usage.topUpMs + TOP_UP_VIDEO_MS });

/** Takes video straight from bought time (a subscriber's, once the subscription's video is gone). */
export const useTopUp = (usage: Usage, videoMs: number): Usage => ({
  ...usage,
  topUpMs: Math.max(0, usage.topUpMs - videoMs),
});
