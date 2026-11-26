import type { AppRow, Crawl, MapRow, OrbitTask, UsageData } from "./types";

const KEY_BASE = "orbit.base";
const KEY_TOKEN = "orbit.token";

type NativeBridge = {
  saveConfig?: (json: string) => string | Promise<string>;
  testLlm?: (json: string) => string | Promise<string>;
  restartAgent?: () => void;
};

function nativeBridge(): NativeBridge | undefined {
  return (window as unknown as { OrbitNative?: NativeBridge }).OrbitNative;
}

function embedded(): boolean {
  const host = window as unknown as { __ORBIT_EMBEDDED__?: boolean };
  return host.__ORBIT_EMBEDDED__ === true || window.location.protocol === "file:";
}

export function getBase(): string {
  const host = window as unknown as { __ORBIT_BASE__?: string };
  if (embedded()) return host.__ORBIT_BASE__ || "http://127.0.0.1:8765";
  return localStorage.getItem(KEY_BASE) || "/api";
}
export function getToken(): string {
  const host = window as unknown as { __ORBIT_TOKEN__?: string };
  if (embedded()) return host.__ORBIT_TOKEN__ || "";
  return localStorage.getItem(KEY_TOKEN) || "";
}
export function setConnection(base: string, token: string) {
  localStorage.setItem(KEY_BASE, base || "/api");
  localStorage.setItem(KEY_TOKEN, token);
}

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function request<T = unknown>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken();
  const headers: Record<string, string> = { ...(init.headers as Record<string, string>) };
  if (init.body) headers["Content-Type"] = "application/json";
  if (token) headers["X-Agent-Token"] = token;
  let res: Response;
  try {
    res = await fetch(`${getBase()}${path}`, { ...init, headers });
  } catch {
    throw new ApiError(0, "The local Orbit agent is still starting. If you just saved settings, wait a few seconds; otherwise open Settings and Save.");
  }
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`;
    try {
      const body = await res.json();
      detail = body.error || body.detail || detail;
    } catch {
      /* non-json error */
    }
    throw new ApiError(res.status, detail);
  }
  return res.json() as Promise<T>;
}

const post = <T = unknown>(path: string, body: unknown = {}) =>
  request<T>(path, { method: "POST", body: JSON.stringify(body) });

export const api = {
  tasks: () => request<{ tasks: Record<string, OrbitTask> }>("/tasks"),
  task: (id: string) => request<OrbitTask>(`/task/${id}`),
  startTask: (body: {
    text: string;
    autonomous?: boolean;
    maps?: boolean;
    shell?: boolean;
    display?: number;
  }) => post<{ id: string }>("/task", body),
  cancelTask: (id: string) => post<{ ok: boolean }>(`/task/${id}/cancel`),
  answerTask: (id: string, text: string) => post<{ ok: boolean }>(`/task/${id}/answer`, { text }),

  maps: () => request<{ maps: MapRow[]; count: number }>("/maps"),
  pinMap: (packageName: string, file: string) => post("/maps/pin", { package: packageName, file }),
  unpinMap: (packageName: string) => post("/maps/unpin", { package: packageName }),
  deleteMap: (file: string) => post("/maps/delete", { file }),
  crawlApps: () => request<{ apps: AppRow[] }>("/crawl/apps"),
  crawl: (body: Record<string, unknown>) => post<{ id: string; output: string }>("/crawl", body),
  crawlStatus: (id: string) => request<Crawl>(`/crawl/${id}`),
  cancelCrawl: (id: string) => post(`/crawl/${id}/cancel`),
  bench: (map: string, limit = 8) => post<{ id: string }>("/bench", { map, limit }),
  benchStatus: (id: string) => request<{ state: string; rows: unknown[]; summary: Record<string, number> }>(`/bench/${id}`),
  warmup: () => post<{ warmed: string[] }>("/warmup"),

  usage: (days = 7) => request<UsageData>(`/usage?days=${days}`),
  frameUrl: () => `${getBase()}/frame`,
  frame: async (): Promise<string> => {
    const token = getToken();
    const res = await fetch(`${getBase()}/frame`, {
      headers: token ? { "X-Agent-Token": token } : {},
      cache: "no-store",
    });
    if (!res.ok) throw new ApiError(res.status, `frame ${res.status}`);
    return URL.createObjectURL(await res.blob());
  },
  testLlm: async (body: { api_key: string; api_base?: string; model?: string; vision_model?: string }) => {
    const bridge = nativeBridge();
    if (embedded() && bridge?.testLlm) {
      return JSON.parse(await bridge.testLlm(JSON.stringify(body))) as { ok: boolean; detail: string };
    }
    return post<{ ok: boolean; detail: string }>("/test-llm", body);
  },
  saveConfig: async (body: { api_key: string; api_base?: string; model?: string; vision_model?: string }) => {
    const bridge = nativeBridge();
    if (embedded() && bridge?.saveConfig) {
      const raw = await bridge.saveConfig(JSON.stringify(body));
      const saved = JSON.parse(raw) as { ok: boolean; detail: string; restart_required?: boolean };
      if (saved.ok) bridge.restartAgent?.();
      return saved;
    }
    return post<{ ok: boolean; detail: string; restart_required?: boolean }>("/config", body);
  },
  markRisk: (packageName: string, note?: string) => post("/risk", { package: packageName, note }),
};
