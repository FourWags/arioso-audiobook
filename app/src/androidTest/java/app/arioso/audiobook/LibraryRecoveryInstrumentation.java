package app.arioso.audiobook;
import android.app.*;import android.content.*;import android.os.Bundle;import org.json.*;import java.util.*;
public final class LibraryRecoveryInstrumentation extends Instrumentation {
 public void onCreate(Bundle a){super.onCreate(a);start();}
 public void onStart(){Bundle out=new Bundle();Activity activity=null;try{
  Context c=getTargetContext();if(!c.getPackageName().endsWith(".qa")||!"ranchu".equals(android.os.Build.HARDWARE))throw new AssertionError("Fresh audit emulator QA only");
  LibraryStore store=LibraryStore.get(c);
  // Permit repeat runs only over this fixture's exact keys on the guarded QA emulator.
  try(android.database.Cursor existing=store.getWritableDatabase().rawQuery("SELECT uri FROM books",null)){
   while(existing.moveToNext())if(!"corrupt-audit-row".equals(existing.getString(0))&&!"content://com.android.externalstorage.documents/document/primary%3AAudit.wav".equals(existing.getString(0)))throw new AssertionError("Unrecognized data; refuse fixture reset");
  }
  store.getWritableDatabase().delete("books",null,null);
  if(store.readAll().length()!=0)throw new AssertionError("Must be empty");
  JSONObject good=new JSONObject().put("uri","content://com.android.externalstorage.documents/document/primary%3AAudit.wav").put("title","Synthetic preserved title").put("position",12345);
  if(!store.replaceAll(Collections.singletonList(good)))throw new AssertionError("seed failed");
  store.getWritableDatabase().execSQL("INSERT INTO books(uri,ordinal,data) VALUES('corrupt-audit-row',1,'{bad')");
  activity=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
  java.lang.reflect.Field books=MainActivity.class.getDeclaredField("books");books.setAccessible(true);out.putInt("loaded",((List)books.get(activity)).size());
  java.lang.reflect.Method save=MainActivity.class.getDeclaredMethod("saveLibraryNow",boolean.class);save.setAccessible(true);final Activity a=activity;final boolean[] result={false};runOnMainSync(()->{try{result[0]=(Boolean)save.invoke(a,false);}catch(Exception e){throw new RuntimeException(e);}});
  out.putBoolean("saveAccepted",result[0]);try(android.database.Cursor cur=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM books",null)){cur.moveToFirst();out.putInt("rowsAfter",cur.getInt(0));}
  if(result[0] || out.getInt("rowsAfter") != 2)throw new AssertionError("Failed read must preserve both original rows");
  if(store.replaceAll(Collections.emptyList()))throw new AssertionError("Store accepted replacing malformed metadata");
  // A later successful raw read must not authorize a stale empty Activity list.
  store.getWritableDatabase().execSQL("DELETE FROM books WHERE uri='corrupt-audit-row'");
  runOnMainSync(()->{try{result[0]=(Boolean)save.invoke(a,false);}catch(Exception e){throw new RuntimeException(e);}});
  if(result[0] || store.readAll().length()!=1)throw new AssertionError("Read failure latch was bypassed");
  java.lang.reflect.Method reload=MainActivity.class.getDeclaredMethod("readLibrary");reload.setAccessible(true);
  runOnMainSync(()->{try{reload.invoke(a);result[0]=(Boolean)save.invoke(a,false);}catch(Exception e){throw new RuntimeException(e);}});
  if(!result[0] || store.readAll().length()!=1 || store.readAll().getJSONObject(0).getInt("position")!=12345)throw new AssertionError("Successful reload did not recover preserved metadata");
  // A failure after a successful load must also stop replacement.
  store.getWritableDatabase().execSQL("INSERT INTO books(uri,ordinal,data) VALUES('corrupt-audit-row',1,'{bad')");
  runOnMainSync(()->{try{result[0]=(Boolean)save.invoke(a,false);}catch(Exception e){throw new RuntimeException(e);}});
  if(result[0])throw new AssertionError("Progress read failure was ignored");
  store.getWritableDatabase().execSQL("UPDATE books SET data='{}' WHERE uri='corrupt-audit-row'");
  if(store.replaceAll(Collections.emptyList()))throw new AssertionError("Store erased structurally invalid metadata");
  out.putString("regression","PASS: failed initial load, store guard, sticky failure, successful reload, later failed synchronization, structurally invalid row");
  out.putString("scenario","2 synthetic rows including malformed JSON; failed initial read followed by ordinary save boundary");finish(Activity.RESULT_OK,out);
 }catch(Throwable e){out.putString("error",e.toString());finish(Activity.RESULT_CANCELED,out);}finally{if(activity!=null){final Activity a=activity;runOnMainSync(a::finish);}}}
}
