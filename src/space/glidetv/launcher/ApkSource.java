package space.glidetv.launcher;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.*;

/** Public HTTPS APK sources and AFTVnews Downloader codes. No browser or shell execution. */
final class ApkSource {
    static final long MAX_BYTES=1024L*1024*1024;
    interface Progress { void update(long bytes,long total); }
    interface Transport { HttpURLConnection open(URL url)throws IOException; InetAddress[] addresses(String host)throws IOException; }
    private final Transport transport;
    ApkSource(){this(new Transport(){public HttpURLConnection open(URL u)throws IOException{return (HttpURLConnection)u.openConnection();}public InetAddress[] addresses(String h)throws IOException{return InetAddress.getAllByName(h);}});}
    ApkSource(Transport transport){this.transport=transport;}

    static URL input(String value)throws IOException {
        String s=value==null?"":value.trim();
        if(s.matches("[0-9]{1,10}"))s="https://go.aftvnews.com/"+s;
        if(s.length()>4096)throw new IOException("The link is too long.");
        try{return new URL(s);}catch(MalformedURLException e){throw new IOException("Enter a Downloader code or a direct HTTPS APK link.");}
    }
    void validate(URL url)throws IOException {
        if(!"https".equalsIgnoreCase(url.getProtocol())||url.getUserInfo()!=null||url.getHost().isEmpty()||(url.getPort()!=-1&&url.getPort()!=443))throw new IOException("Use a public HTTPS download link.");
        InetAddress[] addresses=transport.addresses(url.getHost());
        if(addresses.length==0)throw new IOException("The download server could not be found.");
        for(InetAddress a:addresses){byte[] b=a.getAddress();int first=b[0]&255;
            if(a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress()||
                (b.length==16&&(first&0xfe)==0xfc)||(b.length==4&&(first==0||first>=224||(first==100&&(b[1]&255)>=64&&(b[1]&255)<=127))))throw new IOException("Local and private network download links are not supported.");
        }
    }
    static URL codeDestination(URL url,String html)throws IOException {
        if(!"go.aftvnews.com".equalsIgnoreCase(url.getHost())||!url.getPath().matches("/[0-9]{1,10}/?"))throw new IOException("This link opens a website. Use its direct APK download link instead.");
        // AFTVnews exposes the destination as a normal link inside its redirect message.
        Matcher m=Pattern.compile("(?is)id\\s*=\\s*[\"']redirect_(?:message|noscript)[\"'][^>]*>.*?<a\\s+[^>]*href\\s*=\\s*[\"']([^\"']+)[\"']").matcher(html);
        if(!m.find())throw new IOException("This Downloader code has no direct download. Check the code or paste an HTTPS APK link.");
        String target=m.group(1).replace("&amp;","&").replace("&#38;","&").replace("&quot;","\"").replace("&#39;","'");
        return new URL(url,target);
    }
    void download(String source,File target,Progress progress)throws IOException {
        URL url=input(source);long deadline=System.nanoTime()+10L*60*1000000000L;
        try {
            for(int hop=0;hop<10;hop++){
                if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new IOException("Download cancelled or timed out.");
                validate(url);HttpURLConnection c=transport.open(url);
                c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("User-Agent","GlideTV-Downloader/4.5");c.setRequestProperty("Accept-Encoding","identity");
                try {
                    int status=c.getResponseCode();
                    if(status==301||status==302||status==303||status==307||status==308){String next=c.getHeaderField("Location");if(next==null)throw new IOException("The server returned an empty redirect.");url=new URL(url,next);continue;}
                    if(status!=200)throw new IOException("Download server returned HTTP "+status+".");
                    String type=c.getContentType();type=type==null?"":type.toLowerCase(Locale.US);
                    if(type.contains("text/html")){
                        if(!"go.aftvnews.com".equalsIgnoreCase(url.getHost()))throw new IOException("This link opens a website. Use its direct APK download link instead.");
                        try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>262144)throw new IOException("The Downloader response is too large.");out.write(b,0,n);}
                            url=codeDestination(url,out.toString("UTF-8"));continue;
                        }
                    }
                    long expected=c.getContentLengthLong();if(expected>MAX_BYTES)throw new IOException("APK downloads are limited to 1 GB.");
                    if(target.getParentFile().getUsableSpace()<Math.max(expected,0)+32L*1024*1024)throw new IOException("Not enough free TV storage for this download.");
                    long total=0;
                    try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(target)){
                        byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){
                            if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new IOException("Download cancelled or timed out.");
                            total+=n;if(total>MAX_BYTES)throw new IOException("APK downloads are limited to 1 GB.");
                            if(target.getParentFile().getUsableSpace()<32L*1024*1024)throw new IOException("Not enough free TV storage.");
                            out.write(b,0,n);progress.update(total,expected);
                        }
                    }
                    if(total==0||(expected>=0&&total!=expected))throw new IOException("The APK download was incomplete. Please retry.");
                    return;
                }finally{c.disconnect();}
            }
            throw new IOException("Too many download redirects.");
        }catch(IOException|RuntimeException e){target.delete();throw e;}
    }
}
