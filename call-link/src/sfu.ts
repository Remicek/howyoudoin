// Cloudflare Realtime SFU's Connection API, called with the app's secret (never sent to the browser).
// https://developers.cloudflare.com/realtime/sfu/api/

export interface SessionDescription {
  type: "offer" | "answer";
  sdp: string;
}

export interface TrackResult {
  mid?: string;
  trackName?: string;
  sessionId?: string;
  errorCode?: string;
  errorDescription?: string;
}

export interface TracksResponse {
  sessionDescription?: SessionDescription;
  requiresImmediateRenegotiation?: boolean;
  tracks?: TrackResult[];
}

export interface IceServer {
  urls: string | string[];
  username?: string;
  credential?: string;
}

export class SfuError extends Error {}

const BASE = "https://rtc.live.cloudflare.com/v1";

export class Sfu {
  private readonly appId: string;
  private readonly appSecret: string;
  private readonly fetcher: typeof fetch;

  // Workers' fetch can't be called as another object's method, hence the arrow
  constructor(appId: string, appSecret: string, fetcher: typeof fetch = (input, init) => fetch(input, init)) {
    this.appId = appId;
    this.appSecret = appSecret;
    this.fetcher = fetcher;
  }

  async newSession(): Promise<string> {
    const body = (await this.call("POST", "/sessions/new")) as { sessionId?: string };
    if (!body.sessionId) throw new SfuError("No session ID");
    return body.sessionId;
  }

  /** Publishes (local) or subscribes to (remote) tracks; fails if any track failed. */
  async newTracks(sessionId: string, request: unknown): Promise<TracksResponse> {
    const body = (await this.call("POST", `/sessions/${sessionId}/tracks/new`, request)) as TracksResponse;
    const failed = body.tracks?.find((t) => t.errorCode);
    if (failed) throw new SfuError(`Track ${failed.trackName ?? failed.mid}: ${failed.errorCode}`);
    return body;
  }

  async renegotiate(sessionId: string, answer: SessionDescription): Promise<void> {
    await this.call("PUT", `/sessions/${sessionId}/renegotiate`, { sessionDescription: answer });
  }

  private async call(method: string, path: string, body?: unknown): Promise<unknown> {
    const response = await this.fetcher(`${BASE}/apps/${this.appId}${path}`, {
      method,
      headers: { Authorization: `Bearer ${this.appSecret}`, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const json = (await response.json().catch(() => ({}))) as { errorCode?: string; errorDescription?: string };
    if (!response.ok || json.errorCode) {
      throw new SfuError(`SFU ${method} ${path}: ${response.status} ${json.errorCode ?? ""} ${json.errorDescription ?? ""}`);
    }
    return json;
  }
}

const STUN: IceServer[] = [{ urls: "stun:stun.cloudflare.com:3478" }];

/**
 * STUN, plus Cloudflare TURN when a TURN key is set up: it relays calls through networks that block direct WebRTC
 * (some offices and mobile carriers). Port 53 is dropped: browsers block it and would wait for it to time out.
 */
export async function iceServers(
  turnKeyId: string | undefined,
  turnKeyToken: string | undefined,
  ttlSeconds: number,
  fetcher: typeof fetch = fetch,
): Promise<IceServer[]> {
  if (!turnKeyId || !turnKeyToken) return STUN;
  try {
    const response = await fetcher(`${BASE}/turn/keys/${turnKeyId}/credentials/generate-ice-servers`, {
      method: "POST",
      headers: { Authorization: `Bearer ${turnKeyToken}`, "Content-Type": "application/json" },
      body: JSON.stringify({ ttl: ttlSeconds }),
    });
    if (!response.ok) throw new Error(`TURN ${response.status}`);
    const { iceServers: servers } = (await response.json()) as { iceServers: IceServer[] };
    return servers.map((server) => ({
      ...server,
      urls: ([] as string[]).concat(server.urls).filter((url) => !/:53(\?|$)/.test(url)),
    }));
  } catch (e) {
    console.error("No TURN credentials", e);
    return STUN;
  }
}
