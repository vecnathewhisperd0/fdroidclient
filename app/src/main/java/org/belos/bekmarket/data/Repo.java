/*
 * Copyright (C) 2016 Blue Jay Wireless
 * Copyright (C) 2014-2016 Daniel Martí <mvdan@mvdan.cc>
 * Copyright (C) 2014-2016 Hans-Christoph Steiner <hans@eds.org>
 * Copyright (C) 2014-2016 Peter Serwylo <peter@serwylo.com>
 * Copyright (C) 2015 Christian Morgner
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301, USA.
 */

package org.belos.belmarket.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.text.TextUtils;

import org.belos.belmarket.Utils;
import org.belos.belmarket.data.Schema.RepoTable.Cols;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;

public class Repo extends ValueObject {

    public static final int VERSION_DENSITY_SPECIFIC_ICONS = 11;

    public static final int PUSH_REQUEST_IGNORE = 0;
    public static final int PUSH_REQUEST_PROMPT = 1;
    public static final int PUSH_REQUEST_ACCEPT_ALWAYS = 2;

    protected long id;

    public String address;
    public String name;
    public String description;
    /** index version, i.e. what fdroidserver built it - 0 if not specified */
    public int version;
    public boolean inuse;
    public int priority;
    /** The signing certificate, {@code null} for a newly added repo */
    public String signingCertificate;
    /**
     * The SHA1 fingerprint of {@link #signingCertificate}, set to {@code null} when a
     * newly added repo did not include fingerprint. It should never be an
     * empty {@link String}, i.e. {@code ""} */
    public String fingerprint;
    /** maximum age of index that will be accepted - 0 for any */
    public int maxage;
    /** last etag we updated from, null forces update */
    public String lastetag;
    public Date lastUpdated;
    public boolean isSwap;

    public String username;
    public String password;

    /** When the signed repo index was generated, used to protect against replay attacks */
    public long timestamp;

    /** How to treat push requests included in this repo's index XML */
    public int pushRequests = PUSH_REQUEST_IGNORE;

    public Repo() {
    }

    public Repo(Cursor cursor) {

        checkCursorPosition(cursor);

        for (int i = 0; i < cursor.getColumnCount(); i++) {
            switch (cursor.getColumnName(i)) {
                case Cols._ID:
                    id = cursor.getInt(i);
                    break;
                case Cols.LAST_ETAG:
                    lastetag = cursor.getString(i);
                    break;
                case Cols.ADDRESS:
                    address = cursor.getString(i);
                    break;
                case Cols.DESCRIPTION:
                    description = cursor.getString(i);
                    break;
                case Cols.FINGERPRINT:
                    fingerprint = cursor.getString(i);
                    break;
                case Cols.IN_USE:
                    inuse = cursor.getInt(i) == 1;
                    break;
                case Cols.LAST_UPDATED:
                    lastUpdated = Utils.parseTime(cursor.getString(i), null);
                    break;
                case Cols.MAX_AGE:
                    maxage = cursor.getInt(i);
                    break;
                case Cols.VERSION:
                    version = cursor.getInt(i);
                    break;
                case Cols.NAME:
                    name = cursor.getString(i);
                    break;
                case Cols.SIGNING_CERT:
                    signingCertificate = cursor.getString(i);
                    break;
                case Cols.PRIORITY:
                    priority = cursor.getInt(i);
                    break;
                case Cols.IS_SWAP:
                    isSwap = cursor.getInt(i) == 1;
                    break;
                case Cols.USERNAME:
                    username = cursor.getString(i);
                    break;
                case Cols.PASSWORD:
                    password = cursor.getString(i);
                    break;
                case Cols.TIMESTAMP:
                    timestamp = cursor.getLong(i);
                    break;
                case Cols.PUSH_REQUESTS:
                    pushRequests = cursor.getInt(i);
                    break;
            }
        }
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return address;
    }

    public boolean isSigned() {
        return !TextUtils.isEmpty(this.signingCertificate);
    }

    /**
     * This happens when a repo is configed with a fingerprint, but the client
     * has not connected to it yet to download its signing certificate
     */
    public boolean isSignedButUnverified() {
        return TextUtils.isEmpty(this.signingCertificate) && !TextUtils.isEmpty(this.fingerprint);
    }

    public boolean hasBeenUpdated() {
        return this.lastetag != null;
    }

    /**
     * If we haven't run an update for this repo yet, then the name
     * will be unknown, in which case we will just take a guess at an
     * appropriate name based on the url (e.g. "f-droid.org/archive")
     */
    public static String addressToName(String address) {
        String tempName;
        try {
            URL url = new URL(address);
            tempName = url.getHost() + url.getPath();
        } catch (MalformedURLException e) {
            tempName = address;
        }
        return tempName;
    }

    private static int toInt(Integer value) {
        if (value == null) {
            return 0;
        }
        return value;
    }

    public void setValues(ContentValues values) {

        if (values.containsKey(Cols._ID)) {
            id = toInt(values.getAsInteger(Cols._ID));
        }

        if (values.containsKey(Cols.LAST_ETAG)) {
            lastetag = values.getAsString(Cols.LAST_ETAG);
        }

        if (values.containsKey(Cols.ADDRESS)) {
            address = values.getAsString(Cols.ADDRESS);
        }

        if (values.containsKey(Cols.DESCRIPTION)) {
            description = values.getAsString(Cols.DESCRIPTION);
        }

        if (values.containsKey(Cols.FINGERPRINT)) {
            fingerprint = values.getAsString(Cols.FINGERPRINT);
        }

        if (values.containsKey(Cols.IN_USE)) {
            inuse = toInt(values.getAsInteger(Cols.IN_USE)) == 1;
        }

        if (values.containsKey(Cols.LAST_UPDATED)) {
            final String dateString = values.getAsString(Cols.LAST_UPDATED);
            lastUpdated = Utils.parseTime(dateString, null);
        }

        if (values.containsKey(Cols.MAX_AGE)) {
            maxage = toInt(values.getAsInteger(Cols.MAX_AGE));
        }

        if (values.containsKey(Cols.VERSION)) {
            version = toInt(values.getAsInteger(Cols.VERSION));
        }

        if (values.containsKey(Cols.NAME)) {
            name = values.getAsString(Cols.NAME);
        }

        if (values.containsKey(Cols.SIGNING_CERT)) {
            signingCertificate = values.getAsString(Cols.SIGNING_CERT);
        }

        if (values.containsKey(Cols.PRIORITY)) {
            priority = toInt(values.getAsInteger(Cols.PRIORITY));
        }

        if (values.containsKey(Cols.IS_SWAP)) {
            isSwap = toInt(values.getAsInteger(Cols.IS_SWAP)) == 1;
        }

        if (values.containsKey(Cols.USERNAME)) {
            username = values.getAsString(Cols.USERNAME);
        }

        if (values.containsKey(Cols.PASSWORD)) {
            password = values.getAsString(Cols.PASSWORD);
        }

        if (values.containsKey(Cols.TIMESTAMP)) {
            timestamp = toInt(values.getAsInteger(Cols.TIMESTAMP));
        }

        if (values.containsKey(Cols.PUSH_REQUESTS)) {
            pushRequests = toInt(values.getAsInteger(Cols.PUSH_REQUESTS));
        }
    }
}
