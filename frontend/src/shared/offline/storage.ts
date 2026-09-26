const open = (): Promise<IDBDatabase> =>
  new Promise((resolve, reject) => {
    const r = indexedDB.open("waypoint-v1", 1);
    r.onupgradeneeded = () => r.result.createObjectStore("records");
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });

export async function read<T = unknown>(key: string): Promise<T | undefined> {
  const db = await open();
  return new Promise((resolve, reject) => {
    const t = db.transaction("records");
    const r = t.objectStore("records").get(key);
    r.onsuccess = () => resolve(r.result as T | undefined);
    r.onerror = () => reject(r.error);
    t.oncomplete = () => db.close();
  });
}

export async function write(key: string, value: unknown): Promise<void> {
  const db = await open();
  return new Promise((resolve, reject) => {
    const t = db.transaction("records", "readwrite");
    t.objectStore("records").put(value, key);
    t.oncomplete = () => {
      db.close();
      resolve();
    };
    t.onerror = () => reject(t.error);
  });
}

export async function remove(key: string): Promise<void> {
  const db = await open();
  return new Promise((resolve, reject) => {
    const t = db.transaction("records", "readwrite");
    t.objectStore("records").delete(key);
    t.oncomplete = () => {
      db.close();
      resolve();
    };
    t.onerror = () => reject(t.error);
  });
}

export class ApiError extends Error {
  status?: number;
  constructor(message: string, status?: number) {
    super(message);
    this.status = status;
  }
}

export async function api<T = unknown>(path: string, body?: unknown): Promise<T> {
  const response = await fetch("/api/" + path, {
    credentials: "same-origin",
    headers: body ? { "Content-Type": "application/json" } : {},
    ...(body ? { method: "POST", body: JSON.stringify(body) } : {}),
    signal: AbortSignal.timeout(12000),
  });
  const data = (await response.json()) as { error?: string } & T;
  if (!response.ok) {
    throw new ApiError(
      (data as { error?: string }).error || "Request failed",
      response.status,
    );
  }
  return data as T;
}
