package dev.nullclient.gui;

/** Lines and arrows drawn with small rectangles. */
final class Shapes {
    private Shapes() {
    }

    static void line(Ui ui, int x0, int y0, int x1, int y1, int color, int th) {
        int dx = x1 - x0;
        int dy = y1 - y0;
        int steps = Math.min(600, Math.max(Math.abs(dx), Math.abs(dy)));
        if (steps == 0) {
            ui.fill(x0, y0, th, th, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            ui.fill(x0 + dx * i / steps, y0 + dy * i / steps, th, th, color);
        }
    }

    static void arrow(Ui ui, int x0, int y0, int x1, int y1, int color) {
        line(ui, x0, y0, x1, y1, color, 2);
        double a = Math.atan2(y1 - y0, x1 - x0);
        for (double s : new double[]{2.6, -2.6}) {
            line(ui, x1, y1, x1 + (int) Math.round(Math.cos(a + s) * 7), y1 + (int) Math.round(Math.sin(a + s) * 7), color, 2);
        }
    }
}