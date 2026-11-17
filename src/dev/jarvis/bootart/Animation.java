package dev.jarvis.bootart;

import android.graphics.BitmapFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

final class Animation implements AutoCloseable {
    static final int MAX_ZIP = 48 * 1024 * 1024;
    private static final int MAX_FRAME = 6 * 1024 * 1024;
    private static final Pattern FRAME = Pattern.compile("[A-Za-z0-9_-]{1,48}/[A-Za-z0-9_.-]{1,80}\\.(png|jpg|jpeg)", Pattern.CASE_INSENSITIVE);
    final ZipFile zip;
    final ArrayList<String> frames = new ArrayList<>();
    final int width, height, fps;

    Animation(File file) throws IOException {
        if (file.length() > MAX_ZIP) throw new IOException("ZIP exceeds 48 MB");
        zip = new ZipFile(file);
        try {
            ZipEntry desc = zip.getEntry("desc.txt");
            if (desc == null || desc.getSize() < 1 || desc.getSize() > 8192)
                throw new IOException("ZIP needs a valid desc.txt at its root");
            String[] lines;
            try (InputStream in = zip.getInputStream(desc)) {
                lines = new String(readLimited(in, 8192), StandardCharsets.UTF_8).trim().split("\\R");
            }
            String[] dimensions = lines[0].trim().split("\\s+");
            if (dimensions.length != 3) throw new IOException("Use WIDTH HEIGHT FPS on the first line");
            width = number(dimensions[0], 1, 3840);
            height = number(dimensions[1], 1, 2160);
            fps = number(dimensions[2], 1, 60);
            if (lines.length < 2 || lines.length > 33) throw new IOException("Use 1 to 32 animation parts");
            Map<String,List<String>> parts = new HashMap<>();
            Set<String> names = new HashSet<>();
            long total = 0;
            int entries = 0;
            for (ZipEntry e : Collections.list(zip.entries())) {
                if (++entries > 1600) throw new IOException("Too many files");
                String name = e.getName();
                if (!names.add(name)) throw new IOException("Duplicate ZIP entry: " + name);
                if (e.isDirectory()) {
                    if (!name.matches("[A-Za-z0-9_-]{1,48}/")) throw new IOException("Unexpected directory: " + name);
                    continue;
                }
                if (name.equals("desc.txt")) continue;
                if (!FRAME.matcher(name).matches() || e.getSize() <= 0 || e.getSize() > MAX_FRAME)
                    throw new IOException("Unsupported frame: " + name);
                total += e.getSize();
                if (total > MAX_ZIP) throw new IOException("Uncompressed frames exceed 48 MB");
                parts.computeIfAbsent(name.substring(0, name.indexOf('/')), key -> new ArrayList<>()).add(name);
            }
            Set<String> used = new HashSet<>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                String[] fields = line.split("\\s+");
                if (fields.length < 4 || !fields[0].matches("[pcf]"))
                    throw new IOException("Unsupported animation instruction on line " + (i + 1));
                number(fields[1], 0, 1000);
                number(fields[2], 0, 1000);
                if (fields[0].equals("f")) {
                    if (fields.length != 5) throw new IOException("Fade parts need a fade frame count");
                    number(fields[4], 0, 1000);
                } else if (fields.length != 4) {
                    throw new IOException("This version supports simple parts without clock or color options");
                }
                List<String> section = parts.get(fields[3]);
                if (section == null || !used.add(fields[3])) throw new IOException("Missing or repeated part: " + fields[3]);
                Collections.sort(section);
                frames.addAll(section);
            }
            if (frames.isEmpty() || !used.equals(parts.keySet())) throw new IOException("Every frame must belong to a listed part");
        } catch (IOException | RuntimeException invalid) {
            zip.close();
            throw invalid;
        }
    }

    private static int number(String text, int min, int max) throws IOException {
        try {
            int value = Integer.parseInt(text);
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) { }
        throw new IOException("Number outside allowed range: " + text);
    }

    static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        copyLimited(input, buffer, limit);
        return buffer.toByteArray();
    }

    static void copyLimited(InputStream input, OutputStream out, int limit) throws IOException {
        byte[] chunk = new byte[32768];
        long total = 0;
        for (int count; (count = input.read(chunk)) != -1;) {
            total += count;
            if (total > limit) throw new IOException("File exceeds the size limit");
            out.write(chunk, 0, count);
        }
    }

    void normalize(File destination) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(destination))) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (entry.isDirectory()) continue;
                byte[] data;
                try (InputStream in = zip.getInputStream(entry)) {
                    data = readLimited(in, entry.getName().equals("desc.txt") ? 8192 : MAX_FRAME);
                }
                CRC32 crc = new CRC32();
                crc.update(data);
                if (data.length != entry.getSize() || crc.getValue() != entry.getCrc())
                    throw new IOException("Damaged ZIP entry: " + entry.getName());
                if (!entry.getName().equals("desc.txt")) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                    if (bounds.outWidth < 1 || bounds.outWidth > 3840 || bounds.outHeight < 1 || bounds.outHeight > 2160
                            || !("image/png".equals(bounds.outMimeType) || "image/jpeg".equals(bounds.outMimeType)))
                        throw new IOException("Invalid PNG/JPEG frame: " + entry.getName());
                }
                ZipEntry stored = new ZipEntry(entry.getName());
                stored.setMethod(ZipEntry.STORED);
                stored.setSize(data.length);
                stored.setCrc(crc.getValue());
                out.putNextEntry(stored);
                out.write(data);
                out.closeEntry();
            }
        }
    }

    @Override public void close() throws IOException { zip.close(); }
}
