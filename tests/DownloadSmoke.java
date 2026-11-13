package space.glidetv.launcher;

import android.content.Context;
import android.content.pm.PackageInfo;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;

/** Read-only Android runtime smoke: fetch a small public Glide APK and parse its identity. */
public final class DownloadSmoke {
    public static void main(String[] args)throws Exception {
        File directory=new File(args[0]);directory.mkdirs();File apk=new File(directory,"download.apk");
        new ApkSource().download("https://github.com/ghanta1996/GlideTV/releases/download/v4.4.0/GlideTV-v4.4.0.apk",apk,(bytes,total)->{});
        System.out.println("PASS: Android HTTPS download ("+apk.length()+" bytes)");
        Class<?> parser=Class.forName("android.content.pm.PackageParser");Object instance=parser.getConstructor().newInstance();Object info=parser.getMethod("parsePackage",File.class,int.class).invoke(instance,apk,0);
        if(info==null||!"space.glidetv.launcher".equals(info.getClass().getField("packageName").get(info))||!"4.4.0".equals(info.getClass().getField("mVersionName").get(info)))throw new AssertionError("Downloaded APK identity mismatch");
        try{new ApkSource().validate(new URL("https://127.0.0.1/app.apk"));throw new AssertionError("Private address accepted");}catch(IOException expected){}
        System.out.println("PASS: Android HTTPS redirect/download + APK identity + private-address rejection ("+apk.length()+" bytes)");
    }
}
