import { useState } from "react";
import { CheckCircle2, X, XCircle } from "lucide-react";
import { api, ApiError, getBase, getToken, setConnection } from "../lib/api";

export function SettingsModal({ onClose }: { onClose: () => void }) {
  const [base, setBase] = useState(getBase());
  const [token, setToken] = useState(getToken());
  const [key, setKey] = useState("");
  const [apiBase, setApiBase] = useState("https://openrouter.ai/api/v1");
  const [model, setModel] = useState("qwen/qwen3.8-flash");
  const [result, setResult] = useState<{ ok: boolean; detail: string } | null>(null);
  const [error, setError] = useState("");

  const test = async () => {
    setError(""); setResult(null);
    try { setResult(await api.testLlm({ api_key: key, api_base: apiBase, model })); }
    catch (e) { setError(e instanceof ApiError ? e.message : String(e)); }
  };

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/70 p-4 backdrop-blur-sm" onClick={onClose}>
      <div className="w-full max-w-lg rounded-2xl border border-line bg-surface shadow-2xl" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <div>
            <div className="label">settings</div>
            <h2 className="mt-1 font-display text-base font-semibold">Connection & model</h2>
          </div>
          <button onClick={onClose} className="btn-ghost h-8 w-8 rounded-lg"><X className="h-4 w-4" /></button>
        </div>
        <div className="space-y-4 p-5">
          <label className="block">
            <span className="label">API base</span>
            <input className="field mt-2 font-mono" value={base} onChange={(e) => setBase(e.target.value)} placeholder="/api" />
          </label>
          <label className="block">
            <span className="label">Agent token</span>
            <input className="field mt-2 font-mono" type="password" value={token} onChange={(e) => setToken(e.target.value)} placeholder="optional" />
          </label>
          <div className="grid grid-cols-2 gap-3">
            <label className="block">
              <span className="label">API key</span>
              <input className="field mt-2 font-mono" type="password" value={key} onChange={(e) => setKey(e.target.value)} placeholder="sk-or-…" />
            </label>
            <label className="block">
              <span className="label">Model</span>
              <input className="field mt-2 font-mono" value={model} onChange={(e) => setModel(e.target.value)} />
            </label>
          </div>
          <label className="block">
            <span className="label">API base URL</span>
            <input className="field mt-2 font-mono" value={apiBase} onChange={(e) => setApiBase(e.target.value)} />
          </label>

          {error && <p className="rounded-lg border border-danger/30 bg-danger/5 px-3 py-2 text-xs text-danger">{error}</p>}
          {result && (
            <div className={`flex items-start gap-2 rounded-lg border px-3 py-2 text-xs ${result.ok ? "border-sage/30 bg-sage/5 text-sage" : "border-danger/30 bg-danger/5 text-danger"}`}>
              {result.ok ? <CheckCircle2 className="mt-0.5 h-3.5 w-3.5" /> : <XCircle className="mt-0.5 h-3.5 w-3.5" />}
              <span>{result.detail}</span>
            </div>
          )}

          <div className="flex justify-end gap-2 pt-2">
            <button onClick={() => { setConnection(base, token); onClose(); }} className="btn-ghost">Save & close</button>
            <button onClick={() => void test()} disabled={!key.trim()} className="rounded-xl bg-persimmon px-4 py-2 text-sm font-semibold text-ink disabled:opacity-40">Test model</button>
          </div>
        </div>
      </div>
    </div>
  );
}
