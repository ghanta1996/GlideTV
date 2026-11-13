package space.glidetv.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.EnumMap;
import java.util.Map;

/** Generates the short-lived local pairing URL as a bitmap for the TV dialog. */
public final class RemoteQr {
    private RemoteQr() { }
    public static Bitmap create(String value, int size) throws WriterException {
        Map<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);
        // High correction leaves room for the small Glide mark in the centre.
        hints.put(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.MARGIN,4);
        // Request the native module grid; drawing it ourselves keeps the dots crisp.
        BitMatrix matrix=new QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,1,1,hints);
        Bitmap bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);
        int ink=0xffc8fff0, accent=0xff65dfb8, dialog=0xff0d1825;
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        int modules=matrix.getWidth(), quiet=4;
        float unit=(float)size/modules, inset=unit*.11f;
        p.setStyle(Paint.Style.FILL);
        // Glass-like gradient ink, with a mix of dots, pills and rounded squares.
        for(int y=quiet;y<modules-quiet;y++)for(int x=quiet;x<modules-quiet;x++){
            int mx=x-quiet,my=y-quiet;
            if(inFinder(mx,my,modules-quiet*2))continue;
            if(!matrix.get(x,y))continue;
            float blend=Math.min(1f,Math.max(0f,(mx*.42f+my*.58f)/(modules-quiet*2-1f)));
            p.setColor(mix(0xffc8fff0,0xff65dfb8,blend));
            float l=x*unit+inset,t=y*unit+inset,r=(x+1)*unit-inset,b=(y+1)*unit-inset;
            int form=(mx*3+my*5)%9;
            if(form<2)canvas.drawCircle((l+r)/2,(t+b)/2,unit*.39f,p);
            else if(form<4)canvas.drawRoundRect(l,t+unit*.17f,r,b-unit*.17f,unit*.38f,unit*.38f,p);
            else canvas.drawRoundRect(l,t,r,b,unit*.3f,unit*.3f,p);
        }
        int count=modules-quiet*2;
        drawFinder(canvas,p,quiet,quiet,unit,ink,accent,dialog);
        drawFinder(canvas,p,quiet+count-7,quiet,unit,ink,accent,dialog);
        drawFinder(canvas,p,quiet,quiet+count-7,unit,ink,accent,dialog);
        // Oversized mint Glide G, outlined in the dialog color so it reads cleanly over the dots.
        float center=size/2f;
        p.setTextAlign(Paint.Align.CENTER);p.setTypeface(android.graphics.Typeface.create("sans-serif-black",android.graphics.Typeface.NORMAL));p.setTextSize(unit*7.6f);
        Paint.FontMetrics fm=p.getFontMetrics();float baseline=center-(fm.ascent+fm.descent)/2f;
        p.setStyle(Paint.Style.STROKE);p.setStrokeJoin(Paint.Join.ROUND);p.setStrokeWidth(unit*1.05f);p.setColor(dialog);canvas.drawText("G",center,baseline,p);
        p.setStyle(Paint.Style.FILL);p.setColor(0xffc8fff0);canvas.drawText("G",center,baseline,p);
        return bitmap;
    }

    private static boolean inFinder(int x,int y,int n){
        return (x<8&&y<8)||(x>=n-8&&y<8)||(x<8&&y>=n-8);
    }
    private static void drawFinder(Canvas c,Paint p,int x,int y,float u,int ink,int accent,int dialog){
        float l=x*u,t=y*u;
        p.setColor(ink);c.drawRoundRect(l+u*.12f,t+u*.12f,l+u*6.88f,t+u*6.88f,u*1.5f,u*1.5f,p);
        p.setColor(dialog);c.drawRoundRect(l+u*.98f,t+u*.98f,l+u*6.02f,t+u*6.02f,u*1.1f,u*1.1f,p);
        p.setColor(accent);c.drawRoundRect(l+u*2.02f,t+u*2.02f,l+u*4.98f,t+u*4.98f,u*.9f,u*.9f,p);
    }
    private static int mix(int a,int b,float t){
        int r=Math.round(((a>>16)&255)*(1-t)+((b>>16)&255)*t);
        int g=Math.round(((a>>8)&255)*(1-t)+((b>>8)&255)*t);
        int bl=Math.round((a&255)*(1-t)+(b&255)*t);
        return 0xff000000|(r<<16)|(g<<8)|bl;
    }
}
