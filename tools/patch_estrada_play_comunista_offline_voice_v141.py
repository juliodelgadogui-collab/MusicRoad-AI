#!/usr/bin/env python3
from pathlib import Path

ROOT = Path('estrada-play-comunista-app')
J = ROOT / 'app/src/main/java/com/estradaplay/comunista'


def read(p): return p.read_text(encoding='utf-8')
def write(p, s): p.write_text(s, encoding='utf-8')
def rep(s, old, new, label):
    if old not in s:
        raise SystemExit('missing patch anchor: ' + label)
    return s.replace(old, new, 1)

# Version.
g = ROOT / 'app/build.gradle'
s = read(g)
s = rep(s, 'versionCode 140', 'versionCode 141', 'versionCode')
s = rep(s, "versionName '1.4.0'", "versionName '1.4.1'", 'versionName')
write(g, s)

# -----------------------------------------------------------------------------
# Embedded voice: notify the service only after MediaPlayer actually starts.
# Also make replacement/cancelled sequences complete their old callback so audio
# focus can never remain ducked after a flush.
# -----------------------------------------------------------------------------
p = J / 'EstradaPlayOfflineVoice.java'
s = read(p)

s = rep(s,
'''    private MediaPlayer player;\n    private Runnable finished;\n    private int generation;''',
'''    private MediaPlayer player;\n    private Runnable started;\n    private Runnable finished;\n    private boolean startNotified;\n    private int generation;''', 'voice fields')

s = rep(s,
'''    boolean playRoadLimit(int limitKmh, Runnable onFinished) {\n        String speed = speedName(limitKmh);\n        if (speed == null) return false;\n        ArrayList<String> clips = new ArrayList<>();\n        clips.add("ep_limite_via");\n        clips.add(speed);\n        return play(clips, onFinished);\n    }''',
'''    boolean playRoadLimit(int limitKmh, Runnable onFinished) { return playRoadLimit(limitKmh, null, onFinished); }\n\n    boolean playRoadLimit(int limitKmh, Runnable onStarted, Runnable onFinished) {\n        String speed = speedName(limitKmh);\n        if (speed == null) return false;\n        ArrayList<String> clips = new ArrayList<>();\n        clips.add("ep_limite_via");\n        clips.add(speed);\n        return play(clips, onStarted, onFinished);\n    }''', 'road limit overload')

s = rep(s,
'''    boolean playOverspeed(int limitKmh, Runnable onFinished) {\n        String speed = speedName(limitKmh);\n        if (speed == null) return false;\n        ArrayList<String> clips = new ArrayList<>();\n        clips.add("ep_atencao");\n        clips.add("ep_acima_limite");\n        clips.add("ep_limite_via");\n        clips.add(speed);\n        return play(clips, onFinished);\n    }''',
'''    boolean playOverspeed(int limitKmh, Runnable onFinished) { return playOverspeed(limitKmh, null, onFinished); }\n\n    boolean playOverspeed(int limitKmh, Runnable onStarted, Runnable onFinished) {\n        String speed = speedName(limitKmh);\n        if (speed == null) return false;\n        ArrayList<String> clips = new ArrayList<>();\n        clips.add("ep_atencao");\n        clips.add("ep_acima_limite");\n        clips.add("ep_limite_via");\n        clips.add(speed);\n        return play(clips, onStarted, onFinished);\n    }''', 'overspeed overload')

s = rep(s,
'''    boolean playHazard(String type, double forwardM, int radarLimitKmh, Runnable onFinished) {\n        String distance = distanceName(forwardM);''',
'''    boolean playHazard(String type, double forwardM, int radarLimitKmh, Runnable onFinished) {\n        return playHazard(type, forwardM, radarLimitKmh, null, onFinished);\n    }\n\n    boolean playHazard(String type, double forwardM, int radarLimitKmh, Runnable onStarted, Runnable onFinished) {\n        String distance = distanceName(forwardM);''', 'hazard overload')
s = rep(s, '        return play(clips, onFinished);\n    }\n\n    void stop()', '        return play(clips, onStarted, onFinished);\n    }\n\n    void stop()', 'hazard play callback')

