package dev.nullclient.gui;

import java.util.Map;

/** Tiny 8x8 pixel icons for the menu, drawn with plain rectangles (no image files needed). */
final class Icons {
    private static final Map<String, String[]> MAP = Map.of(
            "AI", new String[]{".#.##.#.", "########", ".#....#.", "##.##.##", "##.##.##", ".#....#.", "########", ".#.##.#."},
            "Accounts", new String[]{"..####..", ".######.", ".######.", "..####..", "...##...", ".######.", "########", "########"},
            "POV", new String[]{"........", "..####..", ".#....#.", "#..##..#", "#..##..#", ".#....#.", "..####..", "........"},
            "Proxy", new String[]{"..####..", ".#.##.#.", "#..##..#", "########", "########", "#..##..#", ".#.##.#.", "..####.."},
            "Macros", new String[]{"..####..", ".######.", "########", "########", "########", "########", ".######.", "..####.."},
            "Logger", new String[]{"########", "#......#", "#.####.#", "#......#", "#.####.#", "#......#", "#.##...#", "########"},
            "Server Info", new String[]{"########", "#.#..#.#", "########", "########", "#.#..#.#", "########", "########", "..#..#.."},
            "Settings", new String[]{"...##...", ".######.", ".##..##.", "##....##", "##....##", ".##..##.", ".######.", "...##..."});

    private Icons() {
    }

    static void draw(Ui ui, String name, int x, int y, int color) {
        String[] bits = MAP.get(name);
        if (bits == null) {
            return;
        }
        for (int r = 0; r < 8; r++) {
            String row = bits[r];
            int c = 0;
            while (c < 8) {
                if (row.charAt(c) == '#') {
                    int start = c;
                    while (c < 8 && row.charAt(c) == '#') {
                        c++;
                    }
                    ui.fill(x + start, y + r, c - start, 1, color);
                } else {
                    c++;
                }
            }
        }
    }
}