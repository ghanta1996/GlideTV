package space.glidetv.launcher;

import java.util.List;

/** Platform-independent rules for choosing and labelling an active media session. */
public final class MusicSelection {
    public static final int PAUSED=2, PLAYING=3, BUFFERING=6, CONNECTING=8;

    public static final class Candidate {
        public final String key,title,artist,album,app;
        public final int state;
        public final long updated;
        public Candidate(String key,String title,String artist,String album,String app,int state,long updated){
            this.key=clean(key);this.title=clean(title);this.artist=clean(artist);this.album=clean(album);this.app=clean(app);this.state=state;this.updated=updated;
        }
    }

    private MusicSelection(){}

    public static Candidate choose(List<Candidate> candidates){
        Candidate best=null;
        for(Candidate candidate:candidates){
            if(candidate==null||candidate.title.isEmpty()||!active(candidate.state))continue;
            if(best==null||rank(candidate)>rank(best)||(rank(candidate)==rank(best)&&candidate.updated>best.updated))best=candidate;
        }
        return best;
    }

    public static String subtitle(String artist,String album,String app){
        String primary=clean(artist),secondary=clean(album),fallback=clean(app);
        if(primary.isEmpty())primary=secondary;
        else if(!secondary.isEmpty()&&!secondary.equalsIgnoreCase(primary))primary+="  ·  "+secondary;
        return primary.isEmpty()?fallback:primary;
    }

    private static boolean active(int state){return state==PLAYING||state==PAUSED||state==BUFFERING||state==CONNECTING;}
    private static int rank(Candidate candidate){
        if(candidate.state==PLAYING)return 4;
        if(candidate.state==BUFFERING)return 3;
        if(candidate.state==CONNECTING)return 2;
        return 1;
    }
    private static String clean(String value){return value==null?"":value.trim();}
}
