package dev.phonexr.vrapidriver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
/** Empty URI grants expose this package to the selected game's package manager. No files served. */
public final class VisibilityProvider extends ContentProvider {
    public static final String AUTHORITY="dev.horizonbridge.legacydriver.visibility";
    @Override public boolean onCreate(){return true;}
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String order){return null;}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
