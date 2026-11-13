package space.glidetv.launcher;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Local-network browser remote. The API is allowlisted; it never accepts shell commands or paths. */
public final class RemoteControlServer {
    public interface Host {
        JSONObject status() throws Exception;
        JSONArray apps() throws Exception;
        boolean launch(String packageName);
        boolean stop(String packageName);
        void paired();
        void key(String key);
        void home();
        void volume(String direction);
        JSONObject processes() throws Exception;
        JSONObject clearMemory() throws Exception;
        JSONArray files(String path) throws Exception;
        String folderLabel(String path);
        FileInfo upload(String name, InputStream input, long length) throws Exception;
        FileInfo file(String id) throws Exception;
        InputStream openFile(String id) throws Exception;
        boolean install(String id) throws Exception;
        void remoteStateChanged(boolean active);
    }
    public static final class FileInfo {
        public final String id, name, mime;
        public final long size;
        public FileInfo(String id, String name, String mime, long size) { this.id=id; this.name=name; this.mime=mime; this.size=size; }
        JSONObject json() throws Exception { JSONObject j=new JSONObject();j.put("id",id);j.put("name",name);j.put("mime",mime);j.put("size",size);return j; }
    }

    private static final int FIRST_PORT = 8765;
    private static final int LAST_PORT = 8775;
    private static final long MAX_UPLOAD = 2L * 1024 * 1024 * 1024;
    private static final long PIN_TTL = 10 * 60 * 1000L;
    private static final long SESSION_TTL = 30 * 60 * 1000L;
    private static final long IDLE_TTL = 5 * 60 * 1000L;
    private final Activity activity;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService workers = new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(12),new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentHashMap<String,Attempt> attempts = new ConcurrentHashMap<>();
    private volatile ServerSocket server;
    private volatile int boundPort;
    private volatile boolean running;
    private volatile String pin, token;
    private volatile long pinExpires, tokenExpires;
    private volatile long lastCommandAt;
    private final Runnable idleCheck = this::checkIdle;
    private void checkIdle(){
        if (!running || token == null) return;
        long remaining=IDLE_TTL-(System.currentTimeMillis()-lastCommandAt);
        if (remaining<=0) stop();
        else main.postDelayed(idleCheck,remaining);
    }
    private Thread acceptThread;
    private ApkDownloads downloads;
    private synchronized ApkDownloads downloads(){if(downloads==null)downloads=new ApkDownloads(activity);return downloads;}

    private static final class Attempt { int failures; long blockedUntil, window; }
    public RemoteControlServer(Activity activity, Host host) { this.activity=activity; this.host=host; }

    public synchronized void start() throws IOException {
        if (running && server!=null && !server.isClosed()) { rotatePin(); return; }
        running=false;
        if(server!=null)try{server.close();}catch(IOException ignored){}
        server=null;boundPort=0;
        IOException last=null;ServerSocket socket=null;
        for(int port=FIRST_PORT;port<=LAST_PORT;port++){
            ServerSocket candidate=new ServerSocket();
            try{candidate.setReuseAddress(true);candidate.bind(new InetSocketAddress(port),8);socket=candidate;boundPort=port;break;}
            catch(IOException e){last=e;try{candidate.close();}catch(IOException ignored){}}
        }
        if(socket==null)throw new IOException("Remote ports 8765–8775 are busy. Close another TV remote session and try again.",last);
        final ServerSocket listenSocket=socket;
        server=listenSocket; running=true; rotatePin();
        acceptThread=new Thread(()->acceptLoop(listenSocket),"GlideRemoteHTTP");
        acceptThread.setDaemon(true); acceptThread.start();
    }

    public synchronized void stop() {
        boolean wasActive=token!=null;
        running=false; token=null; pin=null;
        main.removeCallbacks(idleCheck);
        try { if(server!=null) server.close(); } catch(Exception ignored) { }
        server=null;boundPort=0;
        if(downloads!=null){downloads.close();downloads=null;}
        if(wasActive)main.post(()->host.remoteStateChanged(false));
    }

