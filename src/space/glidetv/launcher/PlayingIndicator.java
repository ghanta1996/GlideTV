package space.glidetv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

final class PlayingIndicator extends View {
    private final Paint paint=new Paint(3);
    private boolean playing;

    PlayingIndicator(Context context){super(context);paint.setColor(0xffbee9cf);}

    void setPlaying(boolean value){if(playing==value)return;playing=value;invalidate();}

    @Override protected void onDraw(Canvas canvas){
        float width=getWidth(),height=getHeight(),bar=width/7f,gap=bar,now=SystemClock.uptimeMillis()/170f;
        for(int n=0;n<3;n++){
            float level=playing ? .34f+.58f*(.5f+.5f*(float)Math.sin(now+n*1.7f)) : .28f;
            float left=gap+n*(bar+gap),top=height*(1-level);
            canvas.drawRoundRect(left,top,left+bar,height,bar*.5f,bar*.5f,paint);
        }
        if(playing)postInvalidateOnAnimation();
    }
}
