import { useEffect, useRef, useState } from "react";
import { Monitor, Plus, Settings2, Wifi, WifiOff } from "lucide-react";
import { api, ApiError } from "./lib/api";
import { ChatMessageView, type ChatMessage } from "./components/ChatMessage";
import { Composer } from "./components/Composer";
import { SettingsModal } from "./components/SettingsModal";
import { AgentScreen } from "./components/AgentScreen";

const TERMINAL = ["done", "failed", "cancelled", "partial", "cancelling"];
const STORE = "orbit.chat.v1";

const SUGGESTIONS = [
  "Open Bluetooth settings",
  "Find the cheapest flight to Delhi and tell me the price",
  "Open Notes and search for the hotel name",
];

function loadMessages(): ChatMessage[] {
  try {
    const raw = localStorage.getItem(STORE);
    return raw ? (JSON.parse(raw) as ChatMessage[]) : [];
  } catch {
    return [];
  }
}

export default function App() {
  const [messages, setMessages] = useState<ChatMessage[]>(loadMessages);
  const [connected, setConnected] = useState<boolean | null>(null);
  const [settings, setSettings] = useState(false);
  const [screen, setScreen] = useState(false);
  const [error, setError] = useState("");
  const messagesRef = useRef(messages);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => { messagesRef.current = messages; }, [messages]);
  useEffect(() => { localStorage.setItem(STORE, JSON.stringify(messages)); }, [messages]);
  useEffect(() => { scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" }); }, [messages]);

  useEffect(() => {
    const check = () => void api.tasks().then(() => setConnected(true)).catch(() => setConnected(false));
    check();
    const conn = window.setInterval(check, 8000);

    const tick = async () => {
      const active = messagesRef.current.filter(
        (m) => m.role === "agent" && m.taskId && !TERMINAL.includes(m.state || ""),
      );
      if (!active.length) return;
      const updates = await Promise.all(active.map(async (m) => {
        try { return { id: m.id, task: await api.task(m.taskId!) }; }
        catch { return null; }
      }));
      setMessages((prev) => prev.map((m) => {
        const hit = updates.find((u) => u?.id === m.id);
        if (!hit) return m;
        const t = hit.task;
        const ended = TERMINAL.includes(t.state);
        const text = ended
          ? t.answer || t.reason || (t.state === "cancelled" ? "Stopped." : "Done.")
          : m.text;
        return {
          ...m,
          state: t.state,
          steps: t.steps || [],
          frame: t.frame || m.frame,
          question: t.state === "waiting" ? (t.question || "I need your input to continue.") : "",
          usage: t.usage || m.usage,
          text,
          stopped: t.state === "cancelled" || t.state === "failed",
        };
      }));
    };
    const poll = window.setInterval(() => void tick(), 1200);
    return () => { window.clearInterval(conn); window.clearInterval(poll); };
  }, []);

  const newChat = () => {
    messagesRef.current
      .filter((m) => m.role === "agent" && m.taskId && !TERMINAL.includes(m.state || ""))
      .forEach((m) => void api.cancelTask(m.taskId!));
    setMessages([]);
    setError("");
  };

  const waiting = messages.find((m) => m.role === "agent" && m.state === "waiting" && m.taskId);
  const working = messages.some((m) => m.role === "agent" && m.taskId && !TERMINAL.includes(m.state || ""));

  const send = async (text: string) => {
    setError("");
    if (waiting?.taskId) {
      setMessages((prev) => [...prev, {
        id: crypto.randomUUID(), role: "user", text, time: Date.now(),
      }]);
      try {
        await api.answerTask(waiting.taskId, text);
        setMessages((prev) => prev.map((m) =>
          m.id === waiting.id ? { ...m, question: "", text: "Got it — continuing." } : m));
      } catch (e) {
        setError(e instanceof ApiError ? e.message : String(e));
      }
      return;
    }

    setMessages((prev) => [...prev, {
      id: crypto.randomUUID(), role: "user", text, time: Date.now(),
    }]);
    try {
      const res = await api.startTask({ text });
      setMessages((prev) => [...prev, {
        id: crypto.randomUUID(),
        role: "agent",
        text: "",
        time: Date.now(),
        taskId: res.id,
        state: "queued",
        steps: [],
      }]);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  };

  return (
    <div className="relative z-10 flex h-full flex-col">
      <header className="border-b border-line bg-ink/70 px-4 py-3 backdrop-blur-xl sm:px-6">
        <div className="mx-auto flex max-w-3xl items-center justify-between">
          <div className="flex items-center gap-3">
            <img src="./orbit-icon.png" alt="Orbit" className="h-10 w-10 rounded-xl" />
            <div>
              <div className="font-display text-base font-bold tracking-tight">Orbit</div>
              <div className="font-mono text-[10px] uppercase tracking-[0.18em] text-faint">phone agent</div>
            </div>
          </div>
          <div className="flex items-center gap-1.5">
            <div className={`mr-1 flex items-center gap-1.5 rounded-full border px-3 py-1.5 font-mono text-[11px] uppercase tracking-wider ${
              connected === true ? "border-sage/30 text-sage" : connected === false ? "border-danger/30 text-danger" : "border-line text-faint"
            }`}>
              {connected === true ? <Wifi className="h-5 w-5" /> : <WifiOff className="h-5 w-5" />}
              {connected === false ? "offline" : connected ? "online" : "connecting"}
            </div>
            <button title="New chat" onClick={newChat} className="btn-ghost h-11 gap-2 rounded-xl px-3 text-sm">
              <Plus className="h-5 w-5" /><span className="hidden sm:inline">New chat</span>
            </button>
            <button title="Agent screen" onClick={() => setScreen(true)} className="btn-ghost h-11 w-11 rounded-xl"><Monitor className="h-6 w-6" /></button>
            <button title="Settings" onClick={() => setSettings(true)} className="btn-ghost h-11 w-11 rounded-xl"><Settings2 className="h-6 w-6" /></button>
          </div>
        </div>
      </header>

      <div ref={scrollRef} className="relative flex-1 overflow-y-auto">
        <div className="mx-auto min-h-full max-w-3xl px-4 py-8 sm:px-0 sm:py-12">
          {messages.length === 0 ? (
            <div className="flex min-h-[60vh] flex-col items-center justify-center text-center">
              <img src="./orbit-icon.png" alt="Orbit" className="mb-6 h-14 w-14 rounded-2xl shadow-sm shadow-black/10" />
              <h1 className="font-display text-2xl font-semibold tracking-tight sm:text-3xl">What should I do on your phone?</h1>
              <p className="mt-3 max-w-md text-sm leading-relaxed text-muted">
                Just type it. Orbit routes to a recorded macro first, then watches the screen when it needs to.
              </p>
              <div className="mt-8 flex max-w-xl flex-wrap justify-center gap-2">
                {SUGGESTIONS.map((s) => (
                  <button key={s} onClick={() => void send(s)} className="rounded-full border border-line bg-surface/60 px-4 py-2 text-xs text-muted transition-colors hover:border-persimmon/50 hover:text-linen">
                    {s}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            <div className="space-y-2 pb-4">
              {messages.map((m) => (
                <ChatMessageView key={m.id} message={m} onCancel={(id) => void api.cancelTask(id)} />
              ))}
              {error && <p className="mx-auto mt-4 max-w-md rounded-xl border border-danger/30 bg-danger/5 px-4 py-2 text-center text-xs text-danger">{error}</p>}
            </div>
          )}
        </div>
      </div>

      <Composer onSend={(text) => void send(text)} replying={waiting?.question} disabled={working && !waiting} />
      {settings && <SettingsModal onClose={() => setSettings(false)} />}
      {screen && <AgentScreen onClose={() => setScreen(false)} />}
    </div>
  );
}
