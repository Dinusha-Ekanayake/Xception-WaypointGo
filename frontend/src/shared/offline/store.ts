// IndexedDB, account-scoped. Small on purpose: the queue's contract is what
// matters, and the storage behind it should be replaceable.

const DB_VERSION = 2;
const QUEUE_STORE = "outbox";
/** Reads kept for a full-tier role, so its working set survives a reload with no signal. */
const SNAPSHOT_STORE = "snapshots";
/** Binary artifacts waiting to reach the server: proof photos and signatures. */
const UPLOAD_STORE = "uploads";

export type StoredEntry<T = unknown> = {
  commandId: string;
  kind: string;
  payload: T;
  enqueuedAt: string;
  /** Strictly increasing on this device: the batch sequence and the order writes are sent in. */
  order?: number;
  attempts: number;
  lastError?: string;
  /** Held for human review after a conflict. Never retried automatically. */
  needsReview?: boolean;
  /** Why the server held it, such as VERSION_CONFLICT. */
  problemCode?: string;
  /** The held operation's version on the server, which discarding or redoing it names. */
  serverVersion?: number;
  /**
   * Uploads this write names, such as a voice note's audio (issue #136). It is
   * not sent while any of them is still on the device, and neither is anything
   * after it, so the server never sees a write before what it refers to.
   */
  waitsFor?: string[];
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
      // Additive: a device upgrading from version 1 keeps its queue.
      if (!db.objectStoreNames.contains(QUEUE_STORE)) {
        db.createObjectStore(QUEUE_STORE, { keyPath: "commandId" });
      }
      if (!db.objectStoreNames.contains(SNAPSHOT_STORE)) {
        db.createObjectStore(SNAPSHOT_STORE, { keyPath: "key" });
      }
      if (!db.objectStoreNames.contains(UPLOAD_STORE)) {
        db.createObjectStore(UPLOAD_STORE, { keyPath: "id" });
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
  storeName: string = QUEUE_STORE,
): Promise<T> {
  return new Promise((resolve, reject) => {
    const request = run(db.transaction(storeName, mode).objectStore(storeName));
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
  // In the order recorded on this device (review.ts recordedOrder), the recording time for older entries.
  const at = (e: StoredEntry) => e.order ?? (Date.parse(e.enqueuedAt) || 0) * 1000;
  return entries.sort((a, b) => at(a) - at(b) || a.commandId.localeCompare(b.commandId));
}

export async function remove(accountId: string, commandId: string): Promise<void> {
  const db = await open(accountId);
  await tx(db, "readwrite", (store) => store.delete(commandId));
  db.close();
}

// ---- snapshots ---------------------------------------------------------------

export type Snapshot<T = unknown> = { key: string; value: T; savedAt: string };

export async function putSnapshot<T>(accountId: string, key: string, value: T): Promise<void> {
  const db = await open(accountId);
  await tx(db, "readwrite", (store) => store.put({ key, value, savedAt: new Date().toISOString() }), SNAPSHOT_STORE);
  db.close();
}

export async function getSnapshot<T>(accountId: string, key: string): Promise<Snapshot<T> | null> {
  const db = await open(accountId);
  const found = await tx<Snapshot<T> | undefined>(db, "readonly", (store) => store.get(key), SNAPSHOT_STORE);
  db.close();
  return found ?? null;
}

// ---- uploads -----------------------------------------------------------------

export type StoredUpload = {
  /** Minted on the device; the server stores the artifact under this id. */
  id: string;
  /** Where the bytes go, as a PUT. */
  path: string;
  /** What the upload belongs to, so a screen can say which record still owes one. */
  subject: string;
  contentType: string;
  blob: Blob;
  savedAt: string;
  attempts: number;
  lastError?: string;
  /** Refused by the server. Kept until a person decides; never resent automatically. */
  needsReview?: boolean;
};

export async function putUpload(accountId: string, upload: StoredUpload): Promise<void> {
  const db = await open(accountId);
  await tx(db, "readwrite", (store) => store.put(upload), UPLOAD_STORE);
  db.close();
}

export async function allUploads(accountId: string): Promise<StoredUpload[]> {
  const db = await open(accountId);
  const uploads = await tx<StoredUpload[]>(db, "readonly", (store) => store.getAll(), UPLOAD_STORE);
  db.close();
  return uploads.sort((a, b) => a.savedAt.localeCompare(b.savedAt));
}

export async function removeUpload(accountId: string, id: string): Promise<void> {
  const db = await open(accountId);
  await tx(db, "readwrite", (store) => store.delete(id), UPLOAD_STORE);
  db.close();
}
