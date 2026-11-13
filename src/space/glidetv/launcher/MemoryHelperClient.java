package space.glidetv.launcher;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.Locale;

/** Authenticated client for the fixed-operation, loopback-only ADB helper. */
final class MemoryHelperClient {
    static final class Download implements Closeable {
        final JSONObject metadata;
        final long size;
        final DataInputStream input;
        final Socket socket;
        Download(JSONObject metadata,long size,DataInputStream input,Socket socket){this.metadata=metadata;this.size=size;this.input=input;this.socket=socket;}
        @Override public void close() throws IOException { socket.close(); }
    }
    private MemoryHelperClient() { }
    static synchronized String token(Context context)throws Exception{
        File f=new File(context.getExternalFilesDir(null),"ram-helper-token");
        if(f.isFile())try(BufferedReader r=new BufferedReader(new FileReader(f))){String value=r.readLine();if(value!=null&&value.length()==64)return value;}
        byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);StringBuilder t=new StringBuilder();for(byte b:bytes)t.append(String.format(Locale.US,"%02x",b&255));
        File parent=f.getParentFile();if(parent!=null)parent.mkdirs();try(FileWriter w=new FileWriter(f)){w.write(t.toString());}return t.toString();
    }
    static Socket connect(Context context)throws Exception{
        Socket s=new Socket();s.connect(new InetSocketAddress("127.0.0.1",43817),1800);s.setSoTimeout(30000);
        DataOutputStream out=new DataOutputStream(s.getOutputStream());out.writeUTF(token(context));out.flush();return s;
    }
    static JSONObject request(Context context,String operation)throws Exception{
        try(Socket s=connect(context)){DataOutputStream out=new DataOutputStream(s.getOutputStream());out.writeUTF(operation);out.flush();JSONObject r=new JSONObject(new DataInputStream(s.getInputStream()).readUTF());if(!r.optBoolean("ok"))throw new IOException(r.optString("error","Helper request failed"));return r;}
    }
    static Download download(Context context,String relativePath)throws Exception{
        Socket s=connect(context);try{DataOutputStream out=new DataOutputStream(s.getOutputStream());out.writeUTF("read:"+relativePath);out.flush();DataInputStream in=new DataInputStream(s.getInputStream());JSONObject metadata=new JSONObject(in.readUTF());if(!metadata.optBoolean("ok"))throw new IOException(metadata.optString("error","File unavailable"));long size=in.readLong();return new Download(metadata,size,in,s);}catch(Exception e){try{s.close();}catch(Exception ignored){}throw e;}
    }
    static JSONObject upload(Context context,String name,InputStream source,long size)throws Exception{
        try(Socket s=connect(context)){s.setSoTimeout(120000);DataOutputStream out=new DataOutputStream(s.getOutputStream());out.writeUTF("upload");out.writeUTF(name);out.writeLong(size);byte[] buffer=new byte[65536];long copied=0;while(copied<size){int n=source.read(buffer,0,(int)Math.min(buffer.length,size-copied));if(n<0)throw new EOFException("Upload ended early");out.write(buffer,0,n);copied+=n;}out.flush();JSONObject r=new JSONObject(new DataInputStream(s.getInputStream()).readUTF());if(!r.optBoolean("ok"))throw new IOException(r.optString("error","Upload failed"));return r;}
    }
}