s = rep(s,
'''    void stop() {\n        main.post(this::stopInternal);\n    }\n\n    void release() {\n        main.post(() -> {\n            generation++;\n            stopInternal();\n            finished = null;\n        });\n    }\n\n    private boolean play(List<String> names, Runnable onFinished) {''',
'''    void stop() {\n        main.post(() -> cancelCurrent(true));\n    }\n\n    void release() {\n        main.post(() -> {\n            generation++;\n            cancelCurrent(true);\n            started = null;\n            finished = null;\n        });\n    }\n\n    private boolean play(List<String> names, Runnable onStarted, Runnable onFinished) {''', 'voice stop/play')

s = rep(s,
'''        main.post(() -> startSequence(ids, onFinished));\n        return true;\n    }''',
'''        main.post(() -> startSequence(ids, onStarted, onFinished));\n        return true;\n    }''', 'voice queue')

s = rep(s,
'''    private void startSequence(List<Integer> ids, Runnable onFinished) {\n        generation++;\n        int token = generation;\n        stopInternal();\n        queue.clear();\n        queue.addAll(ids);\n        finished = onFinished;\n        playNext(token);\n    }''',
'''    private void startSequence(List<Integer> ids, Runnable onStarted, Runnable onFinished) {\n        generation++;\n        int token = generation;\n        cancelCurrent(true);\n        queue.clear();\n        queue.addAll(ids);\n        started = onStarted;\n        finished = onFinished;\n        startNotified = false;\n        playNext(token);\n    }''', 'start sequence')

s = rep(s,
'''            mp.start();\n        } catch (Throwable ignored) {''',
'''            mp.start();\n            if (!startNotified) {\n                startNotified = true;\n                Runnable begin = started;\n                started = null;\n                if (begin != null) begin.run();\n            }\n        } catch (Throwable ignored) {''', 'voice actual start')

s = rep(s,
'''            Runnable done = finished;\n            finished = null;\n            if (done != null) done.run();\n            return;''',
'''            Runnable done = finished;\n            started = null;\n            finished = null;\n            startNotified = false;\n            if (done != null) done.run();\n            return;''', 'voice normal finish')

s = rep(s,
'''        Runnable done = finished;\n        finished = null;\n        if (done != null) done.run();\n    }\n\n    private void stopInternal() {''',
'''        Runnable done = finished;\n        started = null;\n        finished = null;\n        startNotified = false;\n        if (done != null) done.run();\n    }\n\n    private void cancelCurrent(boolean notifyFinished) {\n        Runnable done = notifyFinished ? finished : null;\n        started = null;\n        finished = null;\n        startNotified = false;\n        stopInternal();\n        if (done != null) done.run();\n    }\n\n    private void stopInternal() {''', 'voice cancel')
write(p, s)

# -----------------------------------------------------------------------------
# Road safety: embedded voice is truly primary. TTS ducks only from onStart.
# Prime RJ/MG/ES independently of GPS/map/corridor downloads.
# -----------------------------------------------------------------------------
p = J / 'RoadSafetyService.java'
s = read(p)

s = rep(s,
'''    private final AtomicBoolean storesLoading = new AtomicBoolean(false);\n    private ApiClient api;''',
'''    private final AtomicBoolean storesLoading = new AtomicBoolean(false);\n    private final AtomicBoolean coreStatesPriming = new AtomicBoolean(false);\n    private ApiClient api;''', 'core prime field')

s = rep(s,
'''                        @Override public void onStart(String utteranceId) {}\n                        @Override public void onDone(String utteranceId) { main.post(RoadSafetyService.this::restoreAudioAfterVoice); }\n                        @Override public void onError(String utteranceId) { main.post(RoadSafetyService.this::restoreAudioAfterVoice); }''',
'''                        @Override public void onStart(String utteranceId) { main.post(RoadSafetyService.this::beginVoiceDucking); }\n                        @Override public void onDone(String utteranceId) { main.post(RoadSafetyService.this::restoreAudioAfterVoice); }\n                        @Override public void onError(String utteranceId) { main.post(RoadSafetyService.this::restoreAudioAfterVoice); }\n                        @Override public void onStop(String utteranceId, boolean interrupted) { main.post(RoadSafetyService.this::restoreAudioAfterVoice); }''', 'tts progress listener')

