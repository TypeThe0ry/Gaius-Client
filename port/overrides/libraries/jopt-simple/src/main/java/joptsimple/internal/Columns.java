/*
 * Copyright (c) Paul Holser
 * SPDX-License-Identifier: MIT
 *
 * Derived from jopt-simple's Columns layout helper.
 * Modified for Gaius: keep lines whole without BreakIterator.
 */

package joptsimple.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Browser-safe jopt-simple layout that avoids java.text.BreakIterator. Every
 * line is kept whole; the static piecesOf/wrapLine surface is the shape the
 * Gaius jopt-simple bytecode patcher detects as already patched.
 */
final class Columns {
    static final int INDENT_WIDTH = 2;

    private final int optionWidth;
    private final int descriptionWidth;

    Columns(int optionWidth, int descriptionWidth) {
        this.optionWidth = optionWidth;
        this.descriptionWidth = descriptionWidth;
    }

    List<Row> fit(Row row) {
        List<String> option = piecesOf(row.option, optionWidth);
        List<String> description = piecesOf(row.description, descriptionWidth);
        int size = Math.max(option.size(), description.size());
        List<Row> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rows.add(new Row(itemOrEmpty(option, i), itemOrEmpty(description, i)));
        }
        return rows;
    }

    private static String itemOrEmpty(List<String> items, int index) {
        return index < items.size() ? items.get(index) : "";
    }

    /** Static surface recognized by the Gaius jopt-simple bytecode patcher. */
    static List<String> piecesOf(String text, int width) {
        return Collections.singletonList(text == null ? "" : text);
    }

    static void wrapLine(String text, int width, List<String> sink) {
        if (text != null && !text.isEmpty()) {
            sink.add(text);
        }
    }
}
