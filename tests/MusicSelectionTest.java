package space.glidetv.launcher;

import java.util.Arrays;

public final class MusicSelectionTest {
    private static void expect(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static MusicSelection.Candidate item(String key,String title,int state,long updated){return new MusicSelection.Candidate(key,title,"Artist","Album","Player",state,updated);}

    public static void main(String[] args){
        MusicSelection.Candidate selected=MusicSelection.choose(Arrays.asList(item("paused","Paused",MusicSelection.PAUSED,20),item("playing","Playing",MusicSelection.PLAYING,10)));
        expect(selected!=null&&"playing".equals(selected.key),"playing sessions take priority");
        selected=MusicSelection.choose(Arrays.asList(item("old","Old",MusicSelection.PAUSED,10),item("new","New",MusicSelection.PAUSED,20)));
        expect(selected!=null&&"new".equals(selected.key),"newest equivalent session wins");
        expect(MusicSelection.choose(Arrays.asList(item("stopped","Stopped",1,30),item("empty","",MusicSelection.PLAYING,40)))==null,"stopped and titleless sessions stay hidden");
        expect("Artist  ·  Album".equals(MusicSelection.subtitle("Artist","Album","Player")),"artist and album label");
        expect("Album".equals(MusicSelection.subtitle("","Album","Player")),"album fallback");
        expect("Player".equals(MusicSelection.subtitle("","","Player")),"app fallback");
        System.out.println("Music selection checks passed");
    }
}
