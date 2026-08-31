# Estrada Play Comunista Universal 1.6.1 — Estrada Rádio

- PTT real-time audio via WebRTC peer-to-peer.
- Server stores presence and SDP/ICE signaling only; never voice audio.
- Automatic room from locally recognized road + direction + approximate ~20 km cell.
- Public room capped at 8 participants.
- Safety voice has absolute priority and temporarily mutes radio.
- Quick temporary alerts: accident, object, roadworks, traffic, heavy rain.
- Radio foreground service is non-sticky and stopWithTask=true.
- Server v5 has admin kill switch and active-room dashboard.
- STUN by default; optional TURN credentials via app settings: radio_turn_url/user/password.
