# MusicRoad Android 2.0 — Clean Base

This directory is the authoritative Android source for MusicRoad 2.x.

- Native Android only; no WebView.
- Builds directly from source; no legacy patch scripts.
- Same applicationId (`com.musicroad.ai`) for upgrade compatibility.
- Portrait and DriveOS landscape have different presentations and share the same core services.
- Mapbox Maps SDK renders the map.
- FastMapboxRouteEngine uses the server fast Mapbox route endpoint and loads hazards asynchronously.
- NavigationService owns MusicRoad route progress, radar/quebra-mola alerts and voice.
- PlaybackService owns background music and online radio playback.
- NativeMusicRepository reads device audio with MediaStore.
- OfflineStore persists state packs and the last route.
