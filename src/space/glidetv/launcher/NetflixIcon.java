package space.glidetv.launcher;
import android.graphics.*;import android.graphics.drawable.Drawable;
/** Compact N mark for the favorite shortcut. */
public final class NetflixIcon extends Drawable {
 private final Paint p=new Paint(3);
 @Override public void draw(Canvas canvas){Rect r=getBounds();canvas.save();canvas.translate(r.left,r.top);canvas.scale(r.width()/100f,r.height()/100f);p.setColor(0xff0b0d11);canvas.drawRoundRect(0,0,100,100,22,22,p);p.setColor(0xffb20710);canvas.drawRect(28,20,41,80,p);canvas.drawRect(59,20,72,80,p);p.setColor(0xffe50914);Path n=new Path();n.moveTo(28,20);n.lineTo(43,20);n.lineTo(72,80);n.lineTo(57,80);n.close();canvas.drawPath(n,p);canvas.restore();}
 @Override public void setAlpha(int a){}@Override public void setColorFilter(ColorFilter f){}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