s = rep(s,
'''                    if (packs != null && mapRoads != null) startLocation();\n                    else updateNotification("Proteção na estrada", "Base offline indisponível; tentando novamente", true);''',
'''                    if (packs != null && mapRoads != null) {\n                        startLocation();\n                        primeOfflineRadarCore();\n                    } else updateNotification("Proteção na estrada", "Base offline indisponível; tentando novamente", true);''', 'store init prime')

s = rep(s,
'''    @Override public int onStartCommand(Intent intent, int flags, int startId) {\n        if (packs != null && mapRoads != null) startLocation(); else initializeStoresAsync();\n        return START_STICKY;\n    }''',
'''    @Override public int onStartCommand(Intent intent, int flags, int startId) {\n        if (packs != null && mapRoads != null) { startLocation(); primeOfflineRadarCore(); } else initializeStoresAsync();\n        return START_STICKY;\n    }\n\n    // OFFLINE_RADAR_CORE_V141: state radar packs are prepared before route/map extras.\n    // They remain fully local once downloaded and do not depend on live internet to alert.\n    private void primeOfflineRadarCore() {\n        RoadPackStore local = packs;\n        if (local == null || local.coreStatesReady() || !coreStatesPriming.compareAndSet(false, true)) return;\n        io.execute(() -> {\n            try {\n                ensureApiSession(false);\n                boolean ok = local.prefetchCoreStates(api);\n                if (!ok || !local.coreStatesReady()) {\n                    ensureApiSession(true);\n                    local.prefetchCoreStates(api);\n                }\n            } catch (Throwable ignored) {\n            } finally {\n                coreStatesPriming.set(false);\n                String text = "Radares offline · " + local.coreStatesStatus();\n                updateNotification("Proteção offline", text, false);\n                Location p = previous;\n                if (p != null) broadcastSynthetic(p.getLatitude(), p.getLongitude(), text);\n            }\n        });\n    }''', 'onStart prime method')

s = rep(s,
'''    private void prepareEmbeddedVoice() {\n        try { if (tts != null) tts.stop(); } catch (Throwable ignored) {}\n        duckOwnPlayer(true);\n        requestVoiceFocus();\n        main.removeCallbacks(restoreAudioFallback);\n        main.postDelayed(restoreAudioFallback, 8000L);\n    }''',
'''    private void beginVoiceDucking() {\n        duckOwnPlayer(true);\n        requestVoiceFocus();\n        main.removeCallbacks(restoreAudioFallback);\n        main.postDelayed(restoreAudioFallback, 8000L);\n    }''', 'begin ducking')

s = rep(s,
'''    private boolean speakRoadLimitVoice(int limitKmh) {\n        if (ttsReady && copilot != null) {\n            speak(copilot.roadLimit(limitKmh));\n            return true;\n        }\n        if (offlineVoice != null) {\n            prepareEmbeddedVoice();\n            if (offlineVoice.playRoadLimit(limitKmh, this::restoreAudioAfterVoice)) return true;\n            restoreAudioAfterVoice();\n        }\n        return false;\n    }''',
'''    private boolean speakRoadLimitVoice(int limitKmh) {\n        if (offlineVoice != null && offlineVoice.playRoadLimit(limitKmh, this::beginVoiceDucking, this::restoreAudioAfterVoice)) return true;\n        if (ttsReady && copilot != null) return speak(copilot.roadLimit(limitKmh));\n        return false;\n    }''', 'road limit voice priority')

