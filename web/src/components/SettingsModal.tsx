import { useState } from "react";
import { CheckCircle2, Eye, EyeOff, X, XCircle } from "lucide-react";
import { api, ApiError, getBase } from "../lib/api";

type Provider = {
  id: string;
  label: string;
  base: string;
  model: string;
  hint: string;
};

const PROVIDERS: Provider[] = [
  { id: "groq", label: "Groq", base: "https://api.groq.com/openai/v1", model: "openai/gpt-oss-120b", hint: "gsk_" },
  { id: "deepseek", label: "DeepSeek", base: "https://api.deepseek.com", model: "deepseek-flash", hint: "sk-" },
  { id: "openrouter", label: "OpenRouter", base: "https://openrouter.ai/api/v1", model: "qwen/qwen3.8-flash", hint: "sk-or-" },
  { id: "openai", label: "OpenAI", base: "https://api.openai.com/v1", model: "gpt-4o-mini", hint: "sk-" },
  { id: "anthropic", label: "Anthropic", base: "https://api.anthropic.com/v1", model: "claude-3-5-haiku-latest", hint: "sk-ant-" },
  { id: "custom", label: "Custom", base: "", model: "", hint: "" },
];

export function SettingsModal({ onClose }: { onClose: () => void }) {
  const [providerId, setProviderId] = useState("groq");
  const provider = PROVIDERS.find((p) => p.id === providerId) || PROVIDERS[0];
  const [key, setKey] = useState("");
  const [showKey, setShowKey] = useState(false);
  const [apiBase, setApiBase] = useState(provider.base);
  const [model, setModel] = useState(provider.model);
  const [result, setResult] = useState<{ ok: boolean; detail: string } | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const agentBase = getBase();

  const choose = (id: string) => {
    const next = PROVIDERS.find((p) => p.id === id) || PROVIDERS[0];
    setProviderId(id);
    setApiBase(next.base);
    setModel(next.model);
    setResult(null);
    setError("");
  };

  const payload = () => ({
    api_key: key.trim(),
    api_base: apiBase.trim(),
    model: model.trim(),
  });

  const test = async () => {
    setError(""); setResult(null); setBusy(true);
    try { setResult(await api.testLlm(payload())); }
    catch (e) { setError(e instanceof ApiError ? e.message : String(e)); }
    finally { setBusy(false); }
  };

  const save = async () => {
    setError(""); setResult(null); setBusy(true);
    try {
      const saved = await api.saveConfig(payload());
      setResult(saved);
      if (saved.ok) window.setTimeout(onClose, 900);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    } finally { setBusy(false); }
  };

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/70 p-4 backdrop-blur-sm" onClick={onClose}>
      <div className="w-full max-w-lg rounded-2xl border border-line bg-surface shadow-2xl" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <div>
            <div className="label">settings</div>
            <h2 className="mt-1 font-display text-base font-semibold">Model & connection</h2>
          </div>
          <button onClick={onClose} className="btn-ghost h-9 w-9 rounded-xl"><X className="h-5 w-5" /></button>
        </div>
        <div className="space-y-4 p-5">
          <div>
            <span className="label">Agent endpoint</span>
            <div className="field mt-2 flex items-center justify-between font-mono text-xs">
              <span className="truncate">{agentBase}</span>
              <span className="ml-2 shrink-0 rounded-md bg-sage/10 px-2 py-0.5 text-[10px] uppercase tracking-wider text-sage">auto</span>
            </div>
            <p className="mt-1.5 text-[11px] text-faint">The local token is supplied by the app automatically.</p>
          </div>

          <label className="block">
            <span className="label">Provider</span>
            <select className="field mt-2" value={providerId} onChange={(e) => choose(e.target.value)}>
              {PROVIDERS.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}
            </select>
          </label>

          <label className="block">
            <span className="label">API key</span>
            <span className="relative mt-2 block">
              <input
                className="field pr-11 font-mono"
                type={showKey ? "text" : "password"}
                value={key}
                onChange={(e) => setKey(e.target.value)}
                placeholder={provider.hint ? `${provider.hint}…` : "Paste your key"}
                autoComplete="off"
              />
              <button
                type="button"
                onClick={() => setShowKey((v) => !v)}
                className="absolute inset-y-0 right-1 flex h-full w-10 items-center justify-center rounded-lg text-muted hover:text-ink"
                title={showKey ? "Hide key" : "Show key"}
              >
                {showKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </span>
          </label>

          <div className="grid grid-cols-2 gap-3">
            <label className="block">
              <span className="label">Model</span>
              <input className="field mt-2 font-mono" value={model} onChange={(e) => setModel(e.target.value)} />
            </label>
            <div>
              <span className="label">Endpoint</span>
              {providerId === "custom" ? (
                <input className="field mt-2 font-mono" value={apiBase} onChange={(e) => setApiBase(e.target.value)} placeholder="https://api.example.com/v1" />
              ) : (
                <div className="field mt-2 flex items-center justify-between font-mono text-xs">
                  <span className="truncate">{apiBase}</span>
                  <span className="ml-2 shrink-0 rounded-md bg-sage/10 px-2 py-0.5 text-[10px] uppercase tracking-wider text-sage">auto</span>
                </div>
              )}
            </div>
          </div>

          {error && <p className="rounded-lg border border-danger/30 bg-danger/5 px-3 py-2 text-xs text-danger">{error}</p>}
          {result && (
            <div className={`flex items-start gap-2 rounded-lg border px-3 py-2 text-xs ${result.ok ? "border-sage/30 bg-sage/5 text-sage" : "border-danger/30 bg-danger/5 text-danger"}`}>
              {result.ok ? <CheckCircle2 className="mt-0.5 h-4 w-4" /> : <XCircle className="mt-0.5 h-4 w-4" />}
              <span>{result.detail}</span>
            </div>
          )}

          <div className="flex justify-end gap-2 pt-2">
            <button onClick={onClose} className="btn-ghost" disabled={busy}>Cancel</button>
            <button onClick={() => void test()} disabled={busy || !key.trim()} className="btn-ghost">Test</button>
            <button onClick={() => void save()} disabled={busy || !key.trim()} className="rounded-xl bg-persimmon px-4 py-2 text-sm font-semibold text-ink disabled:opacity-40">Save</button>
          </div>
        </div>
      </div>
    </div>
  );
}
