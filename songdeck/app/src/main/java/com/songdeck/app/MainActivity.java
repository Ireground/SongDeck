package com.songdeck.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Home screen: folders on the left, song ideas on the right. */
public class MainActivity extends Activity {

    private static final String ALL = "*all*";
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private Store store;
    private String currentFolder = ALL;
    private LinearLayout folderBox, projectBox;
    private TextView folderTitle;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        store = new Store(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        if (b != null) currentFolder = b.getString("folder", ALL);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(14));

        // ---- left: folders ----
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackground(Ui.round(Ui.PANEL, 14));
        left.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(14));
        root.addView(left, new LinearLayout.LayoutParams(Ui.dp(250), MATCH));

        left.addView(Ui.title(this, "SONG", "DECK", 24));
        TextView sub = Ui.label(this, "quick song ideas", 12, Ui.DIM, false);
        left.addView(sub);
        TextView fl = Ui.label(this, "FOLDERS", 11, Ui.DIM, true);
        fl.setPadding(0, Ui.dp(18), 0, Ui.dp(6));
        left.addView(fl);

        ScrollView fs = new ScrollView(this);
        folderBox = new LinearLayout(this);
        folderBox.setOrientation(LinearLayout.VERTICAL);
        fs.addView(folderBox);
        left.addView(fs, new LinearLayout.LayoutParams(MATCH, 0, 1));

        TextView newFolder = Ui.pill(this, "+ New folder", Ui.GREY, v -> askText("New folder", "", "Folder name", name -> {
            Store.Folder f = store.addFolder(name);
            currentFolder = f.id;
            refresh();
        }));
        LinearLayout.LayoutParams nfp = new LinearLayout.LayoutParams(MATCH, WRAP);
        nfp.topMargin = Ui.dp(8);
        left.addView(newFolder, nfp);

        // ---- right: projects ----
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, MATCH, 1);
        rp.leftMargin = Ui.dp(14);
        root.addView(right, rp);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        folderTitle = Ui.label(this, "", 22, Ui.TEXT, true);
        head.addView(folderTitle, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView settings = Ui.pill(this, "Settings", Ui.GREY, v -> showSettings());
        head.addView(settings);
        TextView newIdea = Ui.pill(this, "●  New idea", Ui.RED, v -> newIdea());
        newIdea.setTextSize(16);
        newIdea.setPadding(Ui.dp(22), Ui.dp(12), Ui.dp(22), Ui.dp(12));
        LinearLayout.LayoutParams nip = new LinearLayout.LayoutParams(WRAP, WRAP);
        nip.leftMargin = Ui.dp(10);
        head.addView(newIdea, nip);
        right.addView(head);

        ScrollView ps = new ScrollView(this);
        projectBox = new LinearLayout(this);
        projectBox.setOrientation(LinearLayout.VERTICAL);
        ps.addView(projectBox);
        LinearLayout.LayoutParams psp = new LinearLayout.LayoutParams(MATCH, 0, 1);
        psp.topMargin = Ui.dp(12);
        right.addView(ps, psp);

        setContentView(root);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("folder", currentFolder);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<Store.Folder> folders = store.folders();
        boolean exists = currentFolder.equals(ALL) || currentFolder.isEmpty();
        for (Store.Folder f : folders) if (f.id.equals(currentFolder)) exists = true;
        if (!exists) currentFolder = ALL;

        folderBox.removeAllViews();
        addFolderRow("All ideas", ALL, false);
        addFolderRow("Unsorted", "", false);
        for (Store.Folder f : folders) addFolderRow(f.name, f.id, true);

        List<Project> all = store.listProjects();
        String title = "All ideas";
        if (currentFolder.isEmpty()) title = "Unsorted";
        for (Store.Folder f : folders) if (f.id.equals(currentFolder)) title = f.name;
        folderTitle.setText(title);

        projectBox.removeAllViews();
        int shown = 0;
        for (Project p : all) {
            if (!currentFolder.equals(ALL) && !p.folderId.equals(currentFolder)) continue;
            projectBox.addView(projectCard(p, folders));
            shown++;
        }
        if (shown == 0) {
            TextView e = Ui.label(this, "No ideas here yet.\nTap  ● NEW IDEA  to start recording.", 15, Ui.DIM, false);
            e.setPadding(Ui.dp(6), Ui.dp(30), 0, 0);
            projectBox.addView(e);
        }
    }

    private void addFolderRow(String name, String id, boolean editable) {
        boolean sel = id.equals(currentFolder);
        int count = 0;
        TextView t = Ui.label(this, name, 15, sel ? Ui.TEXT : Ui.DIM, sel);
        t.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setBackground(Ui.round(sel ? Ui.PANEL2 : 0x00000000, 10));
        t.setOnClickListener(v -> { currentFolder = id; refresh(); });
        if (editable) {
            t.setOnLongClickListener(v -> { folderMenu(id, name); return true; });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = Ui.dp(2);
        folderBox.addView(t, lp);
    }

    private void folderMenu(String id, String name) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(name)
                .setItems(new String[]{"Rename", "Delete folder (ideas move to Unsorted)"}, (d, w) -> {
                    if (w == 0) askText("Rename folder", name, "Folder name", n -> { store.renameFolder(id, n); refresh(); });
                    else {
                        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                                .setTitle("Delete folder \"" + name + "\"?")
                                .setMessage("The ideas inside are kept and moved to Unsorted.")
                                .setPositiveButton("Delete", (d2, w2) -> { store.deleteFolder(id); refresh(); })
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                })
                .show();
    }

    private View projectCard(Project p, List<Store.Folder> folders) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.round(Ui.PANEL, 12));
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(10), Ui.dp(12));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.label(this, p.name, 17, Ui.TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);

        long secs = p.contentEnd() / Project.SR;
        String date = new SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()).format(new Date(p.modified));
        StringBuilder d = new StringBuilder();
        d.append(date).append("   •   ").append(String.format(Locale.US, "%d:%02d", secs / 60, secs % 60));
        d.append("   •   ").append(p.tempo).append(" BPM");
        d.append("   •   ").append(p.clips.size() + p.midiClips.size() == 1 ? "1 clip" : (p.clips.size() + p.midiClips.size()) + " clips");
        if (currentFolder.equals(ALL)) {
            String fname = "Unsorted";
            for (Store.Folder f : folders) if (f.id.equals(p.folderId)) fname = f.name;
            d.append("   •   ").append(fname);
        }
        TextView details = Ui.label(this, d.toString(), 12, Ui.DIM, false);
        details.setSingleLine(true);
        details.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(details);
        card.addView(info, new LinearLayout.LayoutParams(0, WRAP, 1));

        TextView move = Ui.pill(this, "Move", Ui.GREY, v -> moveDialog(p));
        card.addView(move);
        TextView more = Ui.pill(this, "⋮", Ui.GREY, v -> projectMenu(p));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(WRAP, WRAP);
        mp.leftMargin = Ui.dp(8);
        card.addView(more, mp);

        card.setOnClickListener(v -> open(p.id));
        card.setOnLongClickListener(v -> { projectMenu(p); return true; });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = Ui.dp(8);
        card.setLayoutParams(lp);
        return card;
    }

    private void projectMenu(Project p) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(p.name)
                .setItems(new String[]{"Open", "Rename", "Move to folder…", "Duplicate", "Delete"}, (d, w) -> {
                    switch (w) {
                        case 0: open(p.id); break;
                        case 1: askText("Rename idea", p.name, "Name", n -> {
                            Project fresh = store.loadProject(p.id);
                            if (fresh != null) { fresh.name = n; store.saveProject(fresh, false); }
                            refresh();
                        }); break;
                        case 2: moveDialog(p); break;
                        case 3: store.duplicate(p); refresh(); break;
                        case 4:
                            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                                    .setTitle("Delete \"" + p.name + "\"?")
                                    .setMessage("Its recordings will be deleted too. This can't be undone.")
                                    .setPositiveButton("Delete", (d2, w2) -> { store.deleteProject(p.id); refresh(); })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                            break;
                    }
                })
                .show();
    }

    private void moveDialog(Project p) {
        List<Store.Folder> folders = store.folders();
        List<String> names = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        names.add("Unsorted"); ids.add("");
        for (Store.Folder f : folders) { names.add(f.name); ids.add(f.id); }
        names.add("+ New folder…"); ids.add(null);
        int checked = Math.max(0, ids.indexOf(p.folderId));
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Move \"" + p.name + "\" to")
                .setSingleChoiceItems(names.toArray(new String[0]), checked, (d, w) -> {
                    d.dismiss();
                    String target = ids.get(w);
                    if (target == null) {
                        askText("New folder", "", "Folder name", n -> {
                            Store.Folder f = store.addFolder(n);
                            moveTo(p, f.id);
                        });
                    } else moveTo(p, target);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void moveTo(Project p, String folderId) {
        Project fresh = store.loadProject(p.id);
        if (fresh != null) { fresh.folderId = folderId; store.saveProject(fresh, false); }
        refresh();
    }

    private void newIdea() {
        String name = "Idea " + new SimpleDateFormat("MMM d  HH:mm", Locale.getDefault()).format(new Date());
        String folder = currentFolder.equals(ALL) ? "" : currentFolder;
        Project p = store.newProject(name, folder);
        open(p.id);
    }

    private void open(String id) {
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra("id", id);
        startActivity(i);
    }

    private void showSettings() {
        Settings.show(this, store, null);
    }

    void askText(String title, String initial, String hint, Consumer<String> done) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setText(initial);
        e.setHint(hint);
        e.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(MATCH, WRAP));
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    String s = e.getText().toString().trim();
                    if (!s.isEmpty()) done.accept(s);
                })
                .setNegativeButton("Cancel", null)
                .show();
        e.requestFocus();
    }
}