    public boolean isRunning() { return running; }
    public String getPin() { return pin; }
    public int getPort() { return boundPort>0?boundPort:FIRST_PORT; }
    public String getPairUrl() { return "http://"+getAddress()+":"+getPort()+"/?pin="+pin; }
    public long getPinSecondsRemaining() { return Math.max(0,(pinExpires-System.currentTimeMillis())/1000); }
    public String getAddress() {
        try {
            Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();
            while(all.hasMoreElements()) {
                NetworkInterface nic=all.nextElement();
                if(!nic.isUp()||nic.isLoopback()||nic.isVirtual()) continue;
                Enumeration<InetAddress> addresses=nic.getInetAddresses();
                while(addresses.hasMoreElements()) {
                    InetAddress a=addresses.nextElement();
                    if(a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress() && privateAddress(a)) return a.getHostAddress();
                }
            }
        } catch(Exception ignored) { }
        return "TV-IP";
    }

    private boolean privateAddress(InetAddress a) {
        byte[] b=a.getAddress();
        return (b[0]&255)==10 || ((b[0]&255)==172 && (b[1]&240)==16) || ((b[0]&255)==192 && (b[1]&255)==168);
    }
    private synchronized void rotatePin() {
        boolean wasActive=token!=null;
        token=null; tokenExpires=0;
        main.removeCallbacks(idleCheck);
        if(wasActive)main.post(()->host.remoteStateChanged(false));
        pin=String.format(Locale.US,"%08d",random.nextInt(100000000));
        pinExpires=System.currentTimeMillis()+PIN_TTL;
    }

    private void acceptLoop(ServerSocket socket) {
        while(running && !socket.isClosed()) {
            try {
                Socket client=socket.accept();
                if(!samePrivateSubnet(client.getInetAddress())) { client.close(); continue; }
                client.setSoTimeout(45000);
                workers.execute(()->handle(client));
            } catch(RejectedExecutionException e) { }
            catch(IOException e) { if(running) android.util.Log.w("GlideRemote","accept failed",e); }
        }
    }

    private boolean samePrivateSubnet(InetAddress remote) {
        try {
            Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();
            while(all.hasMoreElements()) {
                NetworkInterface nic=all.nextElement();
                for(InterfaceAddress a:nic.getInterfaceAddresses()) {
                    InetAddress local=a.getAddress();
                    if(local instanceof Inet4Address && !local.isLoopbackAddress() && a.getNetworkPrefixLength()<=32 && samePrefix(local.getAddress(),remote.getAddress(),a.getNetworkPrefixLength())) return true;
                }
            }
        } catch(Exception ignored) { }
        return false;
    }
    private boolean samePrefix(byte[] a, byte[] b, int bits) {
        if(b==null||a.length!=4||b.length!=4) return false;
        for(int i=0;i<4;i++) { int remaining=bits-i*8; if(remaining<=0) break; int mask=remaining>=8?255:(255<<(8-remaining))&255; if((a[i]&mask)!=(b[i]&mask)) return false; }
        return true;
    }

