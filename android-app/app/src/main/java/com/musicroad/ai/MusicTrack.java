package com.musicroad.ai;

public final class MusicTrack {
    public final long id; public final String title,artist,album,uri; public final long duration;
    public MusicTrack(long id,String title,String artist,String album,String uri,long duration){this.id=id;this.title=title;this.artist=artist;this.album=album;this.uri=uri;this.duration=duration;}
    @Override public String toString(){return title+"\n"+(artist==null||artist.trim().isEmpty()?"Artista desconhecido":artist);}
}
