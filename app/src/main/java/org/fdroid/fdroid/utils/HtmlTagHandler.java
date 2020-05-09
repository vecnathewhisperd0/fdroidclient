/*
 * Copyright (C) 2010-12  Ciaran Gultnieks, ciaran@ciarang.com
 * Copyright (C) 2019 Michael Pöhn, michael.poehn@fsfe.org
 * Copyright (C) 2020 Isira Seneviratne, isirasen96@gmail.com
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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.fdroid.fdroid.utils;

import android.text.Editable;
import android.text.Html;

import org.xml.sax.XMLReader;

/**
 * Need this to add the unimplemented support for ordered and unordered
 * lists to Html.fromHtml().
 */
public class HtmlTagHandler implements Html.TagHandler {
    private int listNum;

    @Override
    public void handleTag(boolean opening, String tag, Editable output,
                          XMLReader reader) {
        switch (tag) {
            case "ul":
                if (opening) {
                    listNum = -1;
                } else {
                    output.append('\n');
                }
                break;
            case "ol":
                if (opening) {
                    listNum = 1;
                } else {
                    output.append('\n');
                }
                break;
            case "li":
                if (opening) {
                    if (listNum == -1) {
                        output.append("\t• ");
                    } else {
                        output.append("\t").append(Integer.toString(listNum)).append(". ");
                        listNum++;
                    }
                } else {
                    output.append('\n');
                }
                break;
        }
    }
}
