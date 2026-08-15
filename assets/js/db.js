const MRDB = (() => {
  const DB_NAME = 'MusicRoadAI';
  const DB_VERSION = 3;
  let dbPromise;

  function open() {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains('tracks')) db.createObjectStore('tracks', {keyPath:'id'});
        if (!db.objectStoreNames.contains('blobs')) db.createObjectStore('blobs', {keyPath:'id'});
        if (!db.objectStoreNames.contains('settings')) db.createObjectStore('settings', {keyPath:'key'});
        if (!db.objectStoreNames.contains('tripCache')) db.createObjectStore('tripCache', {keyPath:'key'});
        if (!db.objectStoreNames.contains('folders')) db.createObjectStore('folders', {keyPath:'key'});
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    return dbPromise;
  }

  async function request(store, mode, action) {
    const db = await open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(store, mode);
      const os = tx.objectStore(store);
      let req;
      try { req = action(os); } catch (e) { reject(e); return; }
      if (req && typeof req.onsuccess !== 'undefined') {
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
      } else {
        tx.oncomplete = () => resolve(req);
        tx.onerror = () => reject(tx.error);
      }
    });
  }

  const get = (store, key) => request(store, 'readonly', os => os.get(key));
  const put = (store, value) => request(store, 'readwrite', os => os.put(value));
  const del = (store, key) => request(store, 'readwrite', os => os.delete(key));
  const clear = store => request(store, 'readwrite', os => os.clear());
  const all = store => request(store, 'readonly', os => os.getAll()).then(v => v || []);

  return {open, get, put, del, clear, all};
})();