    private void handle(Socket socket) {
        try(Socket s=socket) {
            InputStream in=new BufferedInputStream(s.getInputStream());
            OutputStream out=new BufferedOutputStream(s.getOutputStream());
            String first=readLine(in); if(first==null||first.length()>8192) return;
            String[] parts=first.split(" ",3); if(parts.length<2) { respond(out,400,"text/plain","Bad request"); return; }
            String method=parts[0], target=parts[1];
            HashMap<String,String> headers=new HashMap<>(); String line; int headerBytes=first.length();
            while((line=readLine(in))!=null&&!line.isEmpty()) {
                headerBytes+=line.length(); if(headerBytes>16384) { respond(out,431,"text/plain","Headers too large"); return; }
                int colon=line.indexOf(':'); if(colon>0) headers.put(line.substring(0,colon).trim().toLowerCase(Locale.US),line.substring(colon+1).trim());
            }
            long length=0; try { length=Long.parseLong(headers.getOrDefault("content-length","0")); } catch(Exception ignored) { }
            if(length<0||length>MAX_UPLOAD) { respond(out,413,"application/json","{\"error\":\"Upload too large\"}"); return; }
            if(headers.getOrDefault("transfer-encoding","").toLowerCase(Locale.US).contains("chunked")) { respond(out,411,"application/json","{\"error\":\"Content-Length required\"}"); return; }
            Uri uri=Uri.parse("http://local"+target); String path=uri.getPath();
            if("GET".equals(method)&&"/".equals(path)) { servePage(out); return; }
            if("POST".equals(method)&&"/pair".equals(path)) { pair(in,out,length,s.getInetAddress().getHostAddress()); return; }
            if(!authorized(headers)) { respond(out,401,"application/json","{\"error\":\"Pair this phone with the PIN shown on the TV\"}"); return; }
            if(!"GET".equals(method)||(!"/api/status".equals(path)&&!"/api/apk-download".equals(path))) touchCommand();
            try {
                if("GET".equals(method)&&"/api/status".equals(path)) json(out,200,host.status());
                else if("GET".equals(method)&&"/api/apps".equals(path)) { JSONObject j=new JSONObject();j.put("apps",host.apps());json(out,200,j); }
                else if("POST".equals(method)&&"/api/key".equals(path)) { JSONObject b=bodyJson(in,length);String key=b.optString("key","");if(!Arrays.asList("up","down","left","right","ok","back","library").contains(key))throw new IllegalArgumentException("Unknown key");main.post(()->host.key(key));ok(out,"sent"); }
                else if("POST".equals(method)&&"/api/home".equals(path)) { main.post(()->host.home());ok(out,"home"); }
                else if("POST".equals(method)&&"/api/volume".equals(path)) { JSONObject b=bodyJson(in,length);String d=b.optString("direction","");if(!Arrays.asList("up","down","mute").contains(d))throw new IllegalArgumentException("Unknown volume action");main.post(()->host.volume(d));ok(out,"volume"); }
                else if("POST".equals(method)&&"/api/launch".equals(path)) { JSONObject b=bodyJson(in,length);String pkg=b.optString("package","");if(!host.launch(pkg))throw new IllegalArgumentException("App is not available");ok(out,"launched"); }
                else if("POST".equals(method)&&"/api/stop".equals(path)) { JSONObject b=bodyJson(in,length);String pkg=b.optString("package","");if(!host.stop(pkg))throw new IllegalArgumentException("App cannot be stopped");ok(out,"stopped"); }
                else if("GET".equals(method)&&"/api/processes".equals(path)) json(out,200,host.processes());
                else if("POST".equals(method)&&"/api/clear-memory".equals(path)) json(out,200,host.clearMemory());
                else if("GET".equals(method)&&"/api/files".equals(path)) { String folder=uri.getQueryParameter("path");if(folder==null)folder="";JSONObject j=new JSONObject();j.put("entries",host.files(folder));j.put("path",folder);j.put("folder",host.folderLabel(folder));j.put("parent",folder.contains("/")?folder.substring(0,folder.lastIndexOf('/')):"");json(out,200,j); }
                else if("POST".equals(method)&&"/api/upload".equals(path)) { String name=uri.getQueryParameter("name");FileInfo f=host.upload(name,in,length);JSONObject j=f.json();j.put("message","Uploaded to the TV");json(out,200,j); }
                else if("GET".equals(method)&&"/api/file".equals(path)) streamFile(out,uri.getQueryParameter("id"));
                else if("POST".equals(method)&&"/api/install".equals(path)) { JSONObject b=bodyJson(in,length);if(!host.install(b.optString("id","")))throw new IllegalStateException("Install could not be started");ok(out,"Android's installer will ask for confirmation on the TV"); }
                else if("POST".equals(method)&&"/api/apk-download".equals(path)) { JSONObject b=bodyJson(in,length);json(out,200,downloads().start(b.optString("source",""))); }
                else if("GET".equals(method)&&"/api/apk-download".equals(path)) json(out,200,downloads().snapshot(uri.getQueryParameter("id")));
                else if("POST".equals(method)&&"/api/apk-download/install".equals(path)) { JSONObject b=bodyJson(in,length);downloads().install(b.optString("id",""));ok(out,"Confirm installation on the TV"); }
                else if("POST".equals(method)&&"/api/apk-download/cancel".equals(path)) { JSONObject b=bodyJson(in,length);downloads().cancel(b.optString("id",""));ok(out,"Download cancelled"); }
                else respond(out,404,"application/json","{\"error\":\"Not found\"}");
            } catch(Exception e) { JSONObject j=new JSONObject();j.put("error",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());json(out,400,j); }
        } catch(Exception ignored) { }
    }

