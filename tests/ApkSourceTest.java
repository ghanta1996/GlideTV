package space.glidetv.launcher;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Network fixtures exercise redirects, code resolution and failed/unsafe downloads. */
public final class ApkSourceTest {
    static int checks;
    static void check(boolean condition){checks++;if(!condition)throw new AssertionError("Check "+checks);}
    interface Action {void run()throws Exception;}
    static void fails(String text,Action action)throws Exception {try{action.run();throw new AssertionError("Expected "+text);}catch(IOException e){check(e.getMessage().contains(text));}}
    static final class Reply extends HttpURLConnection {
        byte[] data;int status;String type,location;long size;
        Reply(String url,int status,String type,String data,String location)throws Exception{super(new URL(url));this.status=status;this.type=type;this.data=data.getBytes(StandardCharsets.UTF_8);this.location=location;size=this.data.length;}
        public void disconnect(){}public boolean usingProxy(){return false;}public void connect(){}
        public int getResponseCode(){return status;}public String getContentType(){return type;}public long getContentLengthLong(){return size;}public String getHeaderField(String key){return "Location".equals(key)?location:null;}public InputStream getInputStream(){return new ByteArrayInputStream(data);}
    }
    static final class Net implements ApkSource.Transport {
        Map<String,Reply> responses=new HashMap<>();
        public HttpURLConnection open(URL url)throws IOException {Reply r=responses.get(url.toString());if(r==null)throw new IOException("Unexpected request "+url);return r;}
        public InetAddress[] addresses(String host)throws IOException{return new InetAddress[]{InetAddress.getByName(host.equals("private.example")?"192.168.1.1":"8.8.8.8")};}
        void add(Reply r){responses.put(r.getURL().toString(),r);}
    }
    public static void main(String[] args)throws Exception {
        File dir=Files.createTempDirectory("glide-apk-test").toFile(),file=new File(dir,"app.apk");
        try{
            check(ApkSource.input(" 5781040 ").toString().equals("https://go.aftvnews.com/5781040"));
            fails("Enter a Downloader",()->ApkSource.input("invalid input"));
            Net net=new Net();ApkSource source=new ApkSource(net);
            fails("public HTTPS",()->source.validate(new URL("http://public.example/app.apk")));
            fails("public HTTPS",()->source.validate(new URL("https://user:pass@public.example/app.apk")));
            fails("public HTTPS",()->source.validate(new URL("https://public.example:8443/app.apk")));
            fails("private network",()->source.validate(new URL("https://private.example/app.apk")));
            String html="<div id=\"redirect_message\" class=\"redirect-message\">Redirecting to: <a href=\"https://public.example/app.apk?x=1&amp;y=2\">download</a></div>";
            check(ApkSource.codeDestination(new URL("https://go.aftvnews.com/123"),html).toString().endsWith("x=1&y=2"));
            fails("opens a website",()->ApkSource.codeDestination(new URL("https://public.example/page"),html));
            fails("no direct download",()->ApkSource.codeDestination(new URL("https://go.aftvnews.com/999"),"not found"));
            net.add(new Reply("https://go.aftvnews.com/123",200,"text/html",html,null));
            net.add(new Reply("https://public.example/app.apk?x=1&y=2",302,"", "","https://cdn.example/download"));
            net.add(new Reply("https://cdn.example/download",200,"application/octet-stream","APK-fixture",null));
            final long[] received={0};source.download("123",file,(n,total)->received[0]=n);
            check(received[0]==11);check(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8).equals("APK-fixture"));
            net.add(new Reply("https://public.example/private",302,"","","https://private.example/app.apk"));
            fails("private network",()->source.download("https://public.example/private",file,(n,t)->{}));check(!file.exists());
            net.add(new Reply("https://public.example/website",200,"text/html","<html>Not an APK</html>",null));
            fails("opens a website",()->source.download("https://public.example/website",file,(n,t)->{}));check(!file.exists());
            Reply shortReply=new Reply("https://public.example/short",200,"application/octet-stream","short",null);shortReply.size=10;net.add(shortReply);
            fails("incomplete",()->source.download("https://public.example/short",file,(n,t)->{}));check(!file.exists());
            Reply huge=new Reply("https://public.example/huge",200,"application/octet-stream","",null);huge.size=ApkSource.MAX_BYTES+1;net.add(huge);
            fails("limited to 1 GB",()->source.download("https://public.example/huge",file,(n,t)->{}));
            net.add(new Reply("https://public.example/loop",302,"","","/loop"));
            fails("Too many",()->source.download("https://public.example/loop",file,(n,t)->{}));
            System.out.println("PASS: "+checks+" APK source checks");
        }finally{file.delete();dir.delete();}
    }
}