s = rep(s,
'''    private boolean speakOverspeedVoice(int limitKmh) {\n        if (ttsReady && copilot != null) {\n            speak(copilot.overspeed(limitKmh));\n            return true;\n        }\n        if (offlineVoice != null) {\n            prepareEmbeddedVoice();\n            if (offlineVoice.playOverspeed(limitKmh, this::restoreAudioAfterVoice)) return true;\n            restoreAudioAfterVoice();\n        }\n        return false;\n    }''',
'''    private boolean speakOverspeedVoice(int limitKmh) {\n        if (offlineVoice != null && offlineVoice.playOverspeed(limitKmh, this::beginVoiceDucking, this::restoreAudioAfterVoice)) return true;\n        if (ttsReady && copilot != null) return speak(copilot.overspeed(limitKmh));\n        return false;\n    }''', 'overspeed voice priority')

s = rep(s,
'''    private void speakHazardVoice(RoadHazard h, double forwardM) {\n        if (ttsReady && copilot != null && h != null) {\n            speak(copilot.hazard(h.type, forwardM, h.speed));\n            return;\n        }\n        if (offlineVoice != null && h != null) {\n            prepareEmbeddedVoice();\n            if (offlineVoice.playHazard(h.type, forwardM, h.speed, this::restoreAudioAfterVoice)) return;\n            restoreAudioAfterVoice();\n        }\n        speak(voice(h, forwardM));\n    }''',
'''    private void speakHazardVoice(RoadHazard h, double forwardM) {\n        if (offlineVoice != null && h != null &&\n                offlineVoice.playHazard(h.type, forwardM, h.speed, this::beginVoiceDucking, this::restoreAudioAfterVoice)) return;\n        if (ttsReady && copilot != null && h != null && speak(copilot.hazard(h.type, forwardM, h.speed))) return;\n        speak(voice(h, forwardM));\n    }''', 'hazard voice priority')

s = rep(s,
'''    private void speak(String text) {\n        if (!ttsReady || tts == null || text == null || text.trim().isEmpty()) return;\n        if (offlineVoice != null) offlineVoice.stop();\n        duckOwnPlayer(true);\n        requestVoiceFocus();\n        main.removeCallbacks(restoreAudioFallback);\n        main.postDelayed(restoreAudioFallback, 8000L);\n        try {\n            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "road-alert-" + System.currentTimeMillis());\n        } catch (Throwable e) {\n            restoreAudioAfterVoice();\n        }\n    }''',
'''    private boolean speak(String text) {\n        if (!ttsReady || tts == null || text == null || text.trim().isEmpty()) return false;\n        try {\n            String id = "road-alert-" + System.currentTimeMillis();\n            int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);\n            if (result == TextToSpeech.ERROR) { restoreAudioAfterVoice(); return false; }\n            // Deliberately do not duck here. UtteranceProgressListener.onStart is the\n            // proof that Android actually began speaking. This prevents silent ducking.\n            return true;\n        } catch (Throwable e) {\n            restoreAudioAfterVoice();\n            return false;\n        }\n    }''', 'tts speak confirmation')

write(p, s)

# -----------------------------------------------------------------------------
# Radar package persistence: prefetch states first, atomic files, and never throw
# away a state pack merely because it is stale. Stale data is still safer offline;
# online refresh replaces it atomically.
# -----------------------------------------------------------------------------
p = J / 'RoadPackStore.java'
s = read(p)

s = rep(s,
'''    synchronized String coreStatesStatus() {\n        StringBuilder out = new StringBuilder();\n        for (String uf : new String[]{"RJ","MG","ES"}) {\n            if (out.length() > 0) out.append(" · ");\n            out.append(uf).append(hasStateLocked(uf) ? " ✓" : " …");\n        }\n        return out.toString();\n    }''',
'''    synchronized String coreStatesStatus() {\n        StringBuilder out = new StringBuilder();\n        for (String uf : new String[]{"RJ","MG","ES"}) {\n            if (out.length() > 0) out.append(" · ");\n            out.append(uf).append(hasStateLocked(uf) ? " ✓" : " …");\n        }\n        return out.toString();\n    }\n\n    // OFFLINE_RADAR_CORE_V141: prepare the three requested states independently\n    // from GPS tiles/corridors so the phone can go offline immediately afterwards.\n    boolean prefetchCoreStates(ApiClient api) {\n        if (api == null) return coreStatesReady();\n        boolean any = false;\n        for (String uf : new String[]{"RJ","MG","ES"}) {\n            boolean stateOk = fetchMusicRoadStateRadars(api, uf);\n            if (!stateOk) stateOk = fetchStateCoverage(api, uf);\n            any = stateOk || any;\n        }\n        return coreStatesReady() || any;\n    }''', 'state prefetch method')