    private synchronized void pair(InputStream in,OutputStream out,long length,String remoteIp) throws Exception {
        Attempt a=attempts.computeIfAbsent(remoteIp,k->new Attempt()); long now=System.currentTimeMillis();
        synchronized(a) {
            if(a.blockedUntil>now) { respond(out,429,"application/json","{\"error\":\"Try again shortly\"}");return; }
            if(now-a.window>60000) { a.window=now;a.failures=0; }
            JSONObject body=bodyJson(in,length); String supplied=body.optString("pin","");
            if(now>pinExpires||!constantTime(pin==null?"":pin,supplied)) {
                a.failures++; if(a.failures>=5) { a.blockedUntil=now+60000;a.failures=0; }
                respond(out,403,"application/json","{\"error\":\"PIN incorrect or expired\"}");return;
            }
            token=randomHex(32); tokenExpires=now+SESSION_TTL; a.failures=0; touchCommand();
            main.post(()->host.paired());
            JSONObject result=new JSONObject();result.put("token",token);result.put("expiresIn",SESSION_TTL/1000);json(out,200,result);
        }
    }
    private void touchCommand(){lastCommandAt=System.currentTimeMillis();main.removeCallbacks(idleCheck);main.postDelayed(idleCheck,IDLE_TTL);}
    private boolean authorized(Map<String,String> headers) {
        String supplied=headers.getOrDefault("authorization",""); if(supplied.startsWith("Bearer ")) supplied=supplied.substring(7);
        return token!=null && System.currentTimeMillis()<tokenExpires && constantTime(token,supplied);
    }
    private boolean constantTime(String a,String b) { try{return MessageDigest.isEqual(a.getBytes("UTF-8"),b.getBytes("UTF-8"));}catch(Exception e){return false;} }
    private String randomHex(int bytes) { byte[] data=new byte[bytes];random.nextBytes(data);StringBuilder b=new StringBuilder();for(byte v:data)b.append(String.format(Locale.US,"%02x",v&255));return b.toString(); }

    private void servePage(OutputStream out) throws Exception {
        byte[] page; try(InputStream in=activity.getAssets().open("remote/index.html")){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);page=b.toByteArray();}
        respond(out,200,"text/html; charset=utf-8",page);
    }
    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1) { if(c=='\n')break;if(c!='\r')b.write(c);if(b.size()>8192)throw new IOException("Line too long"); }
        if(c==-1&&b.size()==0)return null;return b.toString("UTF-8");
    }
    private byte[] readBody(InputStream in,long length,int max) throws IOException {
        if(length>max)throw new IOException("Request too large");byte[] data=new byte[(int)length];int off=0;while(off<data.length){int n=in.read(data,off,data.length-off);if(n<0)throw new EOFException();off+=n;}return data;
    }
    private JSONObject bodyJson(InputStream in,long length) throws Exception { return new JSONObject(new String(readBody(in,length,1024*1024),StandardCharsets.UTF_8)); }
    private InputStream limited(InputStream in,long length) { return new FilterInputStream(in){long remaining=length;public int read()throws IOException{if(remaining<=0)return -1;int b=super.read();if(b>=0)remaining--;return b;}public int read(byte[] b,int off,int len)throws IOException{if(remaining<=0)return -1;int n=super.read(b,off,(int)Math.min(len,remaining));if(n>0)remaining-=n;return n;}}; }
    private void streamFile(OutputStream out,String id) throws Exception {
        FileInfo f=host.file(id);InputStream in=host.openFile(id);
        try { writeHeaders(out,200,f.mime,f.size,"attachment; filename*=UTF-8''"+URLEncoder.encode(f.name,"UTF-8").replace("+","%20"));byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.flush(); } finally { in.close(); }
    }
    private void ok(OutputStream out,String message) throws Exception { JSONObject j=new JSONObject();j.put("ok",true);j.put("message",message);json(out,200,j); }
    private void json(OutputStream out,int status,JSONObject j) throws Exception { respond(out,status,"application/json; charset=utf-8",j.toString().getBytes(StandardCharsets.UTF_8)); }
    private void respond(OutputStream out,int status,String type,String body) throws IOException { respond(out,status,type,body.getBytes(StandardCharsets.UTF_8)); }
    private void respond(OutputStream out,int status,String type,byte[] data) throws IOException { writeHeaders(out,status,type,data.length,null);out.write(data);out.flush(); }
    private void writeHeaders(OutputStream out,int status,String type,long length,String disposition) throws IOException {
        String label=status==200?"OK":status==401?"Unauthorized":status==403?"Forbidden":status==404?"Not Found":status==411?"Length Required":status==413?"Payload Too Large":status==429?"Too Many Requests":status==431?"Request Header Fields Too Large":"Bad Request";
        StringBuilder h=new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(label).append("\r\nContent-Type: ").append(type).append("\r\nContent-Length: ").append(length).append("\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n");
        if(disposition!=null)h.append("Content-Disposition: ").append(disposition).append("\r\n");h.append("\r\n");out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
    }
}
