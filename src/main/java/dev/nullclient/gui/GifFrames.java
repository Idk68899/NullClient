package dev.nullclient.gui;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Decodes an animated GIF into PNG frames using Java's built-in image decoder. Kept in its own class so a Java
 * without the image module only fails when a GIF is actually opened. Handles frame offsets and "restore to
 * background" disposal; other disposal modes are treated as "leave in place".
 */
final class GifFrames {
    private static final int MAX_FRAMES = 60;
    private static final int MAX_PIXELS = 512 * 512;

    record Result(List<byte[]> pngs, List<Integer> delaysMs, int w, int h) {
    }

    private GifFrames() {
    }

    private static Node child(Node parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeName().equals(name)) {
                return n;
            }
        }
        return null;
    }

    private static String attr(Node node, String name) {
        if (node == null) {
            return null;
        }
        NamedNodeMap a = node.getAttributes();
        Node v = a == null ? null : a.getNamedItem(name);
        return v == null ? null : v.getNodeValue();
    }

    private static int num(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static Result read(Path path) throws IOException {
        Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("gif");
        if (!it.hasNext()) {
            throw new IOException("this device's Java cannot read GIFs - use a PNG");
        }
        ImageReader reader = it.next();
        try (ImageInputStream in = ImageIO.createImageInputStream(path.toFile())) {
            reader.setInput(in, false);
            int count = Math.min(reader.getNumImages(true), MAX_FRAMES);
            if (count < 1) {
                throw new IOException("no frames in the GIF");
            }
            int w = 0;
            int h = 0;
            IIOMetadata sm = reader.getStreamMetadata();
            if (sm != null) {
                Node lsd = child(sm.getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
                w = num(attr(lsd, "logicalScreenWidth"), 0);
                h = num(attr(lsd, "logicalScreenHeight"), 0);
            }
            if (w <= 0 || h <= 0) {
                BufferedImage first = reader.read(0);
                w = first.getWidth();
                h = first.getHeight();
            }
            if ((long) w * h > MAX_PIXELS) {
                throw new IOException("GIF is too large (" + w + "x" + h + "); keep it under 512x512");
            }
            BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            List<byte[]> pngs = new ArrayList<>();
            List<Integer> delays = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                BufferedImage frame = reader.read(i);
                IIOMetadata im = reader.getImageMetadata(i);
                Node root = im.getAsTree("javax_imageio_gif_image_1.0");
                Node gce = child(root, "GraphicControlExtension");
                Node desc = child(root, "ImageDescriptor");
                int delay = num(attr(gce, "delayTime"), 10) * 10;
                if (delay < 20) {
                    delay = 100;
                }
                String disposal = attr(gce, "disposalMethod");
                Graphics2D g = canvas.createGraphics();
                g.drawImage(frame, num(attr(desc, "imageLeftPosition"), 0), num(attr(desc, "imageTopPosition"), 0), null);
                g.dispose();
                BufferedImage snapshot = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D sg = snapshot.createGraphics();
                sg.drawImage(canvas, 0, 0, null);
                sg.dispose();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(snapshot, "png", out);
                pngs.add(out.toByteArray());
                delays.add(delay);
                if ("restoreToBackgroundColor".equals(disposal)) {
                    Graphics2D cg = canvas.createGraphics();
                    cg.setComposite(AlphaComposite.Clear);
                    cg.fillRect(0, 0, w, h);
                    cg.dispose();
                }
            }
            return new Result(pngs, delays, w, h);
        } finally {
            reader.dispose();
        }
    }
}