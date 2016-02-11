/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cyanogenmod.providers.datausage;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;

import cyanogenmod.providers.DataUsageContract;

/**
 * ContentProvider for the DataUsage statistics gathering of the Settings App
 * Keeps track of various per App configuration/state variables that are used to determine
 * if and when to generate an App specific DataUsage warning
 */

public class DataUsageProvider extends ContentProvider {
    private static final boolean DEBUG = false;
    private static final String TAG = DataUsageProvider.class.getSimpleName();
    private static final String DATABASE_NAME = "datausage.db";
    private static final int DATABASE_VERSION = 1;

    private DatabaseHelper mOpenHelper;

    // define database matching constants
    private static final int DATAUSAGE_ALL      = 0;
    private static final int DATAUSAGE_ID       = 1;
    private static final int DATAUSAGE_UID      = 2;

    // build a URI matcher - add routes to it (if any)
    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        sURIMatcher.addURI(DataUsageContract.DATAUSAGE_AUTHORITY,
                DataUsageContract.DATAUSAGE_TABLE,            DATAUSAGE_ALL);
        sURIMatcher.addURI(DataUsageContract.DATAUSAGE_AUTHORITY,
                DataUsageContract.DATAUSAGE_TABLE + "/#",     DATAUSAGE_ID);
        sURIMatcher.addURI(DataUsageContract.DATAUSAGE_AUTHORITY,
                DataUsageContract.DATAUSAGE_TABLE + "/uid/*", DATAUSAGE_UID);
    }

    // Database Helper Class
    private static class DatabaseHelper extends SQLiteOpenHelper {
        private Context mContext;

        public DatabaseHelper (Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            mContext = context;
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // setup database schema
            db.execSQL(
                    "CREATE TABLE " + DataUsageContract.DATAUSAGE_TABLE +
                            "(" + DataUsageContract._ID + " INTEGER PRIMARY KEY, " +
                            DataUsageContract.UID + " INTEGER, " +
                            DataUsageContract.ENB + " INTEGER DEFAULT 0, " +
                            DataUsageContract.ACTIVE + " INTEGER DEFAULT 0, " +
                            DataUsageContract.LABEL + " STRING, " +
                            DataUsageContract.BYTES + " INTEGER DEFAULT 0, " +
                            DataUsageContract.SLOW_AVG + " INTEGER DEFAULT 0, " +
                            DataUsageContract.SLOW_SAMPLES + " INTEGER DEFAULT 0, " +
                            DataUsageContract.FAST_AVG + " INTEGER DEFAULT 0, " +
                            DataUsageContract.FAST_SAMPLES + " INTEGER DEFAULT 0, " +
                            DataUsageContract.EXTRA + " STRING );"
            );
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            return;
        }
    }


    @Override
    public boolean onCreate() {
        mOpenHelper = new DatabaseHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder
    ) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(DataUsageContract.DATAUSAGE_TABLE);

        int match = sURIMatcher.match(uri);

        if (DEBUG) {
            Log.v(TAG, "Query uri=" + uri + ", match=" + match);
        }

        switch (match) {
            case DATAUSAGE_ALL:
                break;

            case DATAUSAGE_ID:
                break;

            case DATAUSAGE_UID:
                break;

            default:
                Log.e(TAG, "query: invalid request: " + uri);
                return null;
        }

        Cursor cursor;
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        cursor = qb.query(db, projection, selection, selectionArgs, null, null, sortOrder);

        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        int match = sURIMatcher.match(uri);

        switch(match) {
            case DATAUSAGE_ALL:
                return "vnd.android.cursor.dir/datausage_entry";
            case DATAUSAGE_ID:
            case DATAUSAGE_UID:
                return "vnd.android.cursor.item/datausage_entry";
            default:
                throw new IllegalArgumentException("UNKNOWN URI: " + uri);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        int match = sURIMatcher.match(uri);
        if (DEBUG) {
            Log.v(TAG, "Insert uri=" + uri + ", match=" + match);
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        long rowID = db.insert(DataUsageContract.DATAUSAGE_TABLE, null, values);

        if (DEBUG) {
            Log.v(TAG, "inserted " + values + " rowID=" + rowID);
        }

        return ContentUris.withAppendedId(DataUsageContract.CONTENT_URI, rowID);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int match = sURIMatcher.match(uri);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        if (DEBUG) {
            Log.v(TAG, "Delete uri=" + uri + ", match=" + match);
        }

        switch(match) {
            case DATAUSAGE_ALL:
                break;
            case DATAUSAGE_UID:
                if (selection != null || selectionArgs != null) {
                    throw new UnsupportedOperationException(
                            "Cannot delete URI:" + uri + " with a select clause"
                    );
                }
                String uidNumber = uri.getLastPathSegment();
                selection = DataUsageContract.UID + " = ? ";
                selectionArgs = new String [] {uidNumber};
                break;
            default:
                throw new UnsupportedOperationException(
                        "Cannot delete URI:" + uri
                );
        }
        int count = db.delete(DataUsageContract.DATAUSAGE_TABLE, selection, selectionArgs);
        return count;
    }

    // update is always done by UID
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        long count = 0;
        int match = sURIMatcher.match(uri);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        String uid;

        if (DEBUG) {
            Log.v(TAG, "Update uri=" + uri + ", match=" + match);
        }

        switch(match) {
            case DATAUSAGE_ALL:
                uid = selectionArgs[0];
                break;
            case DATAUSAGE_UID:
                if (selection != null || selectionArgs != null) {
                    throw new UnsupportedOperationException(
                            "Cannot update URI " + uri + " with a select clause"
                    );
                }
                selection = DataUsageContract.UID + " = ? ";
                uid = uri.getLastPathSegment();
                selectionArgs = new String [] { uid };
                break;
            default:
                throw new UnsupportedOperationException("Cannot update that URI: " + uri);

        }

        // if no record is found, then perform an insert, so make the db transaction atomic
        if (DEBUG) {
            Log.v(TAG, "Update: Values:" + values.toString() + " selection:" + selection + " " +
                    " selectionArgs:" + selectionArgs[0]);
        }
        // count = db.update(DATAUSAGE_TABLE, values, selection, selectionArgs);

        db.beginTransaction();
        try {
            count = db.update(DataUsageContract.DATAUSAGE_TABLE, values, selection, selectionArgs);

            if (DEBUG) {
                Log.v(TAG, "Update count:" + count);
            }
            if (count == 0) {
                if (DEBUG) {
                    Log.v(TAG, "Count==0, Performing Insert");
                }
                values.put(DataUsageContract.UID, uid);
                count = db.insert(DataUsageContract.DATAUSAGE_TABLE, null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            if (DEBUG) {
                Log.v(TAG, "dbEndTransaction");
            }
            db.endTransaction();
        }
        if (DEBUG) {
            Log.v(TAG, "Update result for uri=" + uri + " count=" + count);
        }
        return (int)count;
    }
}
