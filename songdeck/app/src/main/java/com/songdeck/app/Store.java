package com.songdeck.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Everything saved on the device: folders, projects and recorded audio. */
public final class Store {

    public static class Folder {
        public String id, name;
        Folder(String id, String name) { this.id = id; this.name = name; }
    }

    private final File root;
    private final Context ctx;

    public Store(Context c) {
        ctx = c.getApplicationContext();
        root = new File(ctx.getFilesDir(), "projects");
        root.mkdirs();
    }

    public static String newId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    // ---------- settings ----------

    private SharedPreferences prefs() { return ctx.getSharedPreferences("songdeck", Context.MODE_PRIVATE); }
    public int latencyMs() { return prefs().getInt("latencyMs", 100); }
    public void setLatencyMs(int v) { prefs().edit().putInt("latencyMs", v).apply(); }

    // ---------- folders ----------

    public List<Folder> folders() {
        List<Folder> out = new ArrayList<>();
        try {
            File f = new File(ctx.getFilesDir(), "folders.json");
            if (!f.exists()) return out;
            JSONArray a = new JSONArray(readText(f));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Folder(o.getString("id"), o.getString("name")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    public void saveFolders(List<Folder> list) {
        try {
            JSONArray a = new JSONArray();
            for (Folder f : list) {
                JSONObject o = new JSONObject();
                o.put("id", f.id); o.put("name", f.name);
                a.put(o);
            }
            writeText(new File(ctx.getFilesDir(), "folders.json"), a.toString());
        } catch (Exception ignored) { }
    }

    public Folder addFolder(String name) {
        List<Folder> l = folders();
        Folder f = new Folder(newId(), name);
        l.add(f);
        saveFolders(l);
        return f;
    }

    public void renameFolder(String id, String name) {
        List<Folder> l = folders();
        for (Folder f : l) if (f.id.equals(id)) f.name = name;
        saveFolders(l);
    }

    /** Deletes the folder; its projects go back to "Unsorted". */
    public void deleteFolder(String id) {
        List<Folder> l = folders();
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id.equals(id)) l.remove(i);
        saveFolders(l);
        for (Project p : listProjects()) {
            if (p.folderId.equals(id)) { p.folderId = ""; saveProject(p, false); }
        }
    }

    // ---------- projects ----------

    public File dir(String projectId) { return new File(root, projectId); }
    private File audioDir(String projectId) { return new File(dir(projectId), "audio"); }

    public List<Project> listProjects() {
        List<Project> out = new ArrayList<>();
        File[] ds = root.listFiles();
        if (ds == null) return out;
        for (File d : ds) {
            Project p = loadProject(d.getName());
            if (p != null) out.add(p);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    public Project newProject(String name, String folderId) {
        Project p = new Project();
        p.id = newId();
        p.name = name;
        p.folderId = folderId == null ? "" : folderId;
        p.created = p.modified = System.currentTimeMillis();
        audioDir(p.id).mkdirs();
        saveProject(p, true);
        return p;
    }

    public Project loadProject(String id) {
        try {
            File f = new File(dir(id), "project.json");
            if (!f.exists()) return null;
            Project p = Project.fromJson(new JSONObject(readText(f)));
            p.id = id;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    public void saveProject(Project p, boolean touch) {
        try {
            if (touch) p.modified = System.currentTimeMillis();
            File d = dir(p.id);
            d.mkdirs();
            File tmp = new File(d, "project.json.tmp");
            writeText(tmp, p.toJson().toString());
            File f = new File(d, "project.json");
            if (!tmp.renameTo(f)) { writeText(f, p.toJson().toString()); tmp.delete(); }
        } catch (Exception ignored) { }
    }

    public void deleteProject(String id) { deleteRec(dir(id)); }

    public Project duplicate(Project src) {
        Project p = loadProject(src.id);
        if (p == null) return null;
        p.id = newId();
        p.name = src.name + " copy";
        p.created = p.modified = System.currentTimeMillis();
        File from = audioDir(src.id), to = audioDir(p.id);
        to.mkdirs();
        File[] fs = from.listFiles();
        if (fs != null) for (File f : fs) copyFile(f, new File(to, f.getName()));
        saveProject(p, true);
        return p;
    }

    /** Removes recordings that no clip uses any more (called when leaving the editor). */
    public void cleanAudio(Project p) {
        Set<String> used = new HashSet<>();
        for (Project.Clip c : p.clips) used.add(c.src);
        File[] fs = audioDir(p.id).listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (n.endsWith(".pcm") && !used.contains(n.substring(0, n.length() - 4))) f.delete();
        }
    }

    // ---------- audio ----------

    public void writeAudio(String projectId, String src, short[] data, int len) {
        try {
            File d = audioDir(projectId);
            d.mkdirs();
            ByteBuffer bb = ByteBuffer.allocate(len * 2).order(ByteOrder.LITTLE_ENDIAN);
            bb.asShortBuffer().put(data, 0, len);
            try (FileOutputStream o = new FileOutputStream(new File(d, src + ".pcm"))) {
                o.write(bb.array());
            }
        } catch (Exception ignored) { }
    }

    public short[] readAudio(String projectId, String src) {
        try {
            File f = new File(audioDir(projectId), src + ".pcm");
            byte[] b = readBytes(f);
            short[] s = new short[b.length / 2];
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
            return s;
        } catch (Exception e) {
            return new short[0];
        }
    }

    // ---------- helpers ----------

    static byte[] readBytes(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(16, f.length()));
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        }
    }

    static String readText(File f) throws Exception { return new String(readBytes(f), "UTF-8"); }

    static void writeText(File f, String s) throws Exception {
        try (OutputStream o = new FileOutputStream(f)) { o.write(s.getBytes("UTF-8")); }
    }

    static void copyFile(File a, File b) {
        try (InputStream in = new FileInputStream(a); OutputStream o = new FileOutputStream(b)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
        } catch (Exception ignored) { }
    }

    static void deleteRec(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteRec(c);
        f.delete();
    }
}