s = rep(s,
'''    boolean prepareTravelReserve(ApiClient api, double lat, double lon, float heading) {\n        if (api == null) return false;\n        boolean ok = fetchCoverage(api, lat, lon);''',
'''    boolean prepareTravelReserve(ApiClient api, double lat, double lon, float heading) {\n        if (api == null) return false;\n        // State packs have priority over optional live tiles/corridors.\n        boolean ok = prefetchCoreStates(api);\n        ok = fetchCoverage(api, lat, lon) || ok;''', 'state-first travel reserve')

s = rep(s,
'''    private boolean savePack(String key,JSONObject stored){\n        try{\n            File target=new File(dir,key+".json");writeText(target,stored.toString());\n            Pack p=parsePack(target,stored);if(p==null)return false;\n            synchronized(this){Pack old=findByKey(key);if(old!=null)packs.remove(old);packs.add(p);cleanupLocked();}\n            return true;\n        }catch(Exception e){return false;}\n    }''',
'''    private boolean savePack(String key,JSONObject stored){\n        try{\n            File target=new File(dir,key+".json");\n            File temp=new File(dir,key+".json.tmp");\n            writeText(temp,stored.toString());\n            Pack parsed=parsePack(temp,stored);if(parsed==null){temp.delete();return false;}\n            if(target.exists()&&!target.delete()){temp.delete();return false;}\n            if(!temp.renameTo(target)){writeText(target,stored.toString());temp.delete();}\n            Pack p=parsePack(target,stored);if(p==null)return false;\n            synchronized(this){Pack old=findByKey(key);if(old!=null)packs.remove(old);packs.add(p);cleanupLocked();}\n            return true;\n        }catch(Exception e){return false;}\n    }''', 'atomic state persistence')

s = rep(s,
'''                long maxAge="state".equals(kind)?STATE_MAX_AGE_MS:MAX_AGE_MS;\n                if (now - fetched > maxAge) { f.delete(); continue; }''',
'''                long maxAge="state".equals(kind)?Long.MAX_VALUE:MAX_AGE_MS;\n                // State packs never disappear just because they became stale. They\n                // remain usable offline and are replaced when connectivity returns.\n                if (!"state".equals(kind) && now - fetched > maxAge) { f.delete(); continue; }''', 'keep stale states on load')

s = rep(s,
'''        for (Pack p : packs) {\n            long maxAge="state".equals(p.kind)?STATE_MAX_AGE_MS:MAX_AGE_MS;\n            if(now-p.fetchedAt>maxAge)stale.add(p);\n        }''',
'''        for (Pack p : packs) {\n            if ("state".equals(p.kind)) continue;\n            long maxAge=MAX_AGE_MS;\n            if(now-p.fetchedAt>maxAge)stale.add(p);\n        }''', 'keep state cleanup')

write(p, s)

# Validate key guarantees.
checks = {
    g: ["versionCode 141", "versionName '1.4.1'"],
    J/'RoadSafetyService.java': ['primeOfflineRadarCore', 'onStart(String utteranceId) { main.post(RoadSafetyService.this::beginVoiceDucking);', 'TextToSpeech.ERROR'],
    J/'RoadPackStore.java': ['prefetchCoreStates', 'State packs never disappear', '.json.tmp'],
    J/'EstradaPlayOfflineVoice.java': ['Runnable onStarted', 'startNotified', 'cancelCurrent(true)'],
}
for path, needles in checks.items():
    body = read(path)
    for needle in needles:
        if needle not in body:
            raise SystemExit(f'validation failed: {needle} in {path}')

print('Estrada Play Comunista 1.4.1 offline radar + voice reliability patch applied')
