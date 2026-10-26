import local.airuize.receiver.*;
import android.content.SharedPreferences;
import java.lang.reflect.*;
import java.util.*;

/** Runs the actual preference writer/reader against an Android SharedPreferences contract double.
 * Never instantiates a framework Context or pretends to validate Android disk storage. */
public final class CheckDisplayPersistence {
    static final class Store implements InvocationHandler {
        final Map<String,Object> values=new HashMap<>(),pending=new HashMap<>();
        int commits=0;boolean successful=true;
        final SharedPreferences preferences=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},this);
        final SharedPreferences.Editor editor=(SharedPreferences.Editor)Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class[]{SharedPreferences.Editor.class},this);
        public Object invoke(Object self,Method method,Object[] args) {
            String name=method.getName();
            if(name.equals("edit")){pending.clear();return editor;}
            if(name.startsWith("put")){pending.put((String)args[0],args[1]);return editor;}
            if(name.equals("commit")){commits++;if(successful)values.putAll(pending);return successful;}
            if(name.equals("getAll"))return new HashMap<>(values);
            if(name.startsWith("get"))return values.containsKey(args[0])?values.get(args[0]):args[1];
            throw new AssertionError("Unexpected preference operation: "+name);
        }
    }
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args) {
        Store store=new Store();store.values.put("phoneAddress","preserved");store.values.put("fps",15);store.values.put("scale",6);
        store.values.put("videoOutput","texture");store.values.put("physicalMm",250);store.values.put("volume",65);
        ReceiverSettings before=ReceiverSettings.Companion.fromPreferences(store.preferences);
        ReceiverSettings selected=new ProjectionResolution(1024,600).applyTo(before);
        DisplayPreferences.INSTANCE.save(store.preferences,selected);
        require(store.commits==1,"Display selection must use one atomic preference commit");
        // A newly read settings object must use the exact same saved values used by USB startup.
        ReceiverSettings fresh=ReceiverSettings.Companion.fromPreferences(store.preferences);
        require(fresh.getWidth()==1024&&fresh.getHeight()==600&&fresh.getScale()==10,"Resolution roundtrip");
        require(fresh.getFps()==15&&fresh.display().getFps()==15,"15fps roundtrip");
        require(fresh.getPhysicalMm()==250&&fresh.getVideoOutput().equals("texture"),"Layout/output roundtrip");
        require(fresh.getVolume()==65&&store.values.get("phoneAddress").equals("preserved"),"Unrelated settings were changed");
        try {
            BlackScreenDiagnostics.INSTANCE.setCurrent(BlackScreenDiagnostics.INSTANCE.getTrials().get(3));
            ReceiverSettings trial=ReceiverSettings.Companion.fromPreferences(store.preferences);
            require(trial.getWidth()==640&&trial.getFps()==30,"Diagnostic override did not apply");
        } finally {BlackScreenDiagnostics.INSTANCE.setCurrent(null);}
        fresh=ReceiverSettings.Companion.fromPreferences(store.preferences);
        require(fresh.getWidth()==1024&&fresh.getHeight()==600&&fresh.getFps()==15,"Diagnostic trial overwrote saved settings");
        store.successful=false;
        try{DisplayPreferences.INSTANCE.save(store.preferences,selected);throw new AssertionError("Failed commit accepted");}
        catch(IllegalStateException expected){}
        System.out.println("PASS: actual display preference writer/reader roundtrip, atomic save, 15fps, unrelated values retained, diagnostic override isolated, failed commit rejected");
    }
}
