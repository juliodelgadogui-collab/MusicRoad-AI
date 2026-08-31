#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'estrada-play-comunista-app/app/src/main/java/com/estradaplay/comunista'
p=JAVA/'RoadRadioService.java'
s=p.read_text(encoding='utf-8')
s=s.replace('private PeerConnectionFactory factory;private JavaAudioDeviceModule adm;private AudioSource audioSource;', 'private PeerConnectionFactory factory;private org.webrtc.audio.AudioDeviceModule adm;private AudioSource audioSource;')
s=s.replace('private PeerConnectionFactory factory;private AudioDeviceModule adm;private AudioSource audioSource;', 'private PeerConnectionFactory factory;private org.webrtc.audio.AudioDeviceModule adm;private AudioSource audioSource;')
p.write_text(s,encoding='utf-8')
p=JAVA/'RoadRadioActivity.java'
s=p.read_text(encoding='utf-8')
s=s.replace('@Override protected void onStop(){if(registered)try{unregisterReceiver(rx);}catch(Throwable ignored){}registered=false;send(RoadRadioService.ACTION_QUERY);super.onStop();}', '@Override protected void onStop(){if(registered)try{unregisterReceiver(rx);}catch(Throwable ignored){}registered=false;super.onStop();}')
p.write_text(s,encoding='utf-8')
print('Estrada Radio compile/lifecycle fixes applied')
