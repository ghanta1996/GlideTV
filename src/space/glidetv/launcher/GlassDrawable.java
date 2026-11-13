package space.glidetv.launcher;
import android.graphics.*;import android.graphics.drawable.Drawable;
public final class GlassDrawable extends Drawable {
 private final Paint fill=new Paint(3),line=new Paint(3);private final RectF rect=new RectF();private final float radius;private final boolean focused,outlineOnly;
 public GlassDrawable(float r,boolean f){this(r,f,false);}public GlassDrawable(float r,boolean f,boolean o){radius=r;focused=f;outlineOnly=o;line.setStyle(Paint.Style.STROKE);line.setStrokeWidth(f?2.5f:1.2f);}
 @Override protected void onBoundsChange(Rect b){rect.set(b.left+2,b.top+2,b.right-2,b.bottom-2);fill.setShader(new LinearGradient(0,b.top,0,b.bottom,new int[]{focused?0x558baabb:0x25e5f3ff,focused?0x33405566:0x12384b68},null,Shader.TileMode.CLAMP));line.setShader(new LinearGradient(b.left,b.top,b.right,b.bottom,new int[]{focused?0xffe2f4ff:0x88ecf6ff,0x18ffffff,focused?0x99b1dcff:0x45b4c5e5},new float[]{0,.5f,1},Shader.TileMode.CLAMP));}
 @Override public void draw(Canvas c){if(!outlineOnly)c.drawRoundRect(rect,radius,radius,fill);c.drawRoundRect(rect,radius,radius,line);}
 @Override public void setAlpha(int a){}@Override public void setColorFilter(ColorFilter c){}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
