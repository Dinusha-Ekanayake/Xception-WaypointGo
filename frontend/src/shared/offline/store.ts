// IndexedDB, account-scoped. Small on purpose: the queue's contract is what
// matters, and the storage behind it should be replaceable.

const DB_VERSION = 1;
const QUEUE_STORE = "outbox";

export type StoredEntry<T = unknown> = {
  commandId: string;
  kind: string;
  payload: T;
  enqueuedAt: string;
  attempts: number;
  lastError?: string;
  /** Held for human review after a conflict. Never retried automatically. */
  needsReview?: boolean;
};

function databaseName(accountId: string): string {
  // Scoped per account so signing in as someone else never inherits their queue.
  return `waypoint-${accountId}`;
}

function open(accountId: string): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(databaseName(accountId), DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(QUEUE_STORE)) {
        db.createObjectStore(QUEUE_STORE, { keyPath: "commandId" });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function tx<T>(
  db: IDBDatabase,
  mode: IDBTransactionMode,
  run: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  return new Promise((resolve, reject) => {
    const request = run(db.transaction(QUEUE_STORE, mode).objectStore(QUEUE_STORE));
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export async function put(accountId: string, entry: StoredEntry): Promise<void> {
  const db = await open(accountId);
  await tx(db, "readwrite", (store) => store.put(entry));
  db.close();
}

export async function all(accountId: string): Promise<StoredEntry[]> {
  const db = await open(accountId);
  const entries = await tx<StoredEntry[]>(db, "readonly", (store) => store.getAll());
  db.close();
  return entries.sort((a, b) => a.enqueuedAt.localeCompare(b.enqueuedAt));
}

export async function remove(accountId: string, commandId: string): Promise<void> {
  const db = await open(accountId);
  await tx(db, "readwrite", (store) => store.delete(commandId));
  db.close();
}
