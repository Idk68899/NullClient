package dev.nullclient.gui;

/** Animated backgrounds drawn by code (no image files): sakura petals, rain, stars, aurora, scanlines. */
final class Fx {
    static final String[] NAMES = {"", "sakura", "rain", "stars", "aurora", "scanlines"};

    private Fx() {
    }

    static String label(String key) {
        return key == null || key.isEmpty() ? "none" : key;
    }

    static String next(String current) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(current == null ? "" : current)) {
                return NAMES[(i + 1) % NAMES.length];
            }
        }
        return NAMES[1];
    }

    static void draw(Ui ui, String kind, int w, int h) {
        if (kind == null || kind.isEmpty() || w <= 0 || h <= 0) {
            return;
        }
        long t = System.currentTimeMillis();
        switch (kind) {
            case "sakura" -> {
                for (int i = 0; i < 36; i++) {
                    double speed = 0.018 + (i % 5) * 0.010;
                    int y = (int) ((t * speed + i * 41) % (h + 12)) - 6;
                    int x = (int) (((i * 67) % w) + Math.sin(t * 0.0013 + i) * 16);
                    int s = 2 + (i % 3);
                    ui.fill(x, y, s, s, 0xAAFFB6D2);
                    ui.fill(x + 1, y + 1, Math.max(1, s - 1), Math.max(1, s - 1), 0xCCFFDCE9);
                }
            }
            case "rain" -> {
                for (int i = 0; i < 60; i++) {
                    int y = (int) ((t * (0.20 + (i % 4) * 0.08) + i * 53) % (h + 20)) - 10;
                    ui.fill((i * 31) % w, y, 1, 6, 0x88A8C8FF);
                }
            }
            case "stars" -> {
                for (int i = 0; i < 70; i++) {
                    int a = (int) (0x30 + 0x70 * (0.5 + 0.5 * Math.sin(t * 0.002 + i)));
                    ui.fill((i * 89) % w, (i * 47) % h, i % 7 == 0 ? 2 : 1, i % 7 == 0 ? 2 : 1, (a << 24) | 0xFFFFFF);
                }
            }
            case "aurora" -> {
                int[] cols = {0x2233FF99, 0x2200EBFF, 0x22AA66FF, 0x22FF38CC};
                for (int k = 0; k < 8; k++) {
                    int by = (int) (h * 0.12 + Math.sin(t * 0.0005 + k * 0.9) * h * 0.07 + k * h * 0.05);
                    ui.fill(0, by, w, Math.max(6, h / 14), cols[k % 4]);
                }
            }
            case "scanlines" -> {
                for (int y = 0; y < h; y += 4) {
                    ui.fill(0, y, w, 1, 0x1A000000);
                }
                int sweep = (int) ((t * 0.08) % (h + 40)) - 20;
                ui.fill(0, sweep, w, 3, 0x22FFFFFF);
            }
            default -> {
            }
        }
    }
}