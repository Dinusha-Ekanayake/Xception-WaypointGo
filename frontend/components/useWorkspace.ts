import { useState, useEffect, useRef } from "react";
import { api, read, write, remove, ApiError } from "./storage";
import {
  AppStateSchema,
  type AppState,
  type Command,
  type QueuedCommand,
  type User,
} from "../lib/types";

// Serialize read-modify-write cycles across tabs, including uncertain network retries.
const locked = <T>(fn: () => Promise<T> | T): Promise<T> =>
  (
    navigator as Navigator & {
      locks?: { request: (n: string, f: () => Promise<T> | T) => Promise<T> };
    }
  ).locks
    ? (
        navigator as Navigator & {
          locks: {
            request: (n: string, f: () => Promise<T> | T) => Promise<T>;
          };
        }
      ).locks.request("waypoint-outbox", fn)
    : Promise.resolve(fn());

export interface WorkspaceApi {
  state: AppState | null;
  queue: QueuedCommand[];
  error: string;
  notice: string;
  online: boolean;
  busy: boolean;
  ready: boolean;
  login: (email: string, password: string) => Promise<boolean>;
  logout: () => Promise<void>;
  act: (
    kind: Command["kind"],
    data?: Record<string, unknown>,
  ) => Promise<boolean>;
  sync: () => Promise<void>;
  discard: (id: string) => Promise<void>;
  refresh: () => Promise<void>;
  setError: (msg: string) => void;
}

export function useWorkspace(): WorkspaceApi {
  const [state, setState] = useState<AppState | null>(null);
  const [queue, setQueue] = useState<QueuedCommand[]>([]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [online, setOnline] = useState(true);
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState(false);
  const current = useRef<string | null>(null);
  const syncing = useRef(false);
  const enqueueing = useRef(false);

  const putQueue = async (q: QueuedCommand[]): Promise<void> => {
    await write("queue:" + current.current, q);
    setQueue(q);
  };

  async function refresh(): Promise<void> {
    const id = current.current;
    if (!id) return;
    const raw = await api<unknown>("state");
    const parsed = AppStateSchema.safeParse(raw);
    const s: AppState = parsed.success ? parsed.data : (raw as AppState);
    if (s.user.id !== id || current.current !== id) return;
    await write("state:" + id, s);
    setState(s);
    setOnline(true);
  }

  async function sync(): Promise<void> {
    if (syncing.current || !current.current || !navigator.onLine) return;
    syncing.current = true;
    try {
      await locked(async () => {
        if (!current.current) return;
        let q: QueuedCommand[] =
          (await read<QueuedCommand[]>("queue:" + current.current)) || [];
        setQueue(q);
        while (q.length) {
          if (q[0]!.error) break;
          try {
            const result = await api<{ order_id?: string; day?: string }>(
              "command",
              q[0]!.command,
            );
            q = q.slice(1);
            await putQueue(q);
            if (result.order_id && result.day)
              setNotice(
                `Order ${result.order_id} confirmed for ${result.day}.`,
              );
            else if (!q.length)
              setNotice(
                "Record synchronized. The shared delivery record is up to date.",
              );
          } catch (e) {
            const err = e as ApiError;
            if (err.status === 401) {
              setError(
                "Session expired. Sign in again with the same account to synchronize saved work.",
              );
            } else if (err.status && err.status < 500) {
              q[0]!.error = err.message;
              await putQueue(q);
              setError(err.message);
            } else if (err.status)
              setError(
                "Server unavailable. Saved records will retry without changing their IDs.",
              );
            else setOnline(false);
            break;
          }
        }
        await refresh();
      });
    } catch (e) {
      const err = e as ApiError;
      if (err.status === 401)
        setError(
          "Session expired. Sign in again with the same account to synchronize saved work.",
        );
      else if (!err.status) setOnline(false);
    } finally {
      syncing.current = false;
    }
  }

  useEffect(() => {
    setOnline(navigator.onLine);
    (async () => {
      const id = localStorage.getItem("waypoint-user");
      if (id) {
        current.current = id;
        setState((await read<AppState>("state:" + id)) || null);
        setQueue((await read<QueuedCommand[]>("queue:" + id)) || []);
        await sync();
      }
      setReady(true);
    })().catch((e: Error) => {
      setError(e.message);
      setReady(true);
    });
    const on = (): void => {
      setOnline(true);
      void sync();
    };
    const off = (): void => setOnline(false);
    window.addEventListener("online", on);
    window.addEventListener("offline", off);
    const visible = (): void => { if (document.visibilityState === "visible") void sync(); };
    document.addEventListener("visibilitychange", visible);
    const timer = setInterval(visible, 10000);
    return () => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", visible);
      window.removeEventListener("online", on);
      window.removeEventListener("offline", off);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function login(email: string, password: string): Promise<boolean> {
    setBusy(true);
    setError("");
    try {
      const u = await api<User>("login", { email, password });
      current.current = u.id;
      localStorage.setItem("waypoint-user", u.id);
      setQueue((await read<QueuedCommand[]>("queue:" + u.id)) || []);
      await refresh();
      await sync();
      return true;
    } catch (e) {
      setError((e as Error).message);
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function logout(): Promise<void> {
    setBusy(true);
    try {
      await locked(async () => {
        const q: QueuedCommand[] =
          (await read<QueuedCommand[]>("queue:" + current.current)) || [];
        if (q.length) {
          setError(
            "Synchronize or review saved records before signing out of this device.",
          );
          return;
        }
        await api("logout", {});
        await remove("state:" + current.current);
        await remove("queue:" + current.current);
        localStorage.removeItem("waypoint-user");
        current.current = null;
        setState(null);
        setError("");
        setNotice("");
      });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function act(
    kind: Command["kind"],
    data: Record<string, unknown> = {},
  ): Promise<boolean> {
    if (enqueueing.current) return false;
    enqueueing.current = true;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const command = {
        id: crypto.randomUUID(),
        kind,
        ...data,
        client_time: new Date().toISOString(),
      };
      if (["plan", "publish", "move", "defer_note", "resolve", "resolve_exception"].includes(kind)) {
        await api("command", command);
        await refresh();
        setNotice("Changes saved.");
        return true;
      }
      await locked(async () => {
        const q: QueuedCommand[] =
          (await read<QueuedCommand[]>("queue:" + current.current)) || [];
        await putQueue([...q, { command, created: command.client_time }]);
      });
      setNotice(
        navigator.onLine
          ? "Record saved. Synchronizing…"
          : "Saved on this device. Waiting for connection.",
      );
      await sync();
      return true;
    } catch (e) {
      setError((e as Error).message);
      return false;
    } finally {
      setBusy(false);
      enqueueing.current = false;
    }
  }

  async function discard(id: string): Promise<void> {
    await locked(async () => {
      const q: QueuedCommand[] =
        (await read<QueuedCommand[]>("queue:" + current.current)) || [];
      await putQueue(q.filter((x) => (x.command.id as string) !== id));
      setError("");
    });
    await sync();
  }

  return {
    state,
    queue,
    error,
    notice,
    online,
    busy,
    ready,
    login,
    logout,
    act,
    sync,
    discard,
    refresh,
    setError,
  };
}
